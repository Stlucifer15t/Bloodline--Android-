package com.bloodline.android.game

/**
 * Looks for a user supplied installation of the game.
 *
 * The scanner only ever reads; it never downloads, copies or modifies game
 * files. It works against [StorageFolder], so the same logic covers app storage
 * and a folder picked through the Storage Access Framework, and it stays unit
 * testable on the JVM.
 *
 * Scanning is *not* free: over the Storage Access Framework every entry is a
 * binder call, and a Bloodlines install holds thousands of files. That is why
 * [scanInstall] can report [ScanProgress] as it goes, so the caller can show
 * what is genuinely happening instead of freezing.
 *
 * @param searchRoots folders to inspect, in priority order.
 */
class GameInstallScanner(
    private val searchRoots: List<StorageFolder>,
) {

    /** The first complete installation found across all roots. */
    fun findInstall(onProgress: ((ScanProgress) -> Unit)? = null): GameInstall? =
        scanInstall(onProgress).install

    /**
     * Walks the search roots and reports progress while doing it.
     *
     * @param onProgress called with real counts as the walk proceeds. It is
     *                   invoked on the calling thread, which must not be the
     *                   main thread for a large tree.
     */
    fun scanInstall(onProgress: ((ScanProgress) -> Unit)? = null): InstallScanResult {
        val counter = Counter(onProgress)

        var partial: GameInstall? = null

        for (root in searchRoots) {
            for (candidate in scan(root, depth = 0, counter)) {
                if (candidate.isComplete) {
                    return InstallScanResult(
                        install = candidate,
                        entriesExamined = counter.entries,
                        foldersExamined = counter.folders,
                    )
                }
                if (partial == null) partial = candidate
            }
        }

        return InstallScanResult(
            install = partial,
            entriesExamined = counter.entries,
            foldersExamined = counter.folders,
        )
    }

    private fun scan(
        folder: StorageFolder,
        depth: Int,
        counter: Counter,
    ): List<GameInstall> {
        if (depth > MAX_DEPTH) return emptyList()
        // Unreadable or revoked folders are skipped rather than crashing.
        val entries = runCatching { folder.entries() }.getOrNull() ?: return emptyList()

        counter.onFolderListed(entries.size)

        // Checked once per folder: the data folder has to sit next to the
        // executable, and listing a folder is not free (each entry of a
        // Storage Access Framework tree is a query).
        val hasDataDirectory = entries.any {
            it.isDirectory && it.name.equals(DATA_DIRECTORY_NAME, ignoreCase = true)
        }

        val found = ArrayList<GameInstall>()
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue

            if (!entry.isDirectory && entry.name.equals(EXECUTABLE_NAME, ignoreCase = true)) {
                // Vampire.exe is there: the next thing checked is whether the
                // data folder sits beside it, which decides if it can run.
                counter.onExecutableFound()
                found += GameInstall(
                    location = folder.location,
                    hasDataDirectory = hasDataDirectory,
                )
            } else if (entry.isDirectory) {
                found += scan(entry.folder(), depth + 1, counter)
            }
        }
        return found
    }

    /** The outcome of a scan plus the real amount of work it took. */
    data class InstallScanResult(
        val install: GameInstall?,
        val entriesExamined: Int,
        val foldersExamined: Int,
    )

    /** Keeps the running counts and emits a progress report after each folder. */
    private class Counter(private val onProgress: ((ScanProgress) -> Unit)?) {
        var entries: Int = 0
            private set
        var folders: Int = 0
            private set

        private var stage: ScanStage = ScanStage.SCANNING_FOLDERS

        fun onFolderListed(entryCount: Int) {
            entries += entryCount
            folders += 1
            emit()
        }

        fun onExecutableFound() {
            stage = ScanStage.CHECKING_REQUIRED_FILES
            emit()
            // The check itself is a single pass over the already listed
            // entries, so the walk goes straight back to scanning.
            stage = ScanStage.SCANNING_FOLDERS
        }

        private fun emit() {
            onProgress?.invoke(ScanProgress(stage, entries, folders))
        }
    }

    companion object {
        /** Retail / GOG / Steam name of the game executable. */
        const val EXECUTABLE_NAME = "Vampire.exe"

        /** Source engine data folder that must sit next to the executable. */
        const val DATA_DIRECTORY_NAME = "Vampire"

        /**
         * How deep to walk. Keeps the scan cheap and predictable even when a
         * root folder is huge.
         */
        private const val MAX_DEPTH = 2
    }
}
