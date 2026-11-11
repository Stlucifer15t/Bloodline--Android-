package com.bloodline.android.game

/**
 * A Vampire: The Masquerade – Bloodlines installation found on the device.
 *
 * The launcher never copies, downloads or modifies the game: an install is just
 * a [location] plus what we could verify about it.
 *
 * @param location         folder that contains Vampire.exe.
 * @param hasDataDirectory true when the "Vampire" data folder sits next to the
 *                         executable; without it the game cannot start.
 */
data class GameInstall(
    val location: GameLocation,
    val hasDataDirectory: Boolean,
) {
    /** The game only stands a chance of starting when its data is present. */
    val isComplete: Boolean get() = hasDataDirectory
}
