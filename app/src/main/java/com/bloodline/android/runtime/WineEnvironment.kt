package com.bloodline.android.runtime

/**
 * The Wine prefix: where it lives, what shape it is, and which environment the
 * game process sees.
 *
 * Two rules decided this file's shape:
 *
 * 1. **Runtime-generated files never touch the user's game folder.** The prefix,
 *    its registry and every file Wine writes go under a directory the app owns.
 *    The game directory is read as it is; if the runtime wrote into it, a failed
 *    experiment could damage an installation the user cannot re-download.
 * 2. **The environment is built once and printed.** Every variable is produced
 *    by [environment] with a comment saying why, because "what environment was
 *    this launched with?" is one of the first questions a bug report asks — and
 *    because an unexplained variable is how cargo-culted settings spread.
 *
 * Nothing here starts a process or touches the filesystem on construction: it is
 * a value, so it can be built and checked in a unit test.
 */
data class WineEnvironment(

    /** Directory the app owns, where the prefix and every runtime file live. */
    val runtimeRoot: String,

    /** The Windows architecture the prefix is created for. */
    val architecture: WineArchitecture,

    /** Path to the Wine loader inside the APK's library directory. */
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

    /** Where Wine and the container put temporary files. */
    val tempDirectory: String get() = "$runtimeRoot/tmp"

    /** Where the runtime writes its own logs. */
    val logDirectory: String get() = "$runtimeRoot/logs"

    /** The path Wine sees for the game, e.g. `D:\Vampire.exe`. */
    val windowsExecutablePath: String get() = "$gameDrive:\\$executableName"

    /**
     * Every variable the game process is started with.
     *
     * `PATH` and the dynamic loader variables are *not* set here: they belong to
     * the container, which does not exist yet. Adding them now would be a guess
     * that a later milestone would have to quietly correct.
     */
    fun environment(): Map<String, String> = linkedMapOf(
        // Which prefix, and what shape it is. A 32-bit game needs WINEARCH=win32
        // or the prefix is created for the wrong architecture and the executable
        // is rejected before Wine ever looks at its bytes.
        "WINEPREFIX" to prefixDirectory,
        "WINEARCH" to architecture.wineArch,

        // Wine's own chatter is enormous and it all goes through the same log
        // path as our diagnostics. Off by default; -all means "no debug at all".
        "WINEDEBUG" to "-all",

        // Keep Wine's temporary files inside the directory we own rather than
        // letting them scatter into /tmp, which Android may not even permit.
        "TMPDIR" to tempDirectory,
        "TEMP" to tempDirectory,
        "TMP" to tempDirectory,

        // Wine has no window manager to talk to and no reason to try: the
        // surface is provided by the runtime, and a Wine driver that goes
        // looking for an X server just fails slowly.
        "DISPLAY" to "",

        // The directory the game is read from, so anything that resolves a
        // relative path starts in the right place.
        "WINE_GAME_DIRECTORY" to gameDirectory,
    )

    /** The command that would start the game, once every component exists. */
    fun launchCommand(): List<String> = listOf(
        wineLoader,
        windowsExecutablePath,
    )

    companion object {
        const val DEFAULT_GAME_DRIVE = 'D'
        const val DEFAULT_EXECUTABLE = "Vampire.exe"

        /** Everything the runtime owns, relative to [runtimeRoot]. */
        val ownedDirectories: List<String> get() = listOf("prefix", "tmp", "logs")
    }
}

/**
 * The shape of the Windows environment inside the prefix.
 *
 * [WIN32] is not a preference here: `Vampire.exe` is a 32-bit binary, so the
 * prefix has to be a 32-bit prefix regardless of what the phone's CPU is.
 */
enum class WineArchitecture(val wineArch: String) {
    WIN32("win32"),
    WIN64("win64"),
    ;

    companion object {
        /** Vampire.exe is 32-bit, so this is the only correct answer for it. */
        fun forExecutable(bitness: Int): WineArchitecture? = when (bitness) {
            32 -> WIN32
            64 -> WIN64
            else -> null
        }
    }
}

/**
 * Builds a [WineEnvironment] for a validated installation.
 *
 * Split out from the data class so the file system root can be supplied by the
 * caller (an Activity, or a temporary folder in a test) without the environment
 * knowing about Android.
 */
object WineEnvironmentFactory {

    fun create(
        runtimeRoot: String,
        gameDirectory: String,
        wineLoader: String,
        executableName: String = WineEnvironment.DEFAULT_EXECUTABLE,
        architecture: WineArchitecture = WineArchitecture.WIN32,
    ): WineEnvironment = WineEnvironment(
        runtimeRoot = runtimeRoot,
        architecture = architecture,
        wineLoader = wineLoader,
        gameDirectory = gameDirectory,
        executableName = executableName,
    )
}
