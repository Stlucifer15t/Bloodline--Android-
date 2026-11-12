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
    fun environment(): Map<String, String> = linkedMapOf<String, String>().apply {
        // Which prefix, and what shape it is. win64 is how a 32-bit game is
        // actually run here: the prefix is 64-bit and WoW64 hosts the game.
        put("WINEPREFIX", prefixDirectory)
        put("WINEARCH", wineArch)

        // Which WoW64 implementation Wine should load for 32-bit guests. Only
        // set when the flavour uses one: an x86_64 Wine gets its 32-bit support
        // from Box64 translating the whole process, and setting HODLL there
        // would be cargo cult rather than configuration.
        if (flavour.usesWow64Layer) put("HODLL", flavour.wow64DllName)

        // The directory the game is read from, so anything that resolves a
        // relative path starts in the right place.
        put("WINE_GAME_DIRECTORY", gameDirectory)

        // Wine's own chatter is enormous and it all goes through the same log
        // path as our diagnostics. Off by default; -all means "no debug at all".
        put("WINEDEBUG", "-all")

        // Keep Wine's temporary files inside the directory we own rather than
        // letting them scatter into /tmp, which Android may not even permit.
        put("TMPDIR", tempDirectory)
        put("TEMP", tempDirectory)
        put("TMP", tempDirectory)

        // Wine must not try to manage the whole screen itself: the surface is
        // provided by the runtime's own display component.
        put("WINE_DISABLE_FULLSCREEN_HACK", "1")
    }

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
 * How the Windows environment is constructed, and therefore what the launch
 * command looks like.
 *
 * The distinction that matters — ARM64EC versus x86_64 Wine — comes straight
 * from reading the launcher of the Mali build that works. Its command
 * construction has exactly two branches:
 *
 * * **ARM64EC Wine**: the command is `wine <exe>`. Wine's own code is ARM64
 *   and runs natively; only the 32-bit guest needs an emulator, and that
 *   emulator is a WoW64 DLL Wine loads, named by `HODLL`.
 * * **x86_64 Wine**: the command is `box64 wine <exe>`, because the `wine`
 *   binary itself is x86_64 and has to be translated from the start. `HODLL`
 *   is not used — Box64 is already emulating the whole process, 32-bit guests
 *   included.
 *
 * This project targets the ARM64EC branch. It needs no 32-bit userland, it
 * puts one emulator between the game and the CPU instead of two, and it is the
 * branch the 32-bit WoW64 layer is actually built for.
 *
 * [WIN32_BOX86] is the route this project originally assumed — a 32-bit prefix
 * with Box86 — and is kept only because it remains a valid way to run 32-bit
 * code. No package in the catalogue provides it, so [isAvailable] is false.
 */
enum class WineFlavour(
    val wineArch: String,

    /**
     * The DLL Wine loads to host 32-bit guests, or empty when this flavour
     * does not use a WoW64 layer.
     *
     * Empty is a meaningful state rather than a default: [X86_64_BOX64] runs
     * no `HODLL` at all, and setting one there would be cargo cult.
     */
    val wow64DllName: String,

    /** True when the Wine binary itself needs translating. */
    val needsEmulator: Boolean,

    val title: String,
    val description: String,
) {
    WOW64_BOX64(
        wineArch = "win64",
        wow64DllName = "wowbox64.dll",
        needsEmulator = false,
        title = "Wine ARM64EC + WOWBox64",
        description = "An ARM64EC Wine with Box64 supplying the WoW64 layer for the " +
            "game's 32-bit code. Wine itself runs natively, so nothing needs to " +
            "translate it, and no 32-bit userland is involved. The default.",
    ),

    WOW64_FEX(
        wineArch = "win64",
        wow64DllName = "libwow64fex.dll",
        needsEmulator = false,
        title = "Wine ARM64EC + FEX-Emu",
        description = "An ARM64EC Wine with FEX-Emu supplying the WoW64 layer. The " +
            "alternative when WOWBox64 cannot host the game.",
    ),

    X86_64_BOX64(
        wineArch = "win64",
        wow64DllName = "",
        needsEmulator = true,
        title = "Wine x86_64 + Box64",
        description = "An x86_64 Wine with Box64 translating the whole Wine process. " +
            "The conventional arrangement, offered as an alternative because some " +
            "games behave differently under it. Sets no HODLL, because Box64 is " +
            "already emulating everything including the 32-bit guest.",
    ),

    WIN32_BOX86(
        wineArch = "win32",
        wow64DllName = "",
        needsEmulator = true,
        title = "Wine 32-bit + Box86 (legacy)",
        description = "A 32-bit Wine prefix with Box86 translating instructions. Box86 " +
            "is itself a 32-bit program, so this route needs a 32-bit userland that " +
            "Android devices increasingly do not provide. Kept for completeness; no " +
            "package in the catalogue supplies it.",
    ),
    ;

    /** True when Wine is told to load a WoW64 layer for 32-bit guests. */
    val usesWow64Layer: Boolean get() = wow64DllName.isNotEmpty()

    /** False for flavours with no package to back them. */
    val isAvailable: Boolean get() = this != WIN32_BOX86

    companion object {
        /** The flavour to use when the user has not chosen one. */
        val DEFAULT: WineFlavour get() = WOW64_BOX64

        /** The flavours worth offering, backed by a package. */
        fun available(): List<WineFlavour> = entries.filter { it.isAvailable }
    }
}
