package com.bloodline.android.storage

import android.content.Context
import com.bloodline.android.game.StorageFolder
import java.io.File

/**
 * Where the launcher keeps, and looks for, the game files.
 *
 * Two kinds of location are supported:
 *
 * 1. **App specific storage** — needs no permission and works under Android 11+
 *    scoped storage. Users can copy the game here with any file manager.
 * 2. **A folder the user picks** — reached through the Storage Access Framework
 *    and read in place, so no gigabytes are copied.
 */
object StorageLocations {

    /** Folder inside app specific storage that holds the game. */
    const val GAME_FOLDER_NAME = "Bloodlines"

    /**
     * Where the game belongs if the user copies it in manually:
     * `Android/data/<package>/files/Bloodlines`.
     */
    fun importTarget(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, GAME_FOLDER_NAME)
    }

    /**
     * Roots inspected by [com.bloodline.android.game.GameInstallScanner],
     * in priority order: the folder the user picked comes first, so a deliberate
     * choice always beats whatever happens to sit in app storage.
     */
    fun searchRoots(context: Context, gameFolderStore: GameFolderStore): List<StorageFolder> {
        val roots = LinkedHashMap<String, StorageFolder>()

        // 1. The folder the user selected, if we can still read it.
        gameFolderStore.load()?.let { uri ->
            runCatching { DocumentFolder.fromTree(context, uri) }
                .onSuccess { folder -> roots[folder.location.reference] = folder }
        }

        // 2. App specific external storage, including SD cards when present.
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .forEach { dir ->
                addFileRoot(roots, dir)
                addFileRoot(roots, File(dir, GAME_FOLDER_NAME))
            }

        // 3. Internal storage fallback.
        addFileRoot(roots, context.filesDir)
        addFileRoot(roots, File(context.filesDir, GAME_FOLDER_NAME))

        return roots.values.toList()
    }

    private fun addFileRoot(roots: MutableMap<String, StorageFolder>, directory: File) {
        val folder = FileSystemFolder(directory)
        roots.putIfAbsent(folder.location.reference, folder)
    }
}
