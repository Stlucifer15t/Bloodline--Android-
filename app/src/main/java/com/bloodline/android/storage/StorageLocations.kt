package com.bloodline.android.storage

import android.content.Context
import java.io.File

/**
 * Where the launcher keeps, and looks for, the game files.
 *
 * Only app specific directories are used. They need no storage permission and
 * remain readable under Android 11+ scoped storage, which makes the launcher
 * behave the same on every supported device.
 */
object StorageLocations {

    /** Folder inside app specific storage that holds the game. */
    const val GAME_FOLDER_NAME = "Bloodlines"

    /**
     * Import destination: `Android/data/<package>/files/Bloodlines`.
     * The importer (next milestone) copies the user's own game here.
     */
    fun importTarget(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, GAME_FOLDER_NAME)
    }

    /**
     * Roots inspected by [com.bloodline.android.game.GameInstallScanner],
     * in priority order.
     */
    fun searchRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()

        // App specific external storage, including SD cards when present.
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .forEach { dir ->
                roots += dir
                roots += File(dir, GAME_FOLDER_NAME)
            }

        // Internal storage fallback.
        roots += context.filesDir
        roots += File(context.filesDir, GAME_FOLDER_NAME)

        return roots.toList()
    }
}
