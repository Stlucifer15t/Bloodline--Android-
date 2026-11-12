package com.bloodline.android.storage

import com.bloodline.android.game.GameLocation
import com.bloodline.android.game.LocationKind
import com.bloodline.android.game.StorageEntry
import com.bloodline.android.game.StorageFolder
import java.io.File

/** A [StorageFolder] backed by a directory the app owns. */
class FileSystemFolder(
    private val directory: File,
) : StorageFolder {

    override val location: GameLocation = GameLocation(
        kind = LocationKind.APP_STORAGE,
        reference = directory.absolutePath,
        displayPath = directory.absolutePath,
    )

    override fun entries(): List<StorageEntry> {
        // listFiles() returns null when the folder is missing or unreadable,
        // which is normal for some legacy public paths on Android 11+.
        val children = directory.listFiles() ?: return emptyList()
        return children.map { child ->
            StorageEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                open = { FileSystemFolder(child) },
                read = { runCatching { child.inputStream() }.getOrNull() },
            )
        }
    }
}
