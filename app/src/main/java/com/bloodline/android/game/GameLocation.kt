package com.bloodline.android.game

/** Where the game files live. */
enum class LocationKind {
    /** App specific storage the launcher owns, reachable as a plain path. */
    APP_STORAGE,

    /** A folder the user picked through the Storage Access Framework. */
    EXTERNAL_FOLDER,
}

/**
 * Identifies the folder that holds the game, independent of how it is reached.
 *
 * @param kind        which backend the folder lives on.
 * @param reference   absolute path ([LocationKind.APP_STORAGE]) or the
 *                    `content://` document URI ([LocationKind.EXTERNAL_FOLDER])
 *                    of the folder that contains Vampire.exe. This is what a
 *                    future runtime will mount or hand to Wine.
 * @param displayPath short human readable location for the launcher UI.
 */
data class GameLocation(
    val kind: LocationKind,
    val reference: String,
    val displayPath: String,
)
