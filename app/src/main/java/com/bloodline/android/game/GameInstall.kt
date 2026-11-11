package com.bloodline.android.game

import java.io.File

/**
 * A Vampire: The Masquerade – Bloodlines installation found on the device.
 *
 * @param executable       the Vampire.exe that would be handed to Wine.
 * @param rootDirectory    the folder that contains the executable.
 * @param hasDataDirectory true when the "Vampire" data folder sits next to the
 *                         executable; without it the game cannot start.
 */
data class GameInstall(
    val executable: File,
    val rootDirectory: File,
    val hasDataDirectory: Boolean,
) {
    /** The game only stands a chance of starting when its data is present. */
    val isComplete: Boolean get() = hasDataDirectory
}
