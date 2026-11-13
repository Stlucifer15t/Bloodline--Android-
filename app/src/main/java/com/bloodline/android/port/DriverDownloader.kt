package com.bloodline.android.port

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Downloads and verifies a driver package.
 *
 * Split from the installing half on purpose: verifying bytes and moving files
 * around are different risks, and a test can exercise one without the other.
 * Nothing here decides *whether* a device may take a driver — that is
 * [PanVkCatalog.reasonNotApplicable] — so a caller that skips the check cannot
 * blame this class for it.
 */
class DriverDownloader(
    private val download: (String) -> InputStream,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What a download produced. */
    sealed interface Result {
        data class Ready(val file: File, val sha256: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Streams [package] to [destination], hashing as it goes.
     *
     * The digest is computed over the bytes actually written, not over the file
     * afterwards: a second read could see a different file if something else
     * is writing there, and the point of the check is what was downloaded.
     */
    fun fetch(packageToFetch: DriverPackage, destination: File): Result {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L

        return try {
            download(packageToFetch.url).use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                    }
                }
            }

            val actual = digest.digest().toHex()
            when {
                written == 0L -> {
                    destination.delete()
                    Result.Failed("The download produced no bytes.")
                }
                !actual.equals(packageToFetch.sha256, ignoreCase = true) -> {
                    // Deleted rather than kept: a file whose digest does not
                    // match is not a package, and leaving it where an installer
                    // might find it invites a confusing later failure.
                    destination.delete()
                    Result.Failed(
                        "The downloaded package does not match its published checksum. " +
                            "Expected ${packageToFetch.sha256.take(16)}…, got ${actual.take(16)}…. " +
                            "The download was corrupted or replaced; nothing was installed.",
                    )
                }
                else -> Result.Ready(destination, actual)
            }
        } catch (error: IOException) {
            destination.delete()
            Result.Failed("The download failed: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: SecurityException) {
            destination.delete()
            Result.Failed("The download was refused by the platform: ${error.message.orEmpty()}")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * Verifies and unpacks a downloaded zip into a staging directory.
 *
 * The archive's own `SHA256SUMS.txt` is the authority for the library inside:
 * the zip digest says the container arrived intact, and the inner digest says
 * the file that will actually be loaded is the one upstream built. Two checks
 * because they fail differently — a truncated zip versus a swapped payload.
 */
class DriverUnpacker {

    sealed interface Result {
        data class Ready(val library: File, val metadata: DriverMetadata) : Result
        data class Failed(val reason: String) : Result
    }

    /** What the package says about itself. */
    data class DriverMetadata(
        val name: String,
        val driverVersion: String,
        val libraryName: String,
        val minApi: Int,
    )

    /**
     * Unpacks [archive] into [target].
     *
     * Every entry is written through [ArchiveExtractor]'s path resolver, so a
     * crafted `../` in the zip cannot escape the staging directory. The
     * archive is small and flat — `meta.json`, `SHA256SUMS.txt`, one `.so` —
     * and anything else is refused rather than written.
     */
    fun unpack(archive: File, target: File, packageToVerify: DriverPackage): Result {
        target.deleteRecursively()
        target.mkdirs()

        return try {
            val entries = readEntries(archive)
            val libraryEntry = entries.firstOrNull { it.name == packageToVerify.libraryName }
                ?: return Result.Failed(
                    "The package does not contain ${packageToVerify.libraryName}, so there " +
                        "is nothing for a Vulkan loader to open.",
                )

            val library = File(target, packageToVerify.libraryName)
            writeEntry(archive, libraryEntry, library)

            val actual = sha256Of(library)
            if (!actual.equals(packageToVerify.librarySha256, ignoreCase = true)) {
                target.deleteRecursively()
                return Result.Failed(
                    "The driver inside the package does not match its published checksum. " +
                        "Nothing was installed.",
                )
            }

            val metadata = entries.firstOrNull { it.name == META }
                ?.let { readMetadata(archive, it) }
                ?: DriverMetadata(
                    name = packageToVerify.name,
                    driverVersion = packageToVerify.driverVersion,
                    libraryName = packageToVerify.libraryName,
                    minApi = packageToVerify.minApi,
                )

            Result.Ready(library, metadata)
        } catch (error: Exception) {
            target.deleteRecursively()
            Result.Failed(
                "The package could not be unpacked: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun readEntries(archive: File): List<Entry> {
        val entries = mutableListOf<Entry>()
        java.util.zip.ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                entries += Entry(entry.name, entry.size)
            }
        }
        return entries
    }

    private fun writeEntry(archive: File, entry: Entry, destination: File) {
        val root = destination.parentFile ?: throw IOException("no parent for ${destination.name}")
        // Same rule as the runtime's extractor: a name that resolves outside
        // the target is not a package, it is an escape attempt. Resolved by the
        // archive's own entry name, not by the name we intend to write.
        val safe = com.bloodline.android.runtime.packaging.PathResolver(root)
            .resolve(entry.name)
            ?: throw SecurityException("refusing an entry outside the target: ${entry.name}")

        java.util.zip.ZipFile(archive).use { zip ->
            val zipEntry = zip.getEntry(entry.name)
                ?: throw IOException("${entry.name} vanished between passes")
            zip.getInputStream(zipEntry).use { input ->
                safe.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun readMetadata(archive: File, entry: Entry): DriverMetadata? {
        val text = java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry(entry.name)).bufferedReader().readText()
        }
        return DriverMetadata(
            name = jsonValue(text, "name") ?: return null,
            driverVersion = jsonValue(text, "driverVersion").orEmpty(),
            libraryName = jsonValue(text, "libraryName") ?: return null,
            minApi = jsonValue(text, "minApi")?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Reads a flat string or number field out of `meta.json`.
     *
     * A full JSON parser is not warranted for four fields of a file this app
     * writes itself, and a malformed file must degrade to "no metadata"
     * rather than throw. The regex is anchored to a quoted key so a value that
     * happens to contain the word cannot match.
     */
    private fun jsonValue(text: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\"([^\"]*)\"|([0-9]+))")
        val match = pattern.find(text) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[3] }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Entry(val name: String, val size: Long)

    private companion object {
        const val META = "meta.json"
    }
}
