package com.bloodline.android.game

import java.io.File

/**
 * Looks for a user supplied installation of the game on device storage.
 *
 * The scanner only ever reads; it never downloads, copies or modifies game
 * files. It is plain Kotlin backed by [File] so it can be unit tested on the
 * JVM with a temporary folder.
 *
 * @param searchRoots directories to inspect, in priority order.
 */
class GameInstallScanner(
    private val searchRoots: List<File>,
) {

    /**
     * The first complete installation found across all roots. A complete
     * installation always wins over a partial one, and an installation in an
     * earlier root wins over one found later.
     */
    fun findInstall(): GameInstall? {
        var partial: GameInstall? = null

        for (root in searchRoots) {
            for (candidate in scan(root)) {
                if (candidate.isComplete) return candidate
                if (partial == null) partial = candidate
            }
        }
        return partial
    }

    private fun scan(directory: File, depth: Int = 0): List<GameInstall> {
        if (depth > MAX_DEPTH) return emptyList()
        // Unreadable (or missing) folders are skipped rather than crashing:
        // on Android 11+ several legacy public paths are simply not readable.
        val children = directory.listFiles() ?: return emptyList()

        val found = ArrayList<GameInstall>()
        for (child in children) {
            if (child.name.startsWith(".")) continue

            if (child.isFile && child.name.equals(EXECUTABLE_NAME, ignoreCase = true)) {
                found += describe(child)
            } else if (child.isDirectory) {
                found += scan(child, depth + 1)
            }
        }
        return found
    }

    private fun describe(executable: File): GameInstall {
        val root = executable.parentFile ?: executable
        val hasDataDirectory = root.listFiles()?.any {
            it.isDirectory && it.name.equals(DATA_DIRECTORY_NAME, ignoreCase = true)
        } == true

        return GameInstall(
            executable = executable,
            rootDirectory = root,
            hasDataDirectory = hasDataDirectory,
        )
    }

    companion object {
        /** Retail / GOG / Steam name of the game executable. */
        const val EXECUTABLE_NAME = "Vampire.exe"

        /** Source engine data folder that must sit next to the executable. */
        const val DATA_DIRECTORY_NAME = "Vampire"

        /**
         * How deep to walk. Keeps the scan cheap and predictable even when a
         * root folder is huge; the importer will write to a known folder
         * anyway, so a deep search buys nothing.
         */
        private const val MAX_DEPTH = 2
    }
}
