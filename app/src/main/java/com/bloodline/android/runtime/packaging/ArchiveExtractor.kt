package com.bloodline.android.runtime.packaging

import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a runtime package into its target directory.
 *
 * Two things this gets right that a naive version does not:
 *
 * 1. **Path traversal is rejected, not merely escaped.** A package is a file
 *    supplied by a third party over the network; an entry named
 *    `../../../../data/data/...` would otherwise let it write anywhere the app
 *    can. Any entry that resolves outside the target directory is refused and
 *    the whole extraction fails, because a half-written runtime is worse than
 *    none.
 * 2. **The format is detected from the bytes, not the extension.** Upstream
 *    packages use a private `.wcp` extension for what is, in fact, a tar
 *    archive compressed with either XZ or Zstandard — the filename carries no
 *    information, and upstream applies both compressions.
 *
 * The two decompressors are the only third-party code here. Both are needed:
 * inspecting the upstream installer showed it trying XZ first and falling back
 * to Zstandard, so a build that handles only one of them silently fails on
 * some packages. `org.tukaani:xz` is pure Java and public domain;
 * `zstd-jni` is the reference Zstandard binding. Neither is bundled into the
 * APK as native code of ours — they are ordinary dependencies.
 */
class ArchiveExtractor {

    /** What happened. */
    sealed interface Result {
        data class Success(val fileCount: Int, val format: ArchiveFormat) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Extracts [archive] into [target], replacing anything already there.
     *
     * @param onProgress called with the number of entries written so far.
     */
    fun extract(
        archive: File,
        target: File,
        onProgress: (Int) -> Unit = {},
    ): Result {
        // Refused at the door rather than halfway through: a corrupt archive
        // unpacked until it breaks leaves a half-written runtime behind, and
        // the directory has to be deleted to be sure.
        when (val inspection = inspect(archive)) {
            is Inspection.Accepted -> Unit
            is Inspection.Refused -> return Result.Failure(inspection.reason)
        }

        val format = detectFormat(archive)

        return try {
            target.mkdirs()
            val count = when (format) {
                ArchiveFormat.ZIP -> extractZip(archive, target, onProgress)
                else -> extractTar(archive, format, target, onProgress)
            }
            Result.Success(count, format)
        } catch (error: SecurityException) {
            target.deleteRecursively()
            Result.Failure("${archive.name} was refused: ${error.message}")
        } catch (error: Exception) {
            target.deleteRecursively()
            Result.Failure("Unpacking ${archive.name} failed: ${error.message}")
        }
    }

    /**
     * What [archive] is, and whether it is structurally sound enough to unpack.
     *
     * Run before extraction, on purpose. [detectFormat] only reads magic bytes,
     * which is not enough: a truncated download and an HTML error page both
     * have *some* first bytes, and both would otherwise be handed to the
     * extractor, which would then report a failure from the middle of a
     * decompression instead of at the door.
     *
     * For a tar-based archive this decompresses the first block and checks the
     * header's checksum, which is the cheapest structural claim there is: it
     * proves the file is a tar, and not merely that it started like one.
     */
    fun inspect(archive: File): Inspection {
        if (!archive.isFile) {
            return Inspection.Refused("There is no file at ${archive.path}.")
        }
        if (archive.length() == 0L) {
            return Inspection.Refused("${archive.name} is empty, so the download did not finish.")
        }

        val format = detectFormat(archive)
        if (format == ArchiveFormat.UNKNOWN) {
            return Inspection.Refused(
                "The format of ${archive.name} is not recognised. It may have been " +
                    "replaced by an error page, or the download may have been interrupted.",
            )
        }
        if (format == ArchiveFormat.ZIP) return Inspection.Accepted(format)

        return try {
            val header = firstBlock(archive, format)
            when {
                header == null -> Inspection.Refused(
                    "${archive.name} holds no data: the download was truncated.",
                )
                header.all { it == 0.toByte() } -> Inspection.Refused(
                    "${archive.name} begins with an empty tar block, so it is truncated.",
                )
                !header.tarChecksumIsValid() -> Inspection.Refused(
                    "${archive.name} does not begin with a valid tar header. The download " +
                        "is corrupt, or the compression is not the one its format claims.",
                )
                else -> Inspection.Accepted(format)
            }
        } catch (error: Exception) {
            Inspection.Refused("${archive.name} could not be read as ${format.title}: ${error.message}")
        }
    }

    /** What [inspect] concluded. */
    sealed interface Inspection {
        data class Accepted(val format: ArchiveFormat) : Inspection
        data class Refused(val reason: String) : Inspection
    }

    /** The first 512 bytes of a tar-based [archive], decompressed. */
    private fun firstBlock(archive: File, format: ArchiveFormat): ByteArray? {
        val block = ByteArray(TarExtractor.BLOCK_SIZE)
        decompressed(archive, format).use { stream ->
            var total = 0
            while (total < block.size) {
                val count = stream.read(block, total, block.size - total)
                if (count < 0) break
                total += count
            }
            return if (total == 0) null else block
        }
    }

    private fun decompressed(archive: File, format: ArchiveFormat): InputStream = when (format) {
        ArchiveFormat.TAR -> archive.inputStream()
        ArchiveFormat.TAR_XZ -> XZInputStream(archive.inputStream())
        ArchiveFormat.TAR_ZST -> ZstdInputStream(archive.inputStream())
        ArchiveFormat.TAR_GZ -> GZIPInputStream(archive.inputStream())
        else -> throw IllegalArgumentException("${format.title} is not a tar archive")
    }

    /** Reads the first bytes and identifies the container. */
    fun detectFormat(archive: File): ArchiveFormat {
        val header = ByteArray(512)
        val read = archive.inputStream().use { stream ->
            var total = 0
            while (total < header.size) {
                val count = stream.read(header, total, header.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return ArchiveFormat.detect(header.copyOf(read))
    }

    private fun extractZip(archive: File, target: File, onProgress: (Int) -> Unit): Int {
        val root = target.canonicalFile
        val resolver = PathResolver(root)
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = resolver.resolve(entry.name)
                    ?: throw SecurityException(entry.name)

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> copy(zip, out) }
                    // ZIP carries no Unix mode that Java exposes, so the
                    // decision is made from the file's contents instead.
                    ArchivePermissions.apply(destination)
                    count++
                    onProgress(count)
                }
                zip.closeEntry()
            }
        }
        return count
    }

    private fun extractTar(
        archive: File,
        format: ArchiveFormat,
        target: File,
        onProgress: (Int) -> Unit,
    ): Int {
        val decompressed: InputStream = when (format) {
            ArchiveFormat.TAR -> archive.inputStream()
            ArchiveFormat.TAR_XZ -> XZInputStream(archive.inputStream())
            ArchiveFormat.TAR_ZST -> ZstdInputStream(archive.inputStream())
            ArchiveFormat.TAR_GZ -> GZIPInputStream(archive.inputStream())
            else -> throw IllegalArgumentException("${format.title} is not a tar archive")
        }
        return BufferedInputStream(decompressed, BUFFER).use {
            TarExtractor().extract(it, target, onProgress)
        }
    }

    private fun copy(from: InputStream, to: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = from.read(buffer)
            if (read < 0) break
            to.write(buffer, 0, read)
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * Maps an archive entry name onto a path inside a root directory, refusing
 * anything that escapes.
 *
 * Shared by the ZIP and tar paths so the two cannot drift apart: a traversal
 * check implemented twice is a traversal check that gets fixed in one place.
 */
internal class PathResolver(private val root: File) {

    fun resolve(entryName: String): File? {
        val cleaned = entryName.trim().trimEnd('/')
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("/") || cleaned.startsWith("\\")) return null
        if (cleaned.split('/', '\\').any { it == ".." }) return null

        val destination = File(root, cleaned).canonicalFile
        return if (destination.path == root.path ||
            destination.path.startsWith(root.path + File.separator)
        ) {
            destination
        } else {
            null
        }
    }
}
