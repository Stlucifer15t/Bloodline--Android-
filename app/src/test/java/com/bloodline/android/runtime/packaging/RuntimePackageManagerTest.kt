package com.bloodline.android.runtime.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the state machine around acquiring runtime packages.
 *
 * These deliberately avoid the network. What is under test is the bookkeeping
 * that decides whether the Launch button may light up, and that has to be right
 * before a single byte is downloaded:
 *
 * * nothing is reported installed until it has been unpacked **and** marked;
 * * a package built for another ABI is refused outright;
 * * a package with no published checksum is reported **unverified**, never
 *   verified by omission.
 *
 * That last one is the rule most likely to be quietly broken later, because
 * treating "no checksum" as "no problem" is the easy implementation.
 */
class RuntimePackageManagerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val required = RuntimePackageCatalog.REQUIRED

    private fun store(name: String) = RuntimePackageStore(temporary.newFolder(name))

    private fun manager(name: String = "runtime"): Pair<RuntimePackageStore, RuntimePackageManager> {
        val store = store(name)
        return store to RuntimePackageManager(store)
    }

    @Test
    fun `nothing is installed to begin with`() {
        val (_, manager) = manager()

        assertTrue(manager.missingPackages().containsAll(required))
        assertFalse(manager.isRuntimeComplete("arm64-v8a"))
    }

    @Test
    fun `the runtime is complete only when every required package is installed`() {
        val (store, manager) = manager("runtime-complete")

        required.dropLast(1).forEach { store.markInstalled(it, 1L) }
        assertFalse("A partial install must not count.", manager.isRuntimeComplete("arm64-v8a"))

        store.markInstalled(required.last(), 1L)
        assertTrue(manager.isRuntimeComplete("arm64-v8a"))
        assertTrue(manager.missingPackages().isEmpty())
    }

    @Test
    fun `the runtime is never complete on a non-arm64 device`() {
        // The packages are arm64 builds. Reporting "complete" on a 32-bit-only
        // device would promise a launch that cannot work.
        val (store, manager) = manager("runtime-abi")
        required.forEach { store.markInstalled(it, 1L) }

        assertFalse(manager.isRuntimeComplete("armeabi-v7a"))
        assertFalse(manager.isRuntimeComplete(""))
    }

    @Test
    fun `a stale package is reported stale, not installed`() {
        val (store, manager) = manager("runtime-stale")
        store.markInstalled(RuntimePackageCatalog.WINE_10, 1L)

        val upgraded = RuntimePackageCatalog.WINE_10.copy(version = "11.0")

        assertEquals(
            RuntimePackageManager.State.Stale(installed = "10.0", expected = "11.0"),
            manager.stateOf(upgraded),
        )
    }

    @Test
    fun `a package with no published checksum is reported unverified`() {
        // Upstream publishes no SHA-256 for these assets. Saying "unverified"
        // is honest; saying nothing, or claiming verification, is not.
        val (store, manager) = manager("runtime-digest")
        store.markInstalled(RuntimePackageCatalog.WINE_10, 1L)

        val state = manager.stateOf(RuntimePackageCatalog.WINE_10)

        assertEquals(
            RuntimePackageManager.State.Installed(version = "10.0", verified = false),
            state,
        )
    }

    @Test
    fun `a package with a published checksum would be reported verified once checked`() {
        val (store, manager) = manager("runtime-digest2")
        val withDigest = RuntimePackageCatalog.WINE_10.copy(sha256 = "abc123")
        store.markInstalled(withDigest, 1L)

        val state = manager.stateOf(withDigest)

        assertTrue((state as RuntimePackageManager.State.Installed).verified)
    }

    @Test
    fun `installing on the wrong ABI is refused before anything is downloaded`() {
        val (_, manager) = manager("runtime-wrong-abi")

        val state = manager.install(RuntimePackageCatalog.WINE_10, abi = "armeabi-v7a")

        assertTrue(state is RuntimePackageManager.State.Failed)
        assertTrue((state as RuntimePackageManager.State.Failed).reason.contains("armeabi-v7a"))
    }

    @Test
    fun `the order of acquisition puts the largest package first`() {
        // Wine is ~269 MB of a ~277 MB total. Failing on it immediately is far
        // kinder than failing after everything else has downloaded.
        assertEquals(RuntimePackageCatalog.WINE_10, required.first())
    }
}
