package com.bloodline.android.game

import java.io.InputStream

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
 * @param read        opens this entry for reading, or null when the backing
 *                    store cannot produce a stream. Reading is the only access
 *                    the launcher needs: nothing here can write, so no scan or
 *                    validation can damage the user's game files.
 * @param open        opens this entry as a folder. Last on purpose: callers
 *                    pass it as a trailing lambda, and inserting anything after
 *                    it would silently rebind those lambdas.
 */
class StorageEntry(
    val name: String,
    val isDirectory: Boolean,
    private val read: () -> InputStream? = { null },
    private val open: () -> StorageFolder = {
        throw UnsupportedOperationException("$name is not a folder")
    },
) {
    /** Opens this entry as a folder. Only valid when [isDirectory] is true. */
    fun folder(): StorageFolder = open()

    /**
     * Opens this entry for reading.
     *
     * Null means the file could not be opened, which the caller reports to the
     * user rather than treating as an empty file — an unreadable executable and
     * a zero-byte one are different problems.
     */
    fun stream(): InputStream? = read()
}
