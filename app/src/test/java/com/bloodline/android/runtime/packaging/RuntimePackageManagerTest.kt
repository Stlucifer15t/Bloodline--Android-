package com.bloodline.android.runtime.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        store.markInstalled(RuntimePackageCatalog.WINE_ARM64EC, 1L)

        val upgraded = RuntimePackageCatalog.WINE_ARM64EC.copy(version = "12.0")

        assertEquals(
            RuntimePackageManager.State.Stale(installed = "11.3", expected = "12.0"),
            manager.stateOf(upgraded),
        )
    }

    @Test
    fun `a package with no checksum is reported unverified`() {
        // Only two of the five packages have been downloaded and measured; the
        // rest carry no digest at all. Saying "unverified" is honest; saying
        // nothing, or claiming verification, is not.
        val (store, manager) = manager("runtime-digest")
        store.markInstalled(RuntimePackageCatalog.FEX, 1L)

        assertEquals(
            RuntimePackageManager.State.Installed(version = "2611", verified = false),
            manager.stateOf(RuntimePackageCatalog.FEX),
        )
    }

    @Test
    fun `a package whose checksum we measured is reported verified`() {
        // Wine and the WoW64 layer were fetched and hashed in CI, so an install
        // of either is checked against a real digest.
        val (store, manager) = manager("runtime-digest-measured")
        store.markInstalled(RuntimePackageCatalog.WINE_ARM64EC, 1L)

        assertEquals(
            RuntimePackageManager.State.Installed(version = "11.3", verified = true),
            manager.stateOf(RuntimePackageCatalog.WINE_ARM64EC),
        )
    }

    @Test
    fun `a package with a published checksum would be reported verified once checked`() {
        val (store, manager) = manager("runtime-digest2")
        val withDigest = RuntimePackageCatalog.WINE_ARM64EC.copy(sha256 = "abc123")
        store.markInstalled(withDigest, 1L)

        val state = manager.stateOf(withDigest)

        assertTrue((state as RuntimePackageManager.State.Installed).verified)
    }

    @Test
    fun `installing on the wrong ABI is refused before anything is downloaded`() {
        val (_, manager) = manager("runtime-wrong-abi")

        val state = manager.install(RuntimePackageCatalog.WINE_ARM64EC, abi = "armeabi-v7a")

        assertTrue(state is RuntimePackageManager.State.Failed)
        assertTrue((state as RuntimePackageManager.State.Failed).reason.contains("armeabi-v7a"))
    }

    @Test
    fun `the order of acquisition puts the largest package first`() {
        // Wine is ~269 MB of a ~277 MB total. Failing on it immediately is far
        // kinder than failing after everything else has downloaded.
        assertEquals(RuntimePackageCatalog.WINE_ARM64EC, required.first())
    }

    @Test
    fun `a download that is not the published length is refused`() {
        // No checksum is published for these assets, so the length is the only
        // check there is. Both failures it catches are ordinary on a phone: an
        // interrupted transfer, and a redirect that serves HTML instead.
        val (_, manager) = manager("runtime-length")
        val wine = RuntimePackageCatalog.WINE_ARM64EC

        val truncated = temporary.newFile("truncated.bin")
        truncated.writeBytes(ByteArray(4096))

        try {
            manager.checkExpectedLength(wine, truncated)
            fail("A 4 KB file must not pass for a 226 MB package.")
        } catch (error: SecurityException) {
            assertTrue(error.message!!.contains("226178252"))
            assertTrue(error.message!!.contains("4096"))
        }
    }

    @Test
    fun `a download of exactly the published length is accepted`() {
        val (_, manager) = manager("runtime-length-ok")

        val exact = temporary.newFile("exact.bin")
        exact.writeBytes(ByteArray(8))
        val pack = RuntimePackageCatalog.WOWBOX64.copy(sizeBytes = 8L)

        manager.checkExpectedLength(pack, exact)
    }

    @Test
    fun `the two packages we have actually downloaded carry the checksum we measured`() {
        // Measured in CI by fetching the package and hashing the bytes, not
        // published by upstream, which publishes none. They are pins: they
        // catch a changed or substituted file. They are not an upstream
        // attestation, and must never be described as one.
        assertEquals(
            "9fe4cfcd5fa4a9ffe5131620843046ec50b8db980aaa8a51cbcac41270c3a6eb",
            RuntimePackageCatalog.WINE_ARM64EC.sha256,
        )
        assertEquals(
            "8575b947fa674298289ae8089f3e9f2fb936369c88dba07bf223f2ba4f579e52",
            RuntimePackageCatalog.WOWBOX64.sha256,
        )
    }

    @Test
    fun `a package we have never downloaded is left without a checksum rather than given one`() {
        // Guessing a checksum would be worse than none: every install would
        // then fail on a file that is perfectly good.
        assertTrue(RuntimePackageCatalog.FEX.sha256 == null)
        assertTrue(RuntimePackageCatalog.BOX64_BIONIC.sha256 == null)
        assertTrue(RuntimePackageCatalog.WINE_X86_64.sha256 == null)
    }

    @Test
    fun `every pinned package records the length upstream actually publishes`() {
        // These five numbers were read back out of the GitHub release API, not
        // copied from a changelog. They are load-bearing: the length check
        // refuses a download that does not match, so a wrong number here would
        // refuse a perfectly good file.
        val published = mapOf(
            "wine-11.3-arm64ec" to 226_178_252L,
            "wine-11.3-x86_64" to 207_779_756L,
            "wowbox64" to 1_029_612L,
            "box64-bionic" to 5_103_225L,
            "fex-2611" to 2_078_400L,
        )

        RuntimePackageCatalog.ALL.forEach { pack ->
            val expected = published[pack.id]
            assertNotNull("${pack.id} is not a pinned package", expected)
            assertEquals("${pack.id} has the wrong published length", expected, pack.sizeBytes)
        }
    }
}
