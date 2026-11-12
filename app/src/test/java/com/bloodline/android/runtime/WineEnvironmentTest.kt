package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Wine prefix layout.
 *
 * Two rules are being pinned down here, and both are about protecting the
 * user's game folder:
 *
 * 1. Everything the runtime writes lives under a directory the app owns, never
 *    beside the game files.
 * 2. The prefix is created for the architecture the *game* is, not the
 *    architecture the phone is. `Vampire.exe` is 32-bit, so the prefix has to be
 *    a 32-bit prefix even on a 64-bit-only device.
 */
class WineEnvironmentTest {

    private fun environment(
        executableName: String = "Vampire.exe",
        architecture: WineArchitecture = WineArchitecture.WIN32,
    ) = WineEnvironmentFactory.create(
        runtimeRoot = "/data/data/com.bloodline.android/runtime",
        gameDirectory = "/storage/game",
        wineLoader = "/data/app/lib/arm64/libwine.so",
        executableName = executableName,
        architecture = architecture,
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

    @Test
    fun `a 32-bit executable gets a 32-bit prefix`() {
        // The game is 32-bit whatever the phone is.
        val environment = environment()

        assertEquals("win32", environment.environment()["WINEARCH"])
    }

    @Test
    fun `the architecture follows the executable, not the device`() {
        assertEquals(WineArchitecture.WIN32, WineArchitecture.forExecutable(32))
        assertEquals(WineArchitecture.WIN64, WineArchitecture.forExecutable(64))
        assertEquals(null, WineArchitecture.forExecutable(16))
    }

    @Test
    fun `the executable is addressed the way Windows addresses it`() {
        assertEquals("D:\\Vampire.exe", environment().windowsExecutablePath)
    }

    @Test
    fun `Wine debug output is off by default`() {
        // Wine is extremely chatty and it all lands in the same log.
        assertEquals("-all", environment().environment()["WINEDEBUG"])
    }

    @Test
    fun `temporary files are kept inside the app's own directory`() {
        val variables = environment().environment()

        assertEquals(environment().tempDirectory, variables["TMPDIR"])
        assertEquals(environment().tempDirectory, variables["TEMP"])
        assertEquals(environment().tempDirectory, variables["TMP"])
    }

    @Test
    fun `the launch command runs the loader with the Windows path`() {
        val environment = environment()

        assertEquals(
            listOf("/data/app/lib/arm64/libwine.so", "D:\\Vampire.exe"),
            environment.launchCommand(),
        )
    }

    @Test
    fun `the runtime does not claim to own any directory beside the game`() {
        val owned = WineEnvironment.ownedDirectories

        assertTrue(owned.contains("prefix"))
        assertTrue(!owned.contains("Vampire"))
    }
}
