package com.bloodline.android.runtime

import android.os.Build
import com.bloodline.android.diagnostics.GpuInfo

/**
 * Turns probe output into [RuntimeCapabilities].
 *
 * Every field comes from a measurement; none is inferred from a model name or a
 * spec sheet. That rule matters more here than anywhere else in the runtime:
 * the graphics decision for a Mali-G52 is only safely made from what *this*
 * particular device reports, because the same SoC name covers devices with and
 * without a DRM render node, with and without a Vulkan loader.
 *
 * It is deliberately a pure function of the probe outputs. The Activity gathers
 * the strings on a background thread and hands them over, which keeps the
 * interpretation unit-testable and keeps the Activity from knowing what a
 * `personality32` line means.
 */
object RuntimeCapabilitiesReader {

    /**
     * @param runtimeOutput  output of `runtime_probe` — ptrace, memfd, 32-bit.
     * @param graphicsOutput output of `graphics_probe` — GPU nodes, Vulkan loader.
     * @param processOutput  output of `process_probe` — process limit, reaping.
     * @param gpu            GL renderer and GLES version from [GpuInfo], or null
     *                       when no EGL context could be created.
     * @param abis           the process's ABI list; defaults to the device's.
     */
    fun assemble(
        runtimeOutput: String = "",
        graphicsOutput: String = "",
        processOutput: String = "",
        gpu: GpuInfo.Info? = null,
        abis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty(),
    ): RuntimeCapabilities {

        // `abilist32` is a comma-separated ABI list ("armeabi-v7a,armeabi"), or
        // "unknown" when the property is absent — not a boolean. An empty or
        // unknown value means the device does not advertise a 32-bit ABI.
        val abilist32 = RuntimeProbe.value(runtimeOutput, "abilist32")
            ?.takeUnless { it.equals("unknown", ignoreCase = true) }
            .orEmpty()

        val gpuNodes = GraphicsProbe.gpuNodes(graphicsOutput)

        return RuntimeCapabilities(
            abis = abis,
            has32BitAbi = abis.any { is32BitAbi(it) } || abilist32.isNotEmpty(),
            canExecute32Bit = flag(runtimeOutput, "personality32") == true,
            ptraceAllowed = flag(runtimeOutput, "ptrace") == true,
            memfdExecAllowed = flag(runtimeOutput, "exec-memfd") == true,
            processLimit = ProcessProbe.processLimit(processOutput),
            gpuNodes = gpuNodes,

            // The DRM driver name is the closest thing to a graphics device
            // identity available without loading a Vulkan stack of our own, and
            // it is recorded as a *driver* — never as proof that Vulkan works.
            vulkanDevice = GraphicsProbe.drmDriver(graphicsOutput),
            vulkanUsable = GraphicsProbe.vulkanLoaderLoadable(graphicsOutput) == true &&
                gpuNodes.isNotEmpty(),
            glesVersion = gpu?.glVersion.orEmpty(),
            glRenderer = gpu?.renderer.orEmpty(),
            // Only a probe that actually completed counts as a measurement.
            // Output alone is not enough: a probe that was killed midway still
            // prints something, and reading it as fact would be worse than
            // admitting we do not know.
            measured = listOf(
                runtimeOutput to RuntimeProbe.SUCCESS_MARKER,
                graphicsOutput to GraphicsProbe.SUCCESS_MARKER,
                processOutput to ProcessProbe.SUCCESS_MARKER,
            ).any { (output, marker) -> output.contains(marker) },
        )
    }

    private fun is32BitAbi(abi: String): Boolean =
        abi.contains("armeabi") || (abi.contains("x86") && !abi.contains("64"))

    /**
     * Reads a `key: ALLOWED` / `key: DENIED` line.
     *
     * Returns `null` when the probe never printed the key, so "the device
     * refuses this" and "we do not know" stay distinguishable — a distinction
     * that decides whether the runtime blocks a launch or merely warns.
     */
    private fun flag(output: String, key: String): Boolean? = when {
        output.lineSequence().any { it.startsWith("$key: ALLOWED") } -> true
        output.lineSequence().any { it.startsWith("$key:") } -> false
        else -> null
    }
}
