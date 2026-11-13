package com.bloodline.android.port

/**
 * Where a *native* Bloodlines port keeps its files on a device.
 *
 * This is a different thing from the Wine launcher in `runtime/`. The port
 * everyone actually plays on Mali is not Wine at all: it is the original
 * engine recompiled to ARM64, with its own D3D9 backend. That makes it the
 * only runtime that is not blocked by Android's W^X refusal to map PE images
 * (see `docs/DEVICE_REPORT_2_2026-11-13.md`), so tuning *it* is where a
 * difference can actually be felt.
 *
 * The port owns its data directory. Its own app id is `com.vtmb.lift`, and the
 * game folder is wherever the user put it — commonly `Download/<name>/`. So
 * nothing here assumes a fixed path: the caller supplies the game root, and
 * every known file is derived from it.
 */
data class PortLayout(
    /** Folder that holds `Vampire.exe`, `Vampire/`, `Bin/`, … */
    val gameRoot: String,
) {

    /**
     * The port's live settings.
     *
     * `overlay/` is where the port writes the files it lets you tune. They are
     * plain `key=value` text, which is why a companion app can read and write
     * them at all — the game itself is a stripped binary.
     */
    fun settingsFile(): String = "$gameRoot/overlay/port_settings.txt"

    /** The port's own backup of the previous settings, written by this app. */
    fun settingsBackup(): String = "$gameRoot/overlay/port_settings.txt.bak"

    /** Registry values the port feeds the engine: resolution, windowed mode. */
    fun registryFile(): String = "$gameRoot/overlay/registry.txt"

    /** Image-quality switch the port reads on start (`1` = high). */
    fun imageQualityFile(): String = "$gameRoot/overlay/iq_high.txt"

    /** Language selected in the port. */
    fun languageFile(): String = "$gameRoot/overlay/language.txt"

    /** DXVK's own configuration, if the Vulkan renderer is in use. */
    fun dxvkConfig(): String = "$gameRoot/Bin/dxvk.conf"

    /** The game's own folder, which the patch also lives beside. */
    fun gameDataDirectory(): String = "$gameRoot/Vampire"

    /** Wesp5's Unofficial Patch, a Source mod folder loaded with `-game`. */
    fun patchDirectory(): String = "$gameRoot/Unofficial_Patch"

    /** The port's mod folder: loose files dropped here override the game's. */
    fun modsDirectory(): String = "$gameRoot/lift_mods"

    /** The recompiled engine, as the port ships it. */
    fun engineBinary(): String = "$gameRoot/Bin/engine.dll"

    companion object {

        /**
         * Names an install can plausibly have, so a first-run scan has
         * somewhere to look before the user points at a folder.
         */
        val COMMON_FOLDER_NAMES = listOf(
            "VTMB-apkvision-English",
            "VTMB-apkvision",
            "VTMB",
            "Bloodlines",
            "Vampire Bloodlines",
            "Vampire - The Masquerade - Bloodlines",
        )
    }
}
