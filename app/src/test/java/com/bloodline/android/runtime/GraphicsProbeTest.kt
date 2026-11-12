package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests how the graphics probe's output is interpreted.
 *
 * Running the probe needs a device, so what is covered here is the reading of
 * it — which is where the risk is. The distinction these tests defend: opening
 * a GPU device node proves **a route exists**, not that a driver works. A
 * device with `/dev/mali0` and a Vulkan loader is a device where Vulkan is worth
 * *trying*, never one where it is known to work.
 */
class GraphicsProbeTest {

    /** What a phone with Arm's closed driver reports: kbase, no DRM node. */
    private val maliKbase = """
        graphics-probe: ok
        pid: 12345
        dri: no render nodes
        mali-node: /dev/mali0
        mali-kbase: reachable
        vulkan-loader: not loadable
        egl: loadable
        gles: loadable
        graphics-probe: done
    """.trimIndent()

    /** What a phone with an open driver reports: a DRM render node. */
    private val drmNode = """
        graphics-probe: ok
        dri-node: renderD128
        dri-driver: panfrost
        dri-version: 1.3.0
        dri: renderD128
        mali-kbase: not found
        vulkan-loader: loadable
        graphics-probe: done
    """.trimIndent()

    @Test
    fun `accepts a clean run that announced itself`() {
        assertTrue(GraphicsProbe.isSuccessful(exitCode = 0, output = maliKbase))
        assertFalse(GraphicsProbe.isSuccessful(exitCode = 1, output = maliKbase))
        assertFalse(GraphicsProbe.isSuccessful(exitCode = 0, output = "Permission denied"))
    }

    @Test
    fun `reads the Mali kbase node`() {
        assertEquals(setOf("mali0"), GraphicsProbe.gpuNodes(maliKbase))
    }

    @Test
    fun `reads a DRM render node with its device prefix`() {
        assertEquals(setOf("dri/renderD128"), GraphicsProbe.gpuNodes(drmNode))
    }

    @Test
    fun `reports no nodes when the probe found none`() {
        val output = """
            graphics-probe: ok
            dri: absent
            mali-kbase: not found
        """.trimIndent()

        assertTrue(GraphicsProbe.gpuNodes(output).isEmpty())
    }

    @Test
    fun `reads the DRM driver name`() {
        assertEquals("panfrost", GraphicsProbe.drmDriver(drmNode))
        assertNull(GraphicsProbe.drmDriver(maliKbase))
    }

    @Test
    fun `reads whether a Vulkan loader resolved`() {
        assertEquals(true, GraphicsProbe.vulkanLoaderLoadable(drmNode))
        assertEquals(false, GraphicsProbe.vulkanLoaderLoadable(maliKbase))
    }

    @Test
    fun `an absent loader is unknown rather than false`() {
        // "The probe did not say" and "there is no loader" are different, and
        // only the second one should rule a backend out.
        assertNull(GraphicsProbe.vulkanLoaderLoadable("graphics-probe: ok\n"))
    }

    @Test
    fun `summarises the kbase route`() {
        val summary = GraphicsProbe.summary(maliKbase)

        assertTrue(summary.contains("Mali kbase node present"))
        assertTrue(summary.contains("no Vulkan loader"))
    }

    @Test
    fun `summarises the DRM route with its driver`() {
        val summary = GraphicsProbe.summary(drmNode)

        assertTrue(summary.contains("DRM render node present"))
        assertTrue(summary.contains("panfrost"))
    }

    @Test
    fun `a probe that never ran is reported as not measured`() {
        assertEquals("not measured", GraphicsProbe.summary("cannot start: permission denied"))
    }
}
