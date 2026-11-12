package com.bloodline.android.runtime.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the package catalogue.
 *
 * These are licensing and correctness tests as much as code tests. Point 6 of
 * the brief is explicit that a component which cannot be redistributed must
 * have a legitimate acquisition path rather than being quietly copied, so the
 * catalogue is asserted to satisfy that — every entry carries a licence with a
 * source URL, every URL is pinned and served over HTTPS, and the one component
 * found during discovery with **no declared licence** (the Bifrost PanVK build)
 * is absent from the list rather than present with a hopeful note.
 */
class RuntimePackageCatalogTest {

    @Test
    fun `every package is fetched over HTTPS from a pinned tag`() {
        RuntimePackageCatalog.ALL.forEach { pack ->
            assertTrue("${pack.id} is not HTTPS", pack.url.startsWith("https://"))
            assertTrue(
                "${pack.id} is pinned to a moving tag, so it could change under us: ${pack.url}",
                !pack.url.endsWith("/latest") && !pack.url.contains("/latest/download"),
            )
        }
    }

    @Test
    fun `every package carries a licence with somewhere to read it`() {
        RuntimePackageCatalog.ALL.forEach { pack ->
            assertTrue("${pack.id} has no licence name", pack.licence.name.isNotBlank())
            assertTrue("${pack.id} has no SPDX id", pack.licence.spdx.isNotBlank())
            assertTrue(
                "${pack.id} gives nowhere to read the licence",
                pack.licence.referenceUrl.startsWith("https://"),
            )
            assertTrue(
                "${pack.id} has no copyright line for NOTICE",
                pack.licence.attribution.isNotBlank(),
            )
        }
    }

    @Test
    fun `every package points at its source, as copyleft requires`() {
        // LGPL-2.1 (Wine) carries a source-distribution obligation. Recording
        // where the source lives is what lets us meet it.
        RuntimePackageCatalog.ALL.forEach { pack ->
            assertNotNull("${pack.id} has no source URL", pack.licence.sourceUrl)
        }
    }

    @Test
    fun `nothing in the catalogue is marked non-redistributable`() {
        // If a package is listed here, this app may fetch and unpack it. The
        // PanVK build that failed this test is deliberately absent instead.
        RuntimePackageCatalog.ALL.forEach { pack ->
            assertTrue(
                "${pack.id} is not redistributable and must not be listed",
                pack.licence.redistributable,
            )
        }
    }

    @Test
    fun `no Vulkan driver is bundled or auto-downloaded`() {
        // Vulkan stays optional and never silently enabled: a user installs it
        // themselves or does not have it.
        assertNull(RuntimePackageCatalog.byId("panvk-bifrost"))
        assertTrue(RuntimePackageCatalog.ALL.none { it.type == PackageType.GRAPHICS })
    }

    // -------------------------------------------------------- 32-bit coverage

    @Test
    fun `the required set can host a 32-bit executable`() {
        // The acceptance criterion. A 32-bit game needs a WoW64 layer, and it
        // must be in the required set rather than an optional extra.
        val woW64 = RuntimePackageCatalog.REQUIRED.filter { it.type == PackageType.WOW64 }

        assertEquals(listOf(RuntimePackageCatalog.WOWBOX64), woW64)
    }

    @Test
    fun `Wine and an emulator are both required`() {
        val types = RuntimePackageCatalog.REQUIRED.map { it.type }

        assertTrue(types.contains(PackageType.WINE))
        assertTrue(types.contains(PackageType.CPU_EMULATOR))
    }

    @Test
    fun `FEX is offered as an alternative, not required`() {
        // Two independent ways to host 32-bit code is the point of keeping the
        // emulator a replaceable module.
        assertFalse(RuntimePackageCatalog.REQUIRED.contains(RuntimePackageCatalog.FEX))
        assertTrue(RuntimePackageCatalog.ALL.contains(RuntimePackageCatalog.FEX))
    }

    @Test
    fun `every package is built for arm64`() {
        RuntimePackageCatalog.ALL.forEach { pack ->
            assertTrue("${pack.id} is built for ${pack.abi}", pack.abi.startsWith("arm64"))
        }
    }

    // ---------------------------------------------------------------- indices

    @Test
    fun `ids are unique and survive a round trip`() {
        val ids = RuntimePackageCatalog.ALL.map { it.id }

        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { id -> assertEquals(id, RuntimePackageCatalog.byId(id)?.id) }
    }

    @Test
    fun `targets are unique, so packages cannot overwrite each other`() {
        val targets = RuntimePackageCatalog.ALL.map { it.target }

        assertEquals(targets.size, targets.distinct().size)
    }

    @Test
    fun `licences are deduplicated for NOTICE`() {
        // Box64 and WOWBox64 share a licence; NOTICE should list it once.
        val spdxIds = RuntimePackageCatalog.licences().map { it.spdx }

        assertEquals(spdxIds.size, spdxIds.distinct().size)
    }

    @Test
    fun `the download size is known, because the UI has to ask first`() {
        // A 269 MB download over mobile data is not something to start quietly.
        RuntimePackageCatalog.ALL.forEach { pack ->
            assertTrue("${pack.id} has no advertised size", pack.sizeBytes > 0)
        }

        assertTrue(RuntimePackageCatalog.totalBytes(RuntimePackageCatalog.REQUIRED) > 0)
    }
}
