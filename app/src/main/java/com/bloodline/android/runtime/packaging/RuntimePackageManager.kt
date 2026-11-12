package com.bloodline.android.runtime.packaging

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Acquires, verifies and installs runtime packages.
 *
 * The sequence is deliberately strict, because this code downloads and unpacks
 * executables from the network:
 *
 * 1. refuse a package for the wrong ABI;
 * 2. download to a **staging** file, never straight into place;
 * 3. verify the SHA-256 where upstream publishes one, and **record** when it
 *    does not — never silently skip the mention;
 * 4. extract, with path traversal refused;
 * 5. only then write the install marker.
 *
 * A package is therefore never "installed" while it is half-written, and the
 * launch gate cannot be fooled by a partial download.
 */
class RuntimePackageManager(
    private val store: RuntimePackageStore,
    private val extractor: ArchiveExtractor = ArchiveExtractor(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Where one package has got to. */
    sealed interface State {
        data object Missing : State
        data class Installed(val version: String, val verified: Boolean) : State
        data class Stale(val installed: String, val expected: String) : State
        data class Failed(val reason: String) : State
    }

    /** Progress while a package is being acquired. */
    data class Progress(
        val pack: RuntimePackage,
        val phase: Phase,
        val bytesRead: Long = 0L,
        val totalBytes: Long = 0L,
    ) {
        val percent: Int
            get() = if (totalBytes <= 0) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    enum class Phase { DOWNLOADING, VERIFYING, EXTRACTING, DONE, FAILED }

    fun stateOf(pack: RuntimePackage): State = when (val state = store.stateOf(pack)) {
        is RuntimePackageStore.InstallState.Installed ->
            State.Installed(state.version, verified = pack.sha256 != null)
        is RuntimePackageStore.InstallState.Stale ->
            State.Stale(state.installed, state.expected)
        RuntimePackageStore.InstallState.Missing -> State.Missing
    }

    /**
     * Downloads and installs one package.
     *
     * @return [State.Installed] on success, [State.Failed] with the reason on
     *         any problem. Never throws for an expected failure.
     */
    fun install(
        pack: RuntimePackage,
        abi: String,
        onProgress: (Progress) -> Unit = {},
    ): State {
        if (!abi.startsWith("arm64") && pack.abi.startsWith("arm64")) {
            return State.Failed("This package is built for ${pack.abi} and this device reports $abi.")
        }

        val staged = store.stagedFile(pack)
        try {
            staged.parentFile?.mkdirs()
            if (staged.exists()) staged.delete()

            download(pack, staged, onProgress)
            onProgress(Progress(pack, Phase.VERIFYING))

            val verified = verify(pack, staged)
            onProgress(Progress(pack, Phase.EXTRACTING))

            val target = store.packageDir(pack)
            if (target.exists()) target.deleteRecursively()

            when (val result = extractor.extract(staged, target)) {
                is ArchiveExtractor.Result.Success -> Unit
                is ArchiveExtractor.Result.Failure -> {
                    target.deleteRecursively()
                    return State.Failed(result.reason)
                }
            }

            store.markInstalled(pack, clock())
            staged.delete()
            onProgress(Progress(pack, Phase.DONE))
            return State.Installed(pack.version, verified = verified)
        } catch (error: IOException) {
            staged.delete()
            return State.Failed("Downloading ${pack.name} failed: ${error.message}")
        } catch (error: SecurityException) {
            staged.delete()
            return State.Failed("${pack.name} was refused: ${error.message}")
        }
    }

    /**
     * True when the game can start: every required package is installed.
     *
     * "Installed" here means unpacked and marked. It does **not** mean the
     * binaries have been executed successfully — that is a separate question,
     * and no amount of package bookkeeping can answer it.
     */
    fun isRuntimeComplete(abi: String): Boolean =
        RuntimePackageCatalog.REQUIRED.all { stateOf(it) is State.Installed } &&
            abi.startsWith("arm64")

    /** What is missing, for the UI and for reports. */
    fun missingPackages(): List<RuntimePackage> =
        RuntimePackageCatalog.REQUIRED.filter { stateOf(it) !is State.Installed }

    private fun download(pack: RuntimePackage, destination: File, onProgress: (Progress) -> Unit) {
        val connection = URL(pack.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("the server answered $code")
            }

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: pack.sizeBytes
            var read = 0L
            var lastReport = 0L

            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count

                        // Reported at most a few times a second: a progress
                        // callback per buffer would spend the whole download
                        // hopping to the UI thread.
                        if (read - lastReport > REPORT_INTERVAL_BYTES) {
                            lastReport = read
                            onProgress(Progress(pack, Phase.DOWNLOADING, read, total))
                        }
                    }
                }
            }
            onProgress(Progress(pack, Phase.DOWNLOADING, read, total))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Checks the digest when upstream publishes one.
     *
     * @return true when the digest matched, false when there was none to check.
     *         Returning false rather than true is the point: the caller records
     *         the package as *unverified*, not as verified.
     */
    private fun verify(pack: RuntimePackage, file: File): Boolean {
        val expected = pack.sha256 ?: return false
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw SecurityException(
                "the checksum did not match (expected $expected, got $actual). " +
                    "The file was discarded.",
            )
        }
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val DEFAULT_BUFFER = 64 * 1024
        const val REPORT_INTERVAL_BYTES = 256L * 1024
    }
}
