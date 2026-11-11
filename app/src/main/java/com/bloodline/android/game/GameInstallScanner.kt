package com.bloodline.android.game

/**
 * Looks for a user supplied installation of the game.
 *
 * The scanner only ever reads; it never downloads, copies or modifies game
 * files. It works against [StorageFolder], so the same logic covers app storage
 * and a folder picked through the Storage Access Framework, and it stays unit
 * testable on the JVM.
 *
 * @param searchRoots folders to inspect, in priority order.
 */
class GameInstallScanner(
    private val searchRoots: List<StorageFolder>,
) {

    /**
     * The first complete installation found across all roots. A complete
     * installation always wins over a partial one, and an installation in an
     * earlier root wins over one found later.
     */
    fun findInstall(): GameInstall? {
        var partial: GameInstall? = null

        for (root in searchRoots) {
            for (candidate in scan(root, depth = 0)) {
                if (candidate.isComplete) return candidate
                if (partial == null) partial = candidate
            }
        }
        return partial
    }

    private fun scan(folder: StorageFolder, depth: Int): List<GameInstall> {
        if (depth > MAX_DEPTH) return emptyList()
        // Unreadable or revoked folders are skipped rather than crashing.
        val entries = runCatching { folder.entries() }.getOrNull() ?: return emptyList()

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
                found += GameInstall(
                    location = folder.location,
                    hasDataDirectory = hasDataDirectory,
                )
            } else if (entry.isDirectory) {
                found += scan(entry.folder(), depth + 1)
            }
        }
        return found
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
