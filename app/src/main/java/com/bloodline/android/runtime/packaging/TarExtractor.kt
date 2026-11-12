package com.bloodline.android.runtime.packaging

import java.io.File
import java.io.InputStream

/**
 * Reads a POSIX/GNU tar stream.
 *
 * Why hand-rolled rather than pulled in as a dependency: tar is a 512-byte
 * header format with a handful of interesting fields, and the runtime needs
 * exactly one operation from it — "write these entries under this directory,
 * refusing anything that escapes". A library would add a great deal of surface
 * (PAX attributes, sparse files, device nodes, seeks) that a package of Wine
 * binaries does not use and that we would then be responsible for auditing.
 *
 * Supported, because upstream packages use them:
 *
 * * `ustar` and GNU headers, including the `prefix` field for long paths;
 * * GNU `L` long names and `K` long link names;
 * * regular files and directories.
 *
 * Refused rather than emulated: hard links, device nodes, FIFOs and symlinks.
 * A package that needs those is not one to unpack silently, and a refusal is a
 * better outcome than a wrong guess. Symlinks are the notable one — a package
 * that links outside its own directory is exactly what this class exists to
 * stop — so they are skipped and *recorded*, not silently dropped.
 */
internal class TarExtractor {

    private enum class Kind { FILE, DIRECTORY, LONG_NAME, LONG_LINK_NAME, SKIPPABLE, REFUSED }

    /**
     * Extracts [input] into [target].
     *
     * @return the number of regular files written.
     * @throws SecurityException when an entry resolves outside [target] or is
     *         of a kind we refuse; the caller abandons the whole extraction,
     *         because a half-written runtime is worse than none.
     */
    fun extract(
        input: InputStream,
        target: File,
        onProgress: (Int) -> Unit = {},
    ): Int {
        val root = target.canonicalFile
        val stream = BlockStream(input)

        // GNU's long-name mechanism: the previous entry supplied the real name,
        // which did not fit in the header's 100-byte field.
        var pendingName: String? = null
        var pendingLinkName: String? = null
        var written = 0

        while (true) {
            val header = stream.nextBlock() ?: break
            // A zero block marks the end of the archive. Tar pads to a
            // multiple of the blocking factor with further zero blocks, so one
            // is enough to stop.
            if (header.isBlank()) break

            // Before any field in this header is believed. A stream that is
            // not a tar at all - an HTML error page served instead of the
            // download, a truncated transfer - still yields 512-byte blocks of
            // something, and without this they are read as names and sizes.
            // Garbage in, then, produces garbage paths rather than a refusal.
            if (!header.tarChecksumIsValid()) {
                throw SecurityException(
                    "a tar header failed its checksum, so the archive is corrupt " +
                        "or is not a tar archive at all",
                )
            }

            val typeflag = header[TYPEFLAG_OFFSET]
            val size = header.octalAt(SIZE_OFFSET, SIZE_LENGTH)
            val padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE
            val kind = kindOf(typeflag)

            val name = pendingName ?: header.name()
            val linkName = pendingLinkName ?: header.stringAt(LINK_NAME_OFFSET, LINK_NAME_LENGTH)
            pendingName = null
            pendingLinkName = null

            when (kind) {
                Kind.LONG_NAME -> {
                    pendingName = stream.ascii(size).trimEnd('\u0000')
                    stream.skip(padding)
                }

                Kind.LONG_LINK_NAME -> {
                    pendingLinkName = stream.ascii(size).trimEnd('\u0000')
                    stream.skip(padding)
                }

                Kind.FILE -> {
                    val destination = safeDestination(root, name)
                        ?: throw SecurityException(name)
                    destination.parentFile?.mkdirs()
                    stream.copyInto(destination, size)
                    stream.skip(padding)
                    ArchivePermissions.apply(destination, header.octalAt(MODE_OFFSET, MODE_LENGTH))
                    written++
                    onProgress(written)
                }

                Kind.DIRECTORY -> {
                    val destination = safeDestination(root, name)
                        ?: throw SecurityException(name)
                    destination.mkdirs()
                    stream.skip(padding)
                }

                Kind.SKIPPABLE -> {
                    recordSkipped(name, linkName)
                    stream.skip(size + padding)
                }

                Kind.REFUSED -> {
                    stream.skip(size + padding)
                    throw SecurityException(
                        "the entry '$name' is a hard link, device or FIFO, " +
                            "which this extractor will not create",
                    )
                }
            }
        }

        return written
    }

    /** Delegated so ZIP and tar cannot grow two different traversal rules. */
    internal fun safeDestination(root: File, entryName: String): File? =
        PathResolver(root).resolve(entryName)

    private fun kindOf(typeflag: Byte): Kind = when (typeflag) {
        TYPE_LONG_NAME -> Kind.LONG_NAME
        TYPE_LONG_LINK_NAME -> Kind.LONG_LINK_NAME
        TYPE_SYMLINK -> Kind.SKIPPABLE
        TYPE_DIRECTORY -> Kind.DIRECTORY
        TYPE_REGULAR, 0.toByte() -> Kind.FILE
        else -> Kind.REFUSED
    }

    private fun recordSkipped(name: String, linkName: String) {
        if (skipped.size < MAX_RECORDED) {
            skipped += if (linkName.isBlank()) name else "$name -> $linkName"
        }
    }

    private fun ByteArray.isBlank(): Boolean = all { it == 0.toByte() }

    private fun ByteArray.name(): String {
        val prefix = stringAt(PREFIX_OFFSET, PREFIX_LENGTH)
        val name = stringAt(NAME_OFFSET, NAME_LENGTH)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun ByteArray.stringAt(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    private fun ByteArray.octalAt(offset: Int, length: Int): Long {
        var result = 0L
        var started = false
        for (index in offset until offset + length) {
            val digit = this[index]
            if (digit == 0.toByte() || digit == SPACE) {
                if (started) break
                continue
            }
            if (digit < ZERO || digit > SEVEN) {
                throw SecurityException("a tar header held a size that is not octal")
            }
            started = true
            result = (result * 8) + (digit - ZERO)
        }
        return result
    }

    companion object {
        const val BLOCK_SIZE = 512

        private const val NAME_OFFSET = 0
        private const val NAME_LENGTH = 100
        private const val MODE_OFFSET = 100
        private const val MODE_LENGTH = 8
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val TYPEFLAG_OFFSET = 156
        private const val LINK_NAME_OFFSET = 157
        private const val LINK_NAME_LENGTH = 100
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_LENGTH = 155

        /** The header's own checksum field: 8 bytes of octal at 148. */
        private const val CHECKSUM_OFFSET = 148
        private const val CHECKSUM_LENGTH = 8

        // ASCII codes for the tar typeflag byte, written literally because a
        // `const val` cannot call `.code`.
        private const val TYPE_REGULAR: Byte = 0x30 // '0', and the old v7 default
        private const val TYPE_DIRECTORY: Byte = 0x35 // '5'
        private const val TYPE_SYMLINK: Byte = 0x32 // '2'
        private const val TYPE_LONG_NAME: Byte = 0x4C // 'L'
        private const val TYPE_LONG_LINK_NAME: Byte = 0x4B // 'K'


        private const val MAX_RECORDED = 50

        private const val SPACE: Byte = 0x20
        private const val ZERO: Byte = 0x30
        private const val SEVEN: Byte = 0x37

        private val skipped = mutableListOf<String>()

        /**
         * Entries skipped by the last extraction, for the diagnostics report.
         *
         * A runtime that works but skipped three symlinks is a different
         * problem from one that unpacked everything, so the difference is
         * recorded rather than swallowed.
         */
        fun skippedEntries(): List<String> = skipped.toList().also { skipped.clear() }
    }
}

/** Reads whole 512-byte blocks and knows how far to skip. */
private class BlockStream(private val source: InputStream) {

    fun nextBlock(): ByteArray? {
        val block = ByteArray(TarExtractor.BLOCK_SIZE)
        var offset = 0
        while (offset < block.size) {
            val read = source.read(block, offset, block.size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == 0) null else block
    }

    fun ascii(length: Long): String {
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = source.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    fun skip(count: Long) {
        var remaining = count
        val buffer = ByteArray(BUFFER)
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    fun copyInto(destination: File, length: Long) {
        destination.outputStream().buffered().use { out ->
            var remaining = length
            val buffer = ByteArray(BUFFER)
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * True when a 512-byte tar block's checksum field matches its contents.
 *
 * This is the cheapest structural claim that can be made about a tar archive,
 * and it is the one that matters at the door: a truncated download and an HTML
 * error page both still yield 512 bytes of *something*, and without this those
 * bytes are read as a filename and a size. Checking first turns "the archive
 * is unusable" from a failure discovered halfway through decompression into a
 * refusal before a single directory is created.
 *
 * The field is the sum of every byte in the block, counting the checksum field
 * itself as eight spaces. Both the signed and the unsigned sum are accepted,
 * because historic tar writers disagree about which one they wrote and
 * refusing a valid archive over that argument would be a poor trade.
 */
internal fun ByteArray.tarChecksumIsValid(): Boolean {
    if (size < TarExtractor.BLOCK_SIZE) return false
    val stored = tarOctal(CHECKSUM_OFFSET, CHECKSUM_LENGTH) ?: return false
    var unsigned = 0
    var signed = 0
    for (index in 0 until TarExtractor.BLOCK_SIZE) {
        val raw = this[index].toInt() and 0xFF
        // The checksum field counted itself as spaces, because that is what it
        // was when the writer added up the block.
        val byte = if (index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) 0x20 else raw
        unsigned += byte
        signed += if (byte >= 0x80) byte - 0x100 else byte
    }
    return stored == unsigned.toLong() || stored == signed.toLong()
}

/** Reads one octal tar field, or null when it is not octal at all. */
private fun ByteArray.tarOctal(offset: Int, length: Int): Long? {
    var result = 0L
    var started = false
    for (index in offset until offset + length) {
        val digit = this[index]
        if (digit == 0.toByte() || digit == 0x20.toByte()) {
            if (started) break
            continue
        }
        if (digit < 0x30.toByte() || digit > 0x37.toByte()) return null
        started = true
        result = (result * 8) + (digit - 0x30)
    }
    return result
}

private const val CHECKSUM_OFFSET = 148
private const val CHECKSUM_LENGTH = 8
