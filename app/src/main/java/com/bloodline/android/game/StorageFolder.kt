package com.bloodline.android.game

/**
 * A read-only view over one folder, whatever backs it: a plain directory in app
 * storage, or a document tree handed to us by the Storage Access Framework.
 *
 * Keeping the scanner behind this interface means the detection logic is plain
 * Kotlin and can be unit tested without an Android device.
 */
interface StorageFolder {
    /** Where this folder lives. */
    val location: GameLocation

    /** Direct children of this folder. */
    fun entries(): List<StorageEntry>
}

/**
 * One child of a [StorageFolder].
 *
 * @param name        file or folder name.
 * @param isDirectory true when this entry can be opened as a folder.
 */
class StorageEntry(
    val name: String,
    val isDirectory: Boolean,
    private val open: () -> StorageFolder,
) {
    /** Opens this entry as a folder. Only valid when [isDirectory] is true. */
    fun folder(): StorageFolder = open()
}
