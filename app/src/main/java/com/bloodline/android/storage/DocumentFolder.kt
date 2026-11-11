package com.bloodline.android.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bloodline.android.game.GameLocation
import com.bloodline.android.game.LocationKind
import com.bloodline.android.game.StorageEntry
import com.bloodline.android.game.StorageFolder

/**
 * A [StorageFolder] backed by the Storage Access Framework.
 *
 * This is how the launcher reaches a game folder the user picked: Android
 * grants read access to that tree, and we read the files where they are instead
 * of copying several gigabytes into app storage.
 */
class DocumentFolder private constructor(
    private val document: DocumentFile,
    private val displayPath: String,
) : StorageFolder {

    override val location: GameLocation = GameLocation(
        kind = LocationKind.EXTERNAL_FOLDER,
        reference = document.uri.toString(),
        displayPath = displayPath,
    )

    override fun entries(): List<StorageEntry> {
        // Access can be revoked at any time; treat that as "nothing here"
        // instead of crashing the launcher.
        val children = runCatching { document.listFiles() }.getOrNull() ?: return emptyList()

        return children.mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            StorageEntry(name = name, isDirectory = child.isDirectory) {
                DocumentFolder(child, "$displayPath/$name")
            }
        }
    }

    companion object {
        /** Opens the tree the user picked in the folder chooser. */
        fun fromTree(context: Context, treeUri: Uri): DocumentFolder {
            val document = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("That folder could not be opened: $treeUri")
            return DocumentFolder(document, document.name ?: "selected folder")
        }
    }
}
