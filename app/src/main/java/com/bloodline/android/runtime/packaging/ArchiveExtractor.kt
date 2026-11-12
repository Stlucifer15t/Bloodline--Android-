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
                else -> extractTar(archive, format, target, onProgress)
            }
            Result.Success(count, format)
        } catch (error: SecurityException) {
            target.deleteRecursively()
            Result.Failure("The archive contained an unsafe path and was refused: ${error.message}")
        } catch (error: Exception) {
            target.deleteRecursively()
            Result.Failure("Unpacking ${archive.name} failed: ${error.message}")
        }
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
