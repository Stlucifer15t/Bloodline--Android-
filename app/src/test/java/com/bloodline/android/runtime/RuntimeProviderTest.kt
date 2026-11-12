package com.bloodline.android.runtime

import com.bloodline.android.runtime.packaging.RuntimePackageCatalog
import com.bloodline.android.runtime.packaging.RuntimePackageManager
import com.bloodline.android.runtime.packaging.RuntimePackageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the question the launch gate actually asks: *does the runtime have
 * what the game needs?*
 *
 * The interface exists so that question can be answered without the launcher
 * knowing where binaries come from — which also means it can be answered
 * honestly in a test, with no network and no device.
 *
 * One test here is the licensing rule expressed as code: a provider for a
 * non-arm64 device refuses outright, because every package is an arm64 build
 * and promising otherwise would be a lie the user discovers after a 226 MB
 * download.
 */
class RuntimeProviderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun provider(abi: String = "arm64-v8a"): PackagedRuntimeProvider =
        PackagedRuntimeProvider(
            store = RuntimePackageStore(temporary.newFolder("runtime")),
            manager = RuntimePackageManager(RuntimePackageStore(temporary.newFolder("runtime2"))),
            deviceAbi = abi,
        )

    @Test
    fun `nothing is installed to begin with`() {
        val provider = provider()

        assertFalse(provider.isInstalled())
        assertTrue(provider.missing().isNotEmpty())
    }

    @Test
    fun `the required set is a Wine and a WoW64 layer`() {
        // Two packages. That brevity is the whole argument for WoW64 over
        // Box86, which would additionally need a 32-bit userland.
        assertEquals(
            listOf(RuntimePackageCatalog.WINE_ARM64EC, RuntimePackageCatalog.WOWBOX64),
            provider().requiredPackages(),
        )
    }

    @Test
    fun `the missing list names what to install, not just that something is`() {
        val missing = provider().missing()

        assertEquals(2, missing.size)
        assertTrue(missing.any { it.contains("Wine") })
        assertTrue(missing.any { it.contains("WOWBox64") })
    }

    @Test
    fun `a device that cannot run arm64 is refused before anything downloads`() {
        // Every package is an arm64 build. Saying "not installed" here would
        // invite a download that cannot possibly work.
        val missing = provider(abi = "armeabi-v7a").missing()

        assertEquals(1, missing.size)
        assertTrue(missing.first().contains("armeabi-v7a"))
        assertTrue(missing.first().contains("arm64"))
    }

    @Test
    fun `installed packages are not reported missing`() {
        val store = RuntimePackageStore(temporary.newFolder("installed"))
        RuntimePackageCatalog.requiredFor(RuntimePackageCatalog.WINE_ARM64EC)
            .forEach { store.markInstalled(it, 1L) }

        val provider = PackagedRuntimeProvider(
            store = store,
            manager = RuntimePackageManager(store),
        )

        assertTrue(provider.isInstalled())
        assertTrue(provider.missing().isEmpty())
    }

    @Test
    fun `installed is not the same as usable`() {
        // The packages are marked installed but their files are not there, so
        // the launch gate must still refuse. A marker file is a record, not a
        // binary.
        val store = RuntimePackageStore(temporary.newFolder("marked"))
        RuntimePackageCatalog.requiredFor(RuntimePackageCatalog.WINE_ARM64EC)
            .forEach { store.markInstalled(it, 1L) }

        val provider = PackagedRuntimeProvider(
            store = store,
            manager = RuntimePackageManager(store),
        )

        assertTrue(provider.isInstalled())
        assertFalse(provider.isUsable(WineFlavour.WOW64_BOX64))
    }

    @Test
    fun `a static provider lets the gate be tested without a filesystem`() {
        val missing = StaticRuntimeProvider(installed = emptySet()).missing()
        val present = StaticRuntimeProvider(
            installed = RuntimePackageCatalog.requiredFor(RuntimePackageCatalog.WINE_ARM64EC)
                .map { it.id }.toSet(),
        )

        assertTrue(missing.isNotEmpty())
        assertTrue(present.isInstalled())
        assertTrue(present.isUsable(WineFlavour.WOW64_BOX64))
    }

    @Test
    fun `a static provider can describe broken files without any`() {
        val provider = StaticRuntimeProvider(
            installed = RuntimePackageCatalog.requiredFor(RuntimePackageCatalog.WINE_ARM64EC)
                .map { it.id }.toSet(),
            missingFiles = listOf("The WoW64 layer was not found."),
        )

        assertTrue(provider.isInstalled())
        assertFalse(provider.isUsable(WineFlavour.WOW64_BOX64))
        assertEquals(1, provider.fileProblems().size)
    }
}
