package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests how probe output becomes device facts.
 *
 * The rule under test is that **nothing is inferred**. A device is described by
 * what its probes printed, so a Mali-G52 with no Vulkan loader and one with a
 * working Vulkan stack get different answers — which is exactly the distinction
 * the graphics decision depends on.
 */
class RuntimeCapabilitiesReaderTest {

    private val runtime = """
        runtime-probe: ok
        abi: arm64-v8a
        ptrace: ALLOWED
        memfd-create: ALLOWED
        exec-memfd: ALLOWED
        personality32: ALLOWED
        abilist32: armeabi-v7a,armeabi
        cores: 8
    """.trimIndent()

    private val graphics = """
        graphics-probe: ok
        dri: no render nodes
        mali-node: /dev/mali0
        mali-kbase: reachable
        vulkan-loader: not loadable
    """.trimIndent()

    private val process = """
        process-probe: ok
        max-processes-soft: 32
        exec-wait: ALLOWED
        process-tree: ALLOWED
    """.trimIndent()

    private val abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun assemble(
        runtimeOutput: String = runtime,
        graphicsOutput: String = graphics,
        processOutput: String = process,
    ) = RuntimeCapabilitiesReader.assemble(
        runtimeOutput = runtimeOutput,
        graphicsOutput = graphicsOutput,
        processOutput = processOutput,
        abis = abis,
    )

    // ------------------------------------------------------------- assembling

    @Test
    fun `reads the measurements that matter`() {
        val capabilities = assemble()

        assertTrue(capabilities.ptraceAllowed)
        assertTrue(capabilities.memfdExecAllowed)
        assertTrue(capabilities.canExecute32Bit)
        assertTrue(capabilities.has32BitAbi)
        assertEquals(32, capabilities.processLimit)
        assertTrue(capabilities.measured)
    }

    @Test
    fun `reads the GPU nodes as the graphics route`() {
        val capabilities = assemble()

        assertEquals(setOf("mali0"), capabilities.gpuNodes)
        assertTrue(capabilities.hasMaliKbase)
        assertFalse(capabilities.hasDriRenderNode)
    }

    @Test
    fun `a device with a Vulkan loader but no GPU node is not Vulkan usable`() {
        // The distinction that keeps PanVK from being chosen: a loader that
        // resolves is not a driver that renders.
        val capabilities = assemble(
            graphicsOutput = """
                graphics-probe: ok
                mali-kbase: not found
                vulkan-loader: loadable
            """.trimIndent(),
        )

        assertFalse(capabilities.vulkanUsable)
        assertTrue(capabilities.gpuNodes.isEmpty())
    }

    @Test
    fun `a device with both is Vulkan usable`() {
        val capabilities = assemble(
            graphicsOutput = """
                graphics-probe: ok
                dri-node: renderD128
                dri-driver: panfrost
                mali-kbase: not found
                vulkan-loader: loadable
            """.trimIndent(),
        )

        assertTrue(capabilities.vulkanUsable)
        assertTrue(capabilities.hasDriRenderNode)
        assertEquals("panfrost", capabilities.vulkanDevice)
    }

    @Test
    fun `parses the GLES version so the backend can check it`() {
        val capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = runtime,
            graphicsOutput = graphics,
            processOutput = process,
            gpu = com.bloodline.android.diagnostics.GpuInfo.Info(
                vendor = "ARM",
                renderer = "Mali-G52 MC2",
                glVersion = "OpenGL ES 3.2 build 1.9@1234",
                shadingLanguageVersion = "OpenGL ES GLSL ES 3.2",
                eglVersion = "1.5",
            ),
            abis = abis,
        )

        assertEquals(3 to 2, capabilities.glesMajorMinor)
        assertTrue(capabilities.hasGles31)
        assertEquals("Mali-G52 MC2", capabilities.glRenderer)
    }

    @Test
    fun `GLES 3_0 does not satisfy the 3_1 requirement`() {
        val capabilities = RuntimeCapabilities(
            glesVersion = "OpenGL ES 3.0 build 1.9",
            measured = true,
        )

        assertFalse(capabilities.hasGles31)
    }

    // ------------------------------------------------------------- not measured

    @Test
    fun `nothing measured means nothing claimed`() {
        val capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = "",
            graphicsOutput = "",
            processOutput = "",
            abis = abis,
        )

        assertFalse(capabilities.measured)
        assertFalse(capabilities.ptraceAllowed)
        assertFalse(capabilities.canExecute32Bit)
        assertEquals(0, capabilities.processLimit)
    }

    @Test
    fun `a probe that was killed part way through is not a measurement`() {
        // A killed probe still prints something, including real-looking facts.
        // Reading those as measurements would be worse than admitting we do not
        // know, so only the completion marker counts.
        val interrupted = """
            runtime-probe: interrupted
            ptrace: ALLOWED
            exec-memfd: ALLOWED
        """.trimIndent()

        val capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = interrupted,
            graphicsOutput = "",
            processOutput = "",
            abis = abis,
        )

        assertTrue(capabilities.ptraceAllowed)
        assertFalse(capabilities.measured)
    }

    @Test
    fun `an unreadable process limit stays zero rather than being guessed`() {
        // Zero means "not read". The runtime treats that as no constraint, which
        // is safer than inventing a number that might block a launch.
        val capabilities = assemble(processOutput = "process-probe: ok\nexec-wait: ALLOWED")

        assertEquals(0, capabilities.processLimit)
    }

    // ------------------------------------------------------------------- abis

    @Test
    fun `a 64-bit only device with no abilist32 has no 32-bit ABI`() {
        val capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = "runtime-probe: ok\nabi: arm64-v8a\nabilist32: unknown",
            graphicsOutput = graphics,
            processOutput = process,
            abis = listOf("arm64-v8a"),
        )

        assertFalse(capabilities.has32BitAbi)
    }

    @Test
    fun `a 64-bit x86 abi is not treated as a 32-bit userland`() {
        // The easy mistake is `contains("x86")`, which matches x86_64 too.
        val capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = "runtime-probe: ok\nabi: x86_64",
            graphicsOutput = "",
            processOutput = "",
            abis = listOf("x86_64"),
        )

        assertFalse(capabilities.has32BitAbi)
    }

    @Test
    fun `a 32-bit x86 abi is treated as a 32-bit userland`() {
        val capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = "runtime-probe: ok\nabi: x86",
            graphicsOutput = "",
            processOutput = "",
            abis = listOf("x86"),
        )

        assertTrue(capabilities.has32BitAbi)
    }
}
