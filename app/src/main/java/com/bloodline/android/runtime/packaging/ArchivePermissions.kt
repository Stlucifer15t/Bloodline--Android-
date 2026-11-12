package com.bloodline.android.runtime.packaging

import java.io.File

/**
 * Decides and applies the permissions an extracted file needs.
 *
 * A package that unpacks with no execute bit anywhere is a package whose Wine
 * binary will not start, and the failure it produces is a bare "Permission
 * denied" with no hint of which file or why. Reference implementations handle
 * this the blunt way — `chmod 0755` on everything in `bin/` — which works but
 * only for files that happen to live in a directory called `bin`.
 *
 * Two sources are used here, either of which is enough on its own:
 *
 * 1. **The archive's own mode bits.** tar carries them; ZIP does not (Java does
 *    not expose the Unix field), which is why the second source exists.
 * 2. **The file's contents.** Anything beginning with the ELF magic, or with a
 *    shebang, is meant to be executed. This is what covers ZIP packages, and it
 *    covers a `bin/` directory under any other name.
 *
 * Nothing is ever made *less* permissive than a fresh file, and nothing outside
 * the target directory is touched: this runs only on files the extractor has
 * just written, inside the directory it wrote them to.
 */
internal object ArchivePermissions {

    /** ELF magic: 0x7F 'E' 'L' 'F'. */
    private val ELF = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

    /** "#!" — a script. */
    private val SHEBANG = byteArrayOf(0x23, 0x21)

    /**
     * Applies what [file] needs.
     *
     * @param modeBits the Unix mode from the archive, or 0 when the format
     *                 carries none.
     */
    fun apply(file: File, modeBits: Long = 0L) {
        if (!file.isFile) return

        val executable = (modeBits and EXECUTE_BITS) != 0L || looksExecutable(file)

        // Readable always: a runtime nobody can read is not a runtime.
        file.setReadable(true)
        // Owner-only rather than world-executable: the app's own process is the
        // only thing that should be starting these.
        file.setExecutable(executable)
    }

    /** True when the file's first bytes say it is a program. */
    fun looksExecutable(file: File): Boolean {
        val header = ByteArray(4)
        val read = runCatching {
            file.inputStream().use { stream ->
                var total = 0
                while (total < header.size) {
                    val count = stream.read(header, total, header.size - total)
                    if (count < 0) break
                    total += count
                }
                total
            }
        }.getOrNull() ?: return false

        if (read < 2) return false
        if (header.startsWith(SHEBANG)) return true
        return read == 4 && header.startsWith(ELF)
    }

    /** The three execute bits of a Unix mode: owner, group and other. */
    private const val EXECUTE_BITS = 0b001_001_001L

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
