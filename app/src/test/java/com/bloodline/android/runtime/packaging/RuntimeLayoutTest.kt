package com.bloodline.android.runtime.packaging

import com.bloodline.android.runtime.WineFlavour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for finding the executables inside installed packages.
 *
 * The premise is that this project does not control the layout of a
 * third-party package, so the layout **searches** instead of assuming. The
 * tests pin down both halves of that: a file in an unexpected place is still
 * found, and a file that is genuinely absent produces a message naming the
 * consequence rather than a path.
 */
class RuntimeLayoutTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(): RuntimePackageStore = RuntimePackageStore(temporary.newFolder("runtime"))

    private fun put(store: RuntimePackageStore, pack: RuntimePackage, path: String): File =
        File(store.packageDir(pack), path).apply {
            parentFile?.mkdirs()
            writeText("binary")
        }

    private fun filled(): RuntimePackageStore = store().also {
        put(it, RuntimePackageCatalog.WINE_ARM64EC, "bin/wine")
        put(it, RuntimePackageCatalog.WINE_ARM64EC, "share/wine/wine.inf")
        put(it, RuntimePackageCatalog.WOWBOX64, "wowbox64.dll")
        put(it, RuntimePackageCatalog.BOX64_BIONIC, "box64")
    }

    @Test
    fun `finds the Wine loader`() {
        val layout = RuntimeLayout(filled())

        assertEquals("wine", layout.wineLoader()?.name)
        assertTrue(layout.isComplete(WineFlavour.WOW64_BOX64))
    }

    @Test
    fun `finds a file that is not where we expected it`() {
        // The reason for searching: a package that puts its binary somewhere
        // slightly different should still work, and a hard-coded path would
        // report an error that names the wrong problem.
        val store = store()
        put(store, RuntimePackageCatalog.WINE_ARM64EC, "usr/local/bin/wine")
        put(store, RuntimePackageCatalog.WOWBOX64, "x86/pe-wow/wowbox64.dll")

        val layout = RuntimeLayout(store)

        assertTrue(layout.wineLoader() != null)
        assertTrue(layout.wow64Dll(WineFlavour.WOW64_BOX64) != null)
    }

    @Test
    fun `an empty package reports what is missing`() {
        val missing = RuntimeLayout(store()).missingFiles(WineFlavour.WOW64_BOX64)

        assertTrue(missing.any { it.contains("loader") })
        assertTrue(missing.any { it.contains("wowbox64.dll") })
    }

    @Test
    fun `the missing WoW64 message says why it matters`() {
        // "wowbox64.dll not found" is a file error. The user needs to know the
        // 32-bit game cannot start without it.
        val store = store()
        put(store, RuntimePackageCatalog.WINE_ARM64EC, "bin/wine")

        val missing = RuntimeLayout(store).missingFiles(WineFlavour.WOW64_BOX64)

        assertEquals(1, missing.size)
        assertTrue(missing.first().contains("32-bit"))
    }

    @Test
    fun `an x86_64 Wine needs Box64, and says so`() {
        val store = store()
        put(store, RuntimePackageCatalog.WINE_X86_64, "bin/wine")

        val missing = RuntimeLayout(store).missingFiles(WineFlavour.X86_64_BOX64)

        assertTrue(missing.any { it.contains("Box64") })
        assertFalse(RuntimeLayout(store).isComplete(WineFlavour.X86_64_BOX64))
    }

    @Test
    fun `an x86_64 Wine looks in its own package for the loader`() {
        val store = store()
        put(store, RuntimePackageCatalog.WINE_X86_64, "bin/wine")
        put(store, RuntimePackageCatalog.BOX64_BIONIC, "box64")

        assertTrue(RuntimeLayout(store).isComplete(WineFlavour.X86_64_BOX64))
    }

    @Test
    fun `a flavour with no WoW64 layer needs none`() {
        val layout = RuntimeLayout(filled())

        assertNull(layout.wow64Dll(WineFlavour.X86_64_BOX64))
        assertTrue(layout.isComplete(WineFlavour.X86_64_BOX64))
    }

    @Test
    fun `the WoW64 DLL goes into the prefix, not beside the packages`() {
        // Wine looks for it in drive_c/windows/system32. Unpacking it neatly
        // into its own directory would leave the game unable to start.
        val target = RuntimeLayout(filled()).wow64TargetDirectory()

        assertTrue(target.path.endsWith("prefix/drive_c/windows/system32"))
    }

    @Test
    fun `library directories are ordered, Wine's first`() {
        val store = store()
        put(store, RuntimePackageCatalog.WINE_ARM64EC, "bin/wine")
        File(store.packageDir(RuntimePackageCatalog.WINE_ARM64EC), "lib").mkdirs()
        File(store.packageDir(RuntimePackageCatalog.BOX64_BIONIC), "box64")
            .apply { parentFile?.mkdirs() }.writeText("box64")

        val libs = RuntimeLayout(store).libDirs()

        assertTrue(libs.first().path.contains("wine"))
    }
}
