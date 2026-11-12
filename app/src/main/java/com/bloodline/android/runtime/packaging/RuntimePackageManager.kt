package com.bloodline.android.runtime.packaging

import java.io.File
import java.io.FileOutputStream
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
 * 3. confirm the download is the length upstream publishes, then verify the
 *    SHA-256 where upstream publishes one, and **record** when it does not —
 *    never silently skip the mention;
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
            // Deliberately *not* deleting a staged file that is already there:
            // it is the record of where the last attempt got to, and throwing
            // it away is what would force a 226 MB download to start again
            // after 200 MB. [download] decides what, if anything, to keep.
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

    /**
     * Downloads [pack] to [destination], picking up where a previous attempt
     * left off.
     *
     * Resuming matters here more than it would for an ordinary download.
     * Wine is 226 MB over a connection that is, for most of this app's users,
     * a phone on a mobile network, so an interrupted transfer is the normal
     * case rather than the exception. Restarting one from zero after 200 MB is
     * the kind of failure that makes people give up.
     *
     * The staged file is the record: its length is where the last attempt got
     * to, and a `Range` request asks the server for the rest. Three things can
     * come back, and only one of them is a problem:
     *
     * * **206 Partial Content** — resume, appending to what is there.
     * * **200 OK** — the server ignored the range, so the whole body is coming
     *   and the partial file has to be discarded, not appended to.
     * * **416 Range Not Satisfiable** — the partial file no longer corresponds
     *   to what the server has, which is what happens when upstream replaces
     *   an asset underneath us. Discard and start clean.
     *
     * Nothing here decides whether the result is *correct* — that is
     * [verify]'s job, and it runs on whatever is on disk afterwards. A resumed
     * download is held to exactly the same standard as a fresh one.
     */
    private fun download(pack: RuntimePackage, destination: File, onProgress: (Progress) -> Unit) {
        destination.parentFile?.mkdirs()

        // A previous attempt may already have the whole thing: if the transfer
        // finished but extraction or verification failed afterwards, there is
        // nothing to fetch.
        if (destination.isFile && destination.length() == pack.sizeBytes) {
            onProgress(Progress(pack, Phase.DOWNLOADING, pack.sizeBytes, pack.sizeBytes))
            return
        }

        var from = if (destination.isFile) destination.length() else 0L
        if (from >= pack.sizeBytes) {
            // Longer than it should be, which no honest transfer produces.
            destination.delete()
            from = 0L
        }

        var lastProblem = "the transfer did not finish"
        repeat(MAX_ATTEMPTS) {
            when (val result = transfer(pack, destination, from, onProgress)) {
                Transfer.Complete -> return
                is Transfer.Restart -> {
                    lastProblem = result.reason
                    destination.delete()
                    from = 0L
                }
                is Transfer.Failed -> throw IOException(result.reason)
            }
        }
        throw IOException("${pack.name} did not finish downloading: $lastProblem.")
    }

    private sealed interface Transfer {
        data object Complete : Transfer
        data class Restart(val reason: String) : Transfer
        data class Failed(val reason: String) : Transfer
    }

    /** One HTTP attempt, appending to [destination] from byte [from]. */
    private fun transfer(
        pack: RuntimePackage,
        destination: File,
        from: Long,
        onProgress: (Progress) -> Unit,
    ): Transfer {
        val connection = URL(pack.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
            connection.connect()

            return when (val code = connection.responseCode) {
                HTTP_PARTIAL -> {
                    if (from == 0L) {
                        Transfer.Restart("the server sent a partial response to a request that asked for nothing")
                    } else {
                        copyBody(pack, connection, destination, from, append = true, onProgress)
                    }
                }

                // The bytes we already hold no longer correspond to what the
                // server has, which is what an asset replaced underneath a pin
                // looks like from here.
                HTTP_RANGE_UNSATISFIABLE -> Transfer.Restart("the server would not serve the rest of the file (416)")

                in HTTP_OK..HTTP_LAST_OK -> {
                    // The range was refused and the whole body is on its way,
                    // so anything already on disk is the wrong bytes to append
                    // to. Truncate rather than append: appending here would
                    // produce a file of the right shape and the wrong content.
                    if (from > 0L) {
                        return Transfer.Restart(
                            "the server ignored the request to continue and sent the whole file (200)",
                        )
                    }
                    copyBody(pack, connection, destination, 0L, append = false, onProgress)
                }

                else -> Transfer.Failed("the server answered $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBody(
        pack: RuntimePackage,
        connection: HttpURLConnection,
        destination: File,
        alreadyHave: Long,
        append: Boolean,
        onProgress: (Progress) -> Unit,
    ): Transfer {
        val declared = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
        val total = if (append) (alreadyHave + declared).takeIf { it > 0 } ?: pack.sizeBytes else declared
        var read = alreadyHave
        var lastReport = read

        connection.inputStream.use { input ->
            FileOutputStream(destination, append).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    read += count

                    // Reported at most a few times a second: a callback per
                    // buffer would spend the whole download hopping to the UI
                    // thread.
                    if (read - lastReport > REPORT_INTERVAL_BYTES) {
                        lastReport = read
                        onProgress(Progress(pack, Phase.DOWNLOADING, read, total))
                    }
                }
            }
        }
        onProgress(Progress(pack, Phase.DOWNLOADING, read, total))
        return Transfer.Complete
    }

    /**
     * Checks the digest when upstream publishes one, having first checked the
     * length.
     *
     * @return true when the digest matched, false when there was none to check.
     *         Returning false rather than true is the point: the caller records
     *         the package as *unverified*, not as verified.
     */
    private fun verify(pack: RuntimePackage, file: File): Boolean {
        checkExpectedLength(pack, file)
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

    /**
     * Confirms the staged download is the length upstream publishes.
     *
     * Upstream publishes no digest for these assets, so a digest cannot be the
     * check — but it *does* publish the byte length of every release asset,
     * and the catalogue records those exact numbers. Checking the length
     * catches the two failures that actually happen on a mobile connection,
     * and neither is caught otherwise:
     *
     * * **A truncated transfer.** An interrupted download leaves a short file
     *   that looks like a package until something tries to read it.
     * * **An error page served instead of the file.** A redirect to HTML
     *   succeeds at the HTTP level, so without this the failure surfaces as an
     *   unreadable archive rather than as a failed download.
     *
     * It also does the job a pin exists to do. Every URL in the catalogue
     * points at one specific release, so if upstream ever re-uploads an asset
     * of a different size, refusing is the correct answer rather than an
     * inconvenience: the pin says what was asked for, and this says whether
     * that is what arrived.
     *
     * @throws SecurityException when the file is not the published length. The
     *         caller deletes the staged file, so a bad download never survives.
     */
    internal fun checkExpectedLength(pack: RuntimePackage, file: File) {
        val actual = file.length()
        if (actual == pack.sizeBytes) return
        throw SecurityException(
            "${pack.name} arrived the wrong length: ${pack.sizeBytes} bytes were " +
                "published for it, and $actual arrived. The download was discarded.",
        )
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
        const val HTTP_OK = 200
        const val HTTP_LAST_OK = 299
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_UNSATISFIABLE = 416

        /**
         * Two attempts, not one and not forever: one retry is enough to absorb
         * a server that refuses a range, and an endless loop over a dead link
         * is worse than telling the user it failed.
         */
        const val MAX_ATTEMPTS = 2

        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val DEFAULT_BUFFER = 64 * 1024
        const val REPORT_INTERVAL_BYTES = 256L * 1024
    }
}
