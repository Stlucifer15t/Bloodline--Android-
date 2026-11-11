package com.bloodline.android.game

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for the game detection logic. No Android framework involved. */
class GameInstallScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun scanner(vararg roots: File) = GameInstallScanner(roots.toList())

    @Test
    fun `returns null when no game files exist`() {
        val root = temporaryFolder.newFolder("empty")

        assertNull(scanner(root).findInstall())
    }

    @Test
    fun `returns null when only unrelated executables exist`() {
        val root = temporaryFolder.newFolder("other-game")
        File(root, "HalfLife2.exe").createNewFile()

        assertNull(scanner(root).findInstall())
    }

    @Test
    fun `finds an installation in the search root`() {
        val root = temporaryFolder.newFolder("game")
        val executable = File(root, "Vampire.exe").apply { createNewFile() }
        File(root, "Vampire").mkdirs()

        val install = checkNotNull(scanner(root).findInstall())

        assertEquals(executable, install.executable)
        assertEquals(root, install.rootDirectory)
        assertTrue(install.hasDataDirectory)
        assertTrue(install.isComplete)
    }

    @Test
    fun `reports a partial install when the data folder is missing`() {
        val root = temporaryFolder.newFolder("partial")
        File(root, "Vampire.exe").createNewFile()

        val install = checkNotNull(scanner(root).findInstall())

        assertFalse(install.hasDataDirectory)
        assertFalse(install.isComplete)
    }

    @Test
    fun `matches the executable name case insensitively`() {
        val root = temporaryFolder.newFolder("case")
        File(root, "vampire.EXE").createNewFile()
        File(root, "vampire").mkdirs()

        val install = checkNotNull(scanner(root).findInstall())

        assertTrue(install.isComplete)
    }

    @Test
    fun `finds an installation nested inside sub folders`() {
        val root = temporaryFolder.newFolder("root")
        val nested = File(root, "Bloodlines").apply { mkdirs() }
        File(nested, "Vampire.exe").createNewFile()
        File(nested, "Vampire").mkdirs()

        val install = checkNotNull(scanner(root).findInstall())

        assertEquals(nested, install.rootDirectory)
    }

    @Test
    fun `stops scanning beyond the configured depth`() {
        val root = temporaryFolder.newFolder("deep")
        val tooDeep = File(root, "a/b/c").apply { mkdirs() }
        File(tooDeep, "Vampire.exe").createNewFile()

        assertNull(scanner(root).findInstall())
    }

    @Test
    fun `prefers a complete installation over a partial one`() {
        val partial = temporaryFolder.newFolder("partial").apply {
            File(this, "Vampire.exe").createNewFile()
        }
        val complete = temporaryFolder.newFolder("complete").apply {
            File(this, "Vampire.exe").createNewFile()
            File(this, "Vampire").mkdirs()
        }

        val install = checkNotNull(scanner(partial, complete).findInstall())

        assertEquals(complete, install.rootDirectory)
        assertTrue(install.isComplete)
    }

    @Test
    fun `searches roots in the order they are given`() {
        val first = temporaryFolder.newFolder("first").apply {
            File(this, "Vampire.exe").createNewFile()
        }
        val second = temporaryFolder.newFolder("second").apply {
            File(this, "Vampire.exe").createNewFile()
        }

        assertEquals(first, checkNotNull(scanner(first, second).findInstall()).rootDirectory)
        assertEquals(second, checkNotNull(scanner(second, first).findInstall()).rootDirectory)
    }

    @Test
    fun `ignores hidden folders`() {
        val root = temporaryFolder.newFolder("with-hidden")
        val hidden = File(root, ".cache").apply { mkdirs() }
        File(hidden, "Vampire.exe").createNewFile()

        assertNull(scanner(root).findInstall())
    }

    @Test
    fun `tolerates roots that do not exist`() {
        val missing = File(temporaryFolder.root, "does-not-exist")

        assertNull(scanner(missing).findInstall())
    }
}
