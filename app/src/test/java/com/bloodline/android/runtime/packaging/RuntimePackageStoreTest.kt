package com.bloodline.android.runtime.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for where runtime packages live and how we know one is installed.
 *
 * The property that matters: **a half-written package is never mistaken for an
 * installed one.** Packages are hundreds of megabytes and arrive over a mobile
 * connection, so interruption is the normal case, not the exception. A marker
 * file written only after extraction succeeds is what makes the difference
 * between "still downloading" and "broken in a confusing way".
 */
class RuntimePackageStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store() = RuntimePackageStore(temporary.newFolder("runtime"))

    private val wine = RuntimePackageCatalog.WINE_ARM64EC

    @Test
    fun `packages live under the directory the app owns, never beside the game`() {
        val store = store()

        assertTrue(store.packageDir(wine).path.startsWith(store.root.path))
        assertTrue(store.prefixDir().path.startsWith(store.root.path))
    }

    @Test
    fun `a package with no marker is missing`() {
        assertEquals(RuntimePackageStore.InstallState.Missing, store().stateOf(wine))
    }

    @Test
    fun `a marked package is installed, with its version`() {
        val store = store()

        store.markInstalled(wine, 1_700_000_000_000L)

        assertEquals(
            RuntimePackageStore.InstallState.Installed("11.3", 1_700_000_000_000L),
            store.stateOf(wine),
        )
    }

    @Test
    fun `an upgrade leaves the old version recorded as stale`() {
        // The point of keeping the version in the marker: without it an updated
        // URL would silently leave the previous build in place.
        val store = store()
        store.markInstalled(wine, 1L)

        val upgraded = RuntimePackageCatalog.WINE_ARM64EC.copy(version = "12.0")

        assertEquals(
            RuntimePackageStore.InstallState.Stale(installed = "11.3", expected = "12.0"),
            store.stateOf(upgraded),
        )
    }

    @Test
    fun `a truncated marker is treated as missing rather than trusted`() {
        val store = store()
        store.packageDir(wine).mkdirs()
        File(store.packageDir(wine), ".installed").writeText("")

        assertEquals(RuntimePackageStore.InstallState.Missing, store.stateOf(wine))
    }

    @Test
    fun `removing a package deletes its files and its marker`() {
        val store = store()
        store.markInstalled(wine, 1L)
        File(store.packageDir(wine), "bin/wine").apply {
            parentFile?.mkdirs()
            writeText("wine")
        }

        store.remove(wine)

        assertEquals(RuntimePackageStore.InstallState.Missing, store.stateOf(wine))
        assertTrue(!File(store.packageDir(wine), "bin/wine").exists())
    }

    @Test
    fun `downloads are staged apart from installed packages`() {
        val store = store()

        assertTrue(store.stagedFile(wine).path.contains("staging"))
        assertTrue(store.stagedFile(wine) != File(store.packageDir(wine), ".installed"))
    }

    @Test
    fun `the Wine, emulator and WoW64 roots point at real packages`() {
        val store = store()

        assertEquals(store.packageDir(RuntimePackageCatalog.WINE_ARM64EC), store.wineRoot())
        assertEquals(store.packageDir(RuntimePackageCatalog.BOX64_BIONIC), store.emulatorRoot())
        assertEquals(store.packageDir(RuntimePackageCatalog.WOWBOX64), store.wow64Root())
    }
}
