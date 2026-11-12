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
 * Two rules are pinned down here, and both came out of the investigation:
 *
 * 1. **The default is the native GLES wrapper, not VirGL.** The earlier version
 *    of this file asserted the opposite, on the reasoning that Mesa's Panfrost
 *    GLES is conformant on the Mali-G52 — which is true, and which is why VirGL
 *    was proposed. But the projects that specialise in Mali do not use it:
 *    Winlator Mali *removed* VirGL in 1.2 in favour of a native EGL renderer.
 *    Loading the vendor's own GLES driver needs no Mesa in between.
 *
 * 2. **PanVK is never selected automatically**, however good the device looks.
 *    A measurement can prove a backend *might* work, never that it does, and
 *    PanVK is non-conformant on this generation of Mali.
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
    fun `picks the GLES wrapper for a GLES device`() {
        val selection = GraphicsBackendSelector.select(glesOnly)

        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, selection.backend)
        assertFalse(selection.wasFallback)
    }

    @Test
    fun `never picks PanVK automatically, even when the device supports Vulkan`() {
        // The important one. A device that reports a working Vulkan stack is
        // still not evidence that it can run this game.
        val selection = GraphicsBackendSelector.select(withVulkan)

        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, selection.backend)
    }

    @Test
    fun `picks the safe default even before anything has been measured`() {
        // A device that has not been probed must not silently end up on the
        // experimental backend.
        val selection = GraphicsBackendSelector.select(unknown)

        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, selection.backend)
        assertTrue(selection.wasFallback)
    }

    @Test
    fun `the wrapper is first in the fallback order`() {
        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, GraphicsBackend.entries.first())
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

        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, selection.backend)
        assertTrue(selection.wasFallback)
        assertTrue(selection.reason.contains("not usable"))
    }

    @Test
    fun `says why a fallback happened`() {
        val selection = GraphicsBackendSelector.select(glesOnly, GraphicsBackend.PANVK_DXVK)

        assertTrue(selection.reason.contains("PanVK"))
        assertTrue(selection.reason.contains("Vulkan"))
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
    fun `the GLES routes need OpenGL ES 3_1`() {
        val gles30 = RuntimeCapabilities(
            glesVersion = "OpenGL ES 3.0 build 1.9",
            measured = true,
        )

        assertTrue(GraphicsBackend.GLES_WRAPPER_WINE_D3D.isSupportedBy(glesOnly))
        assertTrue(GraphicsBackend.VIRGL_WINE_D3D.isSupportedBy(glesOnly))
        assertFalse(GraphicsBackend.GLES_WRAPPER_WINE_D3D.isSupportedBy(gles30))
        assertFalse(GraphicsBackend.VIRGL_WINE_D3D.isSupportedBy(gles30))
    }

    @Test
    fun `nothing is supported on a device that has not been measured`() {
        // Unmeasured is not the same as fine. A device we know nothing about
        // must not be handed to a backend on the assumption it will cope.
        GraphicsBackend.entries.forEach { backend ->
            assertFalse(
                "${backend.id} claims support on an unmeasured device.",
                backend.isSupportedBy(unknown),
            )
        }
    }

    @Test
    fun `no backend reports ready on a device alone`() {
        // Device measurements can rule a backend out, never in. The shipped
        // binaries are part of every backend's requirements, and none are
        // installed yet.
        val statuses = GraphicsBackend.entries.map { it.status(withVulkan) }

        assertTrue(statuses.none { it == IntegrationStatus.READY })
    }

    @Test
    fun `a backend that is missing everything says so`() {
        val blocker = GraphicsBackend.PANVK_DXVK.blocker(RuntimeCapabilities())

        assertTrue(blocker.contains("Vulkan"))
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `the fallback order puts the experimental backend last`() {
        val entries = GraphicsBackend.entries

        assertTrue(entries.indexOf(GraphicsBackend.GLES_WRAPPER_WINE_D3D) < entries.indexOf(GraphicsBackend.PANVK_DXVK))
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
