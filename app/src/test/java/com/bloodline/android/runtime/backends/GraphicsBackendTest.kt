package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.RuntimeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the graphics decision.
 *
 * The rule under test is the one the research produced: **Panfrost's OpenGL ES
 * 3.1 is conformant on the Mali-G52, PanVK is not**, so VirGL + WineD3D is the
 * default and Vulkan is never chosen automatically — however good the device
 * looks. These tests exist so that rule cannot be quietly reversed later.
 */
class GraphicsBackendTest {

    /** A device with GLES 3.1 and no Vulkan: the expected shape of the CK6. */
    private val glesOnly = RuntimeCapabilities(
        glesVersion = "OpenGL ES 3.2 build 1.9@1234",
        glRenderer = "Mali-G52 MC2",
        gpuNodes = setOf("mali0"),
        measured = true,
    )

    /** A device that also has a working Vulkan stack. */
    private val withVulkan = glesOnly.copy(
        vulkanDevice = "panfrost",
        vulkanApiVersion = 0x400000 + 0x10000,
        vulkanUsable = true,
        gpuNodes = setOf("mali0", "dri/renderD128"),
    )

    /** Nothing measured yet, which is the state before the probes have run. */
    private val unknown = RuntimeCapabilities()

    // ------------------------------------------------------------- the default

    @Test
    fun `picks VirGL for a GLES device`() {
        val selection = GraphicsBackendSelector.select(glesOnly)

        assertEquals(GraphicsBackend.VIRGL_WINE_D3D, selection.backend)
        assertFalse(selection.wasFallback)
    }

    @Test
    fun `never picks PanVK automatically, even when the device supports Vulkan`() {
        // The important one. PanVK on Bifrost v7 is experimental and
        // non-conformant; a device that reports a working Vulkan stack is still
        // not evidence that it can run this game.
        val selection = GraphicsBackendSelector.select(withVulkan)

        assertEquals(GraphicsBackend.VIRGL_WINE_D3D, selection.backend)
    }

    @Test
    fun `picks VirGL even before anything has been measured`() {
        // A device that has not been probed must not silently end up on the
        // experimental backend.
        val selection = GraphicsBackendSelector.select(unknown)

        assertEquals(GraphicsBackend.VIRGL_WINE_D3D, selection.backend)
        assertTrue(selection.wasFallback)
    }

    // ------------------------------------------------------- explicit choices

    @Test
    fun `honours an explicit PanVK choice when the device supports it`() {
        val selection = GraphicsBackendSelector.select(withVulkan, GraphicsBackend.PANVK_DXVK)

        assertEquals(GraphicsBackend.PANVK_DXVK, selection.backend)
        assertFalse(selection.wasFallback)
    }

    @Test
    fun `falls back when an explicit PanVK choice is not supported`() {
        val selection = GraphicsBackendSelector.select(glesOnly, GraphicsBackend.PANVK_DXVK)

        assertEquals(GraphicsBackend.VIRGL_WINE_D3D, selection.backend)
        assertTrue(selection.wasFallback)
        assertTrue(selection.reason.contains("not usable"))
    }

    @Test
    fun `says why a fallback happened`() {
        val selection = GraphicsBackendSelector.select(glesOnly, GraphicsBackend.PANVK_DXVK)

        assertTrue(selection.reason.contains("PanVK"))
        assertTrue(selection.reason.contains("Vulkan implementation"))
    }

    // ------------------------------------------------------------ requirements

    @Test
    fun `PanVK needs both a Vulkan stack and a GPU node`() {
        val onlyNode = RuntimeCapabilities(gpuNodes = setOf("mali0"), measured = true)
        val onlyVulkan = RuntimeCapabilities(vulkanUsable = true, measured = true)

        assertFalse(GraphicsBackend.PANVK_DXVK.isSupportedBy(onlyNode))
        assertFalse(GraphicsBackend.PANVK_DXVK.isSupportedBy(onlyVulkan))
        assertTrue(GraphicsBackend.PANVK_DXVK.isSupportedBy(withVulkan))
    }

    @Test
    fun `VirGL needs OpenGL ES 3_1`() {
        val gles30 = RuntimeCapabilities(
            glesVersion = "OpenGL ES 3.0 build 1.9",
            measured = true,
        )

        assertTrue(GraphicsBackend.VIRGL_WINE_D3D.isSupportedBy(glesOnly))
        assertFalse(GraphicsBackend.VIRGL_WINE_D3D.isSupportedBy(gles30))
    }

    @Test
    fun `no backend reports ready on a device alone`() {
        // Device measurements can rule a backend out, never in. The shipped
        // binaries are part of every backend's requirements, and none ship yet.
        val statuses = GraphicsBackend.entries.map { it.status(withVulkan) }

        assertTrue(statuses.none { it == IntegrationStatus.READY })
    }

    @Test
    fun `a backend that is missing everything says so`() {
        val blocker = GraphicsBackend.PANVK_DXVK.blocker(RuntimeCapabilities())

        assertTrue(blocker.contains("Vulkan"))
        assertTrue(blocker.contains("DXVK"))
    }

    // ----------------------------------------------------------------- ordering

    @Test
    fun `the fallback order puts the safe backend first`() {
        val entries = GraphicsBackend.entries

        assertEquals(GraphicsBackend.VIRGL_WINE_D3D, entries.first())
        assertTrue(entries.indexOf(GraphicsBackend.PANVK_DXVK) < entries.indexOf(GraphicsBackend.LLVMPIPE))
    }

    @Test
    fun `only PanVK is marked experimental`() {
        assertEquals(
            listOf(GraphicsBackend.PANVK_DXVK),
            GraphicsBackend.entries.filter { it.experimental },
        )
    }

    @Test
    fun `ids survive a round trip`() {
        GraphicsBackend.entries.forEach { backend ->
            assertEquals(backend, GraphicsBackend.fromId(backend.id))
        }
    }
}
