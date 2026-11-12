package com.bloodline.android.game

import com.bloodline.android.storage.InstallMetadata
import com.bloodline.android.storage.InstallMetadataStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns everything the launcher knows about the game files.
 *
 * Two problems this class exists to solve:
 *
 * 1. **The black screen during import.** A scan walks thousands of entries, and
 *    over the Storage Access Framework every entry is a binder call. Running
 *    that on the main thread freezes the UI. Every scan here runs on a
 *    background executor and reports its real progress back.
 * 2. **The slow reopen.** A verified result is remembered, so starting the app
 *    shows what was already known instead of walking the whole tree again. A
 *    rescan only happens when there is a reason: nothing cached yet, the cache
 *    is stale, the search folders changed, or the user asked for it.
 *
 * Nothing in here writes to the game files. The scan only ever reads.
 */
class GameInstallRepository(
    private val roots: () -> List<StorageFolder>,
    private val metadataStore: InstallMetadataStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val appVersion: String = "",
    private val staleAfterMillis: Long = STALE_AFTER_MILLIS,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) {

    @Volatile
    private var scanning = false

    /** True while a scan is running, so callers can refuse to start another. */
    val isScanning: Boolean get() = scanning

    /** The remembered result, whether or not it is still fresh. */
    fun cached(): InstallMetadata? = metadataStore.load()

    /** The remembered result as a report, or null when nothing is remembered. */
    fun cachedReport(): InstallScanReport? {
        val meta = metadataStore.load() ?: return null
        return InstallScanReport(
            install = meta.install(),
            entriesExamined = meta.entriesExamined,
            foldersExamined = meta.foldersExamined,
            scannedAtMillis = meta.verifiedAtMillis,
            source = ScanSource.CACHE,
        )
    }

    /**
     * Whether the remembered result is still good enough to trust.
     *
     * Never throws: if the search folders cannot even be listed right now, the
     * honest answer is "not fresh", and the scan that follows will report why.
     */
    fun isCachedFresh(): Boolean = runCatching {
        val meta = metadataStore.load() ?: return false
        meta.isFresh(
            fingerprint = fingerprint(),
            appVersion = appVersion,
            now = clock(),
            staleAfterMillis = staleAfterMillis,
        )
    }.getOrDefault(false)

    /**
     * Makes sure the launcher has an install result.
     *
     * When a fresh cache exists, [onResult] is called straight away on the
     * calling thread and no work is done. Otherwise a scan runs in the
     * background; both callbacks are then invoked on that background thread, so
     * the caller is responsible for hopping back to the main thread.
     *
     * @param force re-scan even when the cached result is still fresh.
     * @return false when a scan was already running and this call did nothing.
     */
    fun ensureInstall(
        force: Boolean = false,
        onProgress: (ScanProgress) -> Unit,
        onResult: (InstallScanReport) -> Unit,
    ): Boolean {
        if (!force && isCachedFresh()) {
            val report = cachedReport()
            if (report != null && report.isWorthReusing()) {
                onResult(report)
                return true
            }
        }

        // Single flight: two scans walking the same SAF tree would double the
        // binder traffic and can only disagree with each other.
        if (scanning) return false
        scanning = true

        executor.execute {
            val report = try {
                // Even listing the search folders can fail (a revoked URI, a
                // removed card), so it happens inside the guarded block: the
                // caller gets a report with the reason instead of an exception.
                val snapshot = roots()
                val fingerprint = snapshot.joinToString(FINGERPRINT_SEPARATOR) {
                    it.location.reference
                }

                val scan = GameInstallScanner(snapshot).scanInstall(onProgress)

                val now = clock()
                metadataStore.save(
                    InstallMetadata.of(
                        install = scan.install,
                        entriesExamined = scan.entriesExamined,
                        foldersExamined = scan.foldersExamined,
                        verifiedAtMillis = now,
                        appVersion = appVersion,
                        fingerprint = fingerprint,
                    ),
                )

                InstallScanReport(
                    install = scan.install,
                    entriesExamined = scan.entriesExamined,
                    foldersExamined = scan.foldersExamined,
                    scannedAtMillis = now,
                    source = ScanSource.FRESH,
                )
            } catch (error: Throwable) {
                // A revoked folder or an unreadable tree must not leave the
                // launcher spinning: report a failed scan with the real reason.
                InstallScanReport(
                    install = null,
                    entriesExamined = 0,
                    foldersExamined = 0,
                    scannedAtMillis = clock(),
                    source = ScanSource.FRESH,
                    error = error,
                )
            } finally {
                scanning = false
            }

            onProgress(
                ScanStage.FINISHED.progress(report.entriesExamined, report.foldersExamined),
            )
            onResult(report)
        }
        return true
    }

    /** Forgets the remembered result, so the next check scans again. */
    fun clearCache() = metadataStore.clear()

    /** Identifies the current set of search folders, to detect a changed pick. */
    private fun fingerprint(): String =
        roots().joinToString(FINGERPRINT_SEPARATOR) { it.location.reference }

    /**
     * A remembered result is reused when it holds an install, or when proving
     * that there was none was expensive.
     *
     * The second half matters: a scan that walked a large folder and found
     * nothing must not be repeated on every start, because that is the slow
     * reopen this class was written to fix. A cheap scan over near-empty app
     * storage, on the other hand, costs so little that it is worth repeating in
     * case the user has since copied their files in.
     */
    private fun InstallScanReport.isWorthReusing(): Boolean =
        install != null || entriesExamined > CHEAP_SCAN_ENTRIES

    companion object {
        /** A remembered result younger than this is reused instead of rescanned. */
        const val STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L

        /** A scan that examined no more than this counts as cheap to repeat. */
        const val CHEAP_SCAN_ENTRIES = 2_000

        const val FINGERPRINT_SEPARATOR = "||"

        private fun ScanStage.progress(entries: Int, folders: Int) =
            ScanProgress(this, entries, folders)
    }
}
