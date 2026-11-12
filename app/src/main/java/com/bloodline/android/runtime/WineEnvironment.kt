package com.bloodline.android.runtime

/**
 * The Wine prefix: where it lives, what shape it is, and which environment the
 * game process sees.
 *
 * The shape changed after the Milestone 3 investigation, and the change is the
 * important thing in this file:
 *
 * **The prefix is 64-bit, and the game's 32-bit code runs through WoW64.**
 *
 * The original version of this class hard-coded `WINEARCH=win32` on the
 * reasoning that `Vampire.exe` is a 32-bit binary, so the prefix must be 32-bit.
 * That reasoning is wrong for the way the working Android runtimes do it.
 * They create a 64-bit prefix and let WoW64 host the 32-bit application inside
 * it — which is what WOWBox64 and FEX's `libwow64fex.dll` exist to provide.
 * No 32-bit prefix, no Box86, no 32-bit userland.
 *
 * Two rules this file keeps regardless:
 *
 * 1. **Runtime-generated files never touch the user's game folder.** The prefix,
 *    its registry and every file Wine writes go under a directory the app owns.
 * 2. **Every environment variable says why it is set.** An unexplained variable
 *    is how cargo-culted settings spread.
 */
data class WineEnvironment(

    /** Directory the app owns, where the prefix and every runtime file live. */
    val runtimeRoot: String,

    /** How the Windows environment is built. See [WineFlavour]. */
    val flavour: WineFlavour = WineFlavour.WOW64_BOX64,

    /** Path to the Wine loader, e.g. `<runtimeRoot>/wine/bin/wine`. */
    val wineLoader: String,

    /**
     * Where the user's game files are, exposed to the prefix. Always the
     * original location — never a copy.
     */
    val gameDirectory: String,

    /** Drive letter the game directory is mounted on inside the prefix. */
    val gameDrive: Char = DEFAULT_GAME_DRIVE,

    /** Executable to start, relative to [gameDirectory]. */
    val executableName: String = DEFAULT_EXECUTABLE,
) {

    /** `WINEPREFIX`: the directory Wine keeps its virtual Windows in. */
    val prefixDirectory: String get() = "$runtimeRoot/prefix"

    /** Where Wine and the runtime put temporary files. */
    val tempDirectory: String get() = "$runtimeRoot/tmp"

    /** Where the runtime writes its own logs. */
    val logDirectory: String get() = "$runtimeRoot/logs"

    /** The path Wine sees for the game, e.g. `D:\Vampire.exe`. */
    val windowsExecutablePath: String get() = "$gameDrive:\\$executableName"

    /**
     * `WINEARCH`, taken from the flavour rather than assumed.
     *
     * For every flavour we support this is `win64`. It is derived, not
     * hard-coded, so that a flavour which genuinely needs `win32` can say so.
     */
    val wineArch: String get() = flavour.wineArch

    /**
     * Every variable the game process is started with.
     *
     * Modelled on what the working runtimes actually set, with the reason for
     * each. The dynamic loader paths are set by [withRuntimePaths] rather than
     * here, because they depend on where the packages were unpacked.
     */
    fun environment(): Map<String, String> = linkedMapOf(
        // Which prefix, and what shape it is. win64 + WoW64 is how a 32-bit
        // game is actually run: the prefix is 64-bit and WoW64 hosts the game.
        "WINEPREFIX" to prefixDirectory,
        "WINEARCH" to wineArch,

        // Which WoW64 implementation Wine should load for 32-bit guests.
        // Without this, a 32-bit executable has nothing to run against.
        "HODLL" to flavour.wow64DllName,

        // Wine's own chatter is enormous and it all goes through the same log
        // path as our diagnostics. Off by default; -all means "no debug at all".
        "WINEDEBUG" to "-all",

        // Keep Wine's temporary files inside the directory we own rather than
        // letting them scatter into /tmp, which Android may not even permit.
        "TMPDIR" to tempDirectory,
        "TEMP" to tempDirectory,
        "TMP" to tempDirectory,

        // The directory the game is read from, so anything that resolves a
        // relative path starts in the right place.
        "WINE_GAME_DIRECTORY" to gameDirectory,

        // Wine must not try to talk to an X server: the surface is provided by
        // the runtime's own display component.
        "WINE_DISABLE_FULLSCREEN_HACK" to "1",
    )

    /**
     * Adds the paths that depend on where packages were unpacked.
     *
     * @param wineBin    the Wine build's `bin` directory.
     * @param libDirs    library directories, in search order.
     */
    fun withRuntimePaths(
        wineBin: String,
        libDirs: List<String>,
    ): Map<String, String> = environment() + linkedMapOf(
        "PATH" to (listOf(wineBin) + libDirs.map { "$it/bin" }).joinToString(":"),
        "LD_LIBRARY_PATH" to libDirs.joinToString(":"),
    )

    /** The command that starts the game. */
    fun launchCommand(emulatorBinary: String?): List<String> = when {
        flavour.needsEmulator && emulatorBinary != null ->
            listOf(emulatorBinary, wineLoader, windowsExecutablePath)
        else -> listOf(wineLoader, windowsExecutablePath)
    }

    companion object {
        const val DEFAULT_GAME_DRIVE = 'D'
        const val DEFAULT_EXECUTABLE = "Vampire.exe"

        /** Everything the runtime owns, relative to [runtimeRoot]. */
        val ownedDirectories: List<String> get() = listOf("prefix", "tmp", "logs")
    }
}

/**
 * How the Windows environment is constructed.
 *
 * [WOW64_BOX64] is the default: an x86_64 Wine with Box64 doing the instruction
 * translation and WOWBox64 providing the WoW64 layer for 32-bit guests. That is
 * the combination the Mali-focused runtimes ship, and it is the one this project
 * targets.
 *
 * [WOW64_FEX] swaps the translator for FEX-Emu. [WIN32_BOX86] is the route this
 * project originally assumed — a 32-bit prefix with Box86 — and is kept only
 * because it remains a valid way to run 32-bit code; no package in the catalog
 * provides it, so [isAvailable] is false until someone supplies one.
 */
enum class WineFlavour(
    val wineArch: String,
    val wow64DllName: String,
    val needsEmulator: Boolean,
    val title: String,
    val description: String,
) {
    WOW64_BOX64(
        wineArch = "win64",
        wow64DllName = "wowbox64.dll",
        needsEmulator = true,
        title = "Wine x86_64 + WOWBox64",
        description = "A 64-bit Wine prefix with Box64 translating instructions and " +
            "WOWBox64 hosting the game's 32-bit Windows DLLs. No 32-bit userland " +
            "is needed. This is the combination the Mali-focused runtimes ship.",
    ),

    WOW64_FEX(
        wineArch = "win64",
        wow64DllName = "libwow64fex.dll",
        needsEmulator = false,
        title = "Wine + FEX-Emu (WoW64)",
        description = "A 64-bit Wine prefix with FEX-Emu providing both the instruction " +
            "translation and the WoW64 layer. No separate emulator binary is needed.",
    ),

    WIN32_BOX86(
        wineArch = "win32",
        wow64DllName = "",
        needsEmulator = true,
        title = "Wine 32-bit + Box86 (legacy)",
        description = "A 32-bit Wine prefix with Box86 translating instructions. Box86 " +
            "is itself a 32-bit program, so this route needs a 32-bit userland that " +
            "Android devices increasingly do not provide. Kept for completeness; no " +
            "package in the catalog supplies it.",
    ),
    ;

    /** False for flavours with no package to back them. */
    val isAvailable: Boolean get() = this != WIN32_BOX86

    companion object {
        /** The flavour to use when the user has not chosen one. */
        val DEFAULT: WineFlavour get() = WOW64_BOX64
    }
}
