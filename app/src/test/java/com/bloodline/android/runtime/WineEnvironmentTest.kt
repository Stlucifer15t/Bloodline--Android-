package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Wine prefix: where it lives, what shape it is, and which
 * environment the game process sees.
 *
 * Two rules are pinned down here. The first is unchanged from the original
 * version of this file:
 *
 * 1. **Everything the runtime writes lives under a directory the app owns**,
 *    never beside the game files. A prefix written into the user's game folder
 *    would be a mess they did not ask for and cannot easily undo.
 *
 * The second rule replaced the first version's, and the replacement is the
 * whole reason this file was rewritten:
 *
 * 2. **The prefix is 64-bit, and the game's 32-bit code runs through WoW64.**
 *    The original asserted `WINEARCH=win32` on the reasoning that
 *    `Vampire.exe` is a 32-bit binary, so the prefix must be 32-bit. That is
 *    not how the runtimes that work do it: they build a 64-bit prefix and let
 *    WoW64 host the 32-bit application inside it. The test now asserts that the
 *    32-bit guest is hosted, rather than that the prefix is 32-bit.
 */
class WineEnvironmentTest {

    private fun environment(
        executableName: String = "Vampire.exe",
        flavour: WineFlavour = WineFlavour.WOW64_BOX64,
    ) = WineEnvironment(
        runtimeRoot = "/data/data/com.bloodline.android/runtime",
        flavour = flavour,
        wineLoader = "/data/data/com.bloodline.android/runtime/wine/bin/wine",
        gameDirectory = "/storage/game",
        executableName = executableName,
    )

    @Test
    fun `the prefix lives under the directory the app owns`() {
        val environment = environment()

        assertTrue(environment.prefixDirectory.startsWith("/data/data/com.bloodline.android/runtime"))
        assertTrue(environment.tempDirectory.startsWith("/data/data/com.bloodline.android/runtime"))
        assertTrue(environment.logDirectory.startsWith("/data/data/com.bloodline.android/runtime"))
    }

    @Test
    fun `the game directory is never inside the runtime directory`() {
        val environment = environment()

        assertEquals("/storage/game", environment.gameDirectory)
        assertTrue(!environment.prefixDirectory.contains("storage/game"))
    }

    // ------------------------------------------------------- the 32-bit guest

    @Test
    fun `the prefix is 64-bit, because the game is hosted through WoW64`() {
        // The correction. A 32-bit executable does not require a 32-bit prefix:
        // WoW64 exists precisely so a 64-bit prefix can host one.
        assertEquals("win64", environment().environment()["WINEARCH"])
    }

    @Test
    fun `Wine is told which WoW64 layer to load`() {
        // Without HODLL there is nothing to run the game's 32-bit code against.
        assertEquals("wowbox64.dll", environment().environment()["HODLL"])
    }

    @Test
    fun `each flavour names the WoW64 layer it uses`() {
        assertEquals("wowbox64.dll", WineFlavour.WOW64_BOX64.wow64DllName)
        assertEquals("libwow64fex.dll", WineFlavour.WOW64_FEX.wow64DllName)
        assertEquals("", WineFlavour.X86_64_BOX64.wow64DllName)
    }

    @Test
    fun `every available flavour hosts the game in a 64-bit prefix`() {
        // No available route builds a 32-bit prefix. That is the correction:
        // a 32-bit executable does not require a 32-bit prefix.
        WineFlavour.available().forEach { flavour ->
            assertEquals("${flavour.name} builds a 32-bit prefix.", "win64", flavour.wineArch)
        }
    }

    @Test
    fun `FEX runs without a separate emulator binary`() {
        // FEX supplies both the translation and the WoW64 layer, so the launch
        // command must not try to chain an emulator in front of Wine.
        val environment = environment(flavour = WineFlavour.WOW64_FEX)

        assertEquals(false, environment.flavour.needsEmulator)
        assertEquals(
            listOf("/data/data/com.bloodline.android/runtime/wine/bin/wine", "D:\\Vampire.exe"),
            environment.launchCommand(emulatorBinary = null),
        )
    }

    @Test
    fun `an ARM64EC Wine needs no emulator in front of it`() {
        // The whole reason ARM64EC was chosen: Wine's own code is ARM64 and
        // runs natively. Only the 32-bit guest is translated, by the WoW64
        // layer named in HODLL.
        val environment = environment()

        assertEquals(false, environment.flavour.needsEmulator)
        assertEquals(
            listOf("/data/data/com.bloodline.android/runtime/wine/bin/wine", "D:\\Vampire.exe"),
            environment.launchCommand(
                emulatorBinary = "/data/data/com.bloodline.android/runtime/box64/box64",
            ),
        )
    }

    @Test
    fun `an x86_64 Wine is run through Box64, and sets no HODLL`() {
        // With an x86_64 Wine the wine binary itself is x86_64, so Box64 goes
        // in front. HODLL would be meaningless here: Box64 is already
        // emulating the entire process, 32-bit guests included.
        val environment = environment(flavour = WineFlavour.X86_64_BOX64)

        assertTrue(environment.flavour.needsEmulator)
        assertEquals("win64", environment.environment()["WINEARCH"])
        assertEquals(null, environment.environment()["HODLL"])
        assertEquals(
            listOf(
                "/data/data/com.bloodline.android/runtime/box64/box64",
                "/data/data/com.bloodline.android/runtime/wine/bin/wine",
                "D:\\Vampire.exe",
            ),
            environment.launchCommand(
                emulatorBinary = "/data/data/com.bloodline.android/runtime/box64/box64",
            ),
        )
    }

    @Test
    fun `only the flavours that use a WoW64 layer set HODLL`() {
        WineFlavour.entries.forEach { flavour ->
            val variables = environment(flavour = flavour).environment()
            assertEquals(
                "${flavour.name} should set HODLL exactly when it uses a WoW64 layer.",
                flavour.usesWow64Layer,
                variables.containsKey("HODLL"),
            )
        }
    }

    @Test
    fun `a WoW64 flavour is the default`() {
        assertEquals(WineFlavour.WOW64_BOX64, WineFlavour.DEFAULT)
        assertTrue(WineFlavour.DEFAULT.isAvailable)
    }

    @Test
    fun `the legacy 32-bit prefix flavour is not offered until a package exists`() {
        // Box86 remains a valid way to run 32-bit code, but nothing in the
        // catalog supplies it, so it must not be presented as an option.
        assertEquals("win32", WineFlavour.WIN32_BOX86.wineArch)
        assertTrue(!WineFlavour.WIN32_BOX86.isAvailable)
    }

    // ------------------------------------------------------------- environment

    @Test
    fun `the executable is addressed the way Windows addresses it`() {
        assertEquals("D:\\Vampire.exe", environment().windowsExecutablePath)
    }

    @Test
    fun `Wine is not muted, because a crash is exactly when it has to speak`() {
        // The old value was -all. That was fine while everything worked and
        // useless the moment it did not: a 32-bit program under Wine on a real
        // device came back SIGSEGV, and the launcher had already told Wine not
        // to print anything, then asked the user what Wine said.
        // Tokens, not substrings: "fixme-all" is a channel being switched off
        // and it contains the characters of "-all" without meaning it.
        val channels = environment().environment()["WINEDEBUG"]
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        assertFalse(channels.toString(), channels.contains("-all"))
        // Errors and the exception that reports a crash have to be on.
        assertTrue(channels.toString(), channels.contains("err+all"))
        assertTrue(channels.toString(), channels.contains("trace+seh"))
        // Whether the WoW64 layer was found is the question behind most of
        // the 32-bit failures, so DLL loading is on too.
        assertTrue(channels.toString(), channels.contains("trace+loaddll"))
    }

    @Test
    fun `Wine debug channels can be set when something needs a closer look`() {
        val environment = environment().copy(wineDebug = "trace+all")

        assertEquals("trace+all", environment.environment()["WINEDEBUG"])
    }

    @Test
    fun `temporary files are kept inside the app's own directory`() {
        val variables = environment().environment()

        assertEquals(environment().tempDirectory, variables["TMPDIR"])
        assertEquals(environment().tempDirectory, variables["TEMP"])
        assertEquals(environment().tempDirectory, variables["TMP"])
    }

    @Test
    fun `the runtime paths are added on top of the fixed variables`() {
        // Library paths depend on where the packages were unpacked, so they are
        // set separately rather than baked into environment().
        val variables = environment().withRuntimePaths(
            wineBin = "/runtime/wine/bin",
            libDirs = listOf("/runtime/box64", "/runtime/wowbox64"),
        )

        assertEquals("/runtime/wine/bin:/runtime/box64/bin:/runtime/wowbox64/bin", variables["PATH"])
        assertEquals("/runtime/box64:/runtime/wowbox64", variables["LD_LIBRARY_PATH"])
        assertEquals("win64", variables["WINEARCH"])
        assertEquals("wowbox64.dll", variables["HODLL"])
    }

    @Test
    fun `the runtime does not claim to own any directory beside the game`() {
        val owned = WineEnvironment.ownedDirectories

        assertTrue(owned.contains("prefix"))
        assertTrue(!owned.contains("Vampire"))
    }
}
