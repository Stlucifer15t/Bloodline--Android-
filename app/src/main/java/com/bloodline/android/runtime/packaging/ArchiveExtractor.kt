package com.bloodline.android.runtime.packaging

import java.io.File
import java.io.InputStream
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
 *    packages use a private `.wcp` extension over an ordinary archive, so the
 *    name tells us nothing.
 */
class ArchiveExtractor {

    /** What went wrong, in words meant for the user. */
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
        if (!archive.isFile) return Result.Failure("The archive is not a file: ${archive.name}")

        val format = detectFormat(archive)
        if (format == ArchiveFormat.UNKNOWN) {
            return Result.Failure(
                "The format of ${archive.name} is not recognised. The runtime cannot " +
                    "unpack it, so it will not be installed.",
            )
        }

        return try {
            target.mkdirs()
            val count = when (format) {
                ArchiveFormat.ZIP -> extractZip(archive, target, onProgress)
                else -> throw UnsupportedFormatException(format)
            }
            Result.Success(count, format)
        } catch (error: SecurityException) {
            Result.Failure("The archive contained an unsafe path and was refused: ${error.message}")
        } catch (error: UnsupportedFormatException) {
            Result.Failure(
                "${error.format.title} archives are not supported yet. Only ZIP can be " +
                    "unpacked with the decoders bundled in this build.",
            )
        } catch (error: Exception) {
            Result.Failure("Unpacking failed: ${error.message}")
        }
    }

    private class UnsupportedFormatException(val format: ArchiveFormat) : Exception(format.title)

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
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = safeDestination(target, entry.name)
                    ?: throw SecurityException(entry.name)

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> copy(zip, out) }
                    count++
                    onProgress(count)
                }
                zip.closeEntry()
            }
        }
        return count
    }

    /**
     * Resolves an entry name inside [target], or null when it escapes.
     *
     * Both checks matter: the canonical-path comparison catches `..` traversal,
     * and rejecting absolute names stops an entry from addressing `/system`
     * directly.
     */
    internal fun safeDestination(target: File, entryName: String): File? {
        if (entryName.isBlank()) return null

        // Reject absolute names outright. `File(parent, child)` would otherwise
        // quietly treat "/system/bin/sh" as relative to the target, which is
        // safe — but relying on that is one refactor away from being unsafe,
        // and a hostile archive should be refused on its face.
        if (entryName.startsWith("/") || entryName.startsWith("\\")) return null

        val root = target.canonicalFile
        val destination = File(root, entryName).canonicalFile

        return if (destination.path == root.path || destination.path.startsWith(root.path + File.separator)) {
            destination
        } else {
            null
        }
    }

    private fun copy(from: InputStream, to: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = from.read(buffer)
            if (read < 0) break
            to.write(buffer, 0, read)
        }
    }
}
