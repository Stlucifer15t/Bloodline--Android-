package com.bloodline.android.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs and interprets the graphics capability probe
 * (`lib*.so` built from `app/src/main/cpp/graphics_probe.c`).
 *
 * The question this answers cannot be answered from Java: **which route to the
 * GPU exists on this device?** Mesa's open Panfrost driver needs a DRM render
 * node (`/dev/dri/renderD*`); the community PanVK builds for the Mali-G52 go
 * through Arm's proprietary kbase node (`/dev/mali0`) instead, because devices
 * with the closed driver ship no DRM node. Which of those is present decides
 * whether the Vulkan route is even worth offering — and `android.opengl` has
 * nothing to say about either.
 *
 * What it reports is deliberately narrow: *a node is present*. It does not
 * claim a Vulkan driver is installed and working, because opening `/dev/mali0`
 * proves no such thing. That distinction is the whole reason for a probe instead
 * of an assumption.
 */
object GraphicsProbe {

    /** Name the probe is packaged under; see CMakeLists.txt. */
    const val LIBRARY_NAME = "libgraphics_probe.so"

    /** Printed by the probe when it ran. */
    const val SUCCESS_MARKER = "graphics-probe: ok"

    private const val TIMEOUT_SECONDS = 20L

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)

    fun isSuccessful(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.contains(SUCCESS_MARKER)

    /**
     * The GPU device nodes the probe could reach, in the form
     * [RuntimeCapabilities.gpuNodes] expects: `mali0`, `dri/renderD128`.
     */
    fun gpuNodes(output: String): Set<String> = buildSet {
        output.lineSequence()
            .filter { it.startsWith("mali-node:") }
            .map { it.substringAfter("mali-node:").trim() }
            .filter { it.isNotEmpty() }
            .forEach { add(it.trimStart('/')) }

        output.lineSequence()
            .filter { it.startsWith("dri-node:") }
            .map { it.substringAfter("dri-node:").trim() }
            .map { it.substringBefore(' ') }
            .filter { it.isNotEmpty() }
            .forEach { add("dri/$it") }
    }

    /** The driver name a DRM node reported, e.g. `panfrost`. Null when none did. */
    fun drmDriver(output: String): String? = value(output, "dri-driver:")

    /** Whether a Vulkan loader resolved its entry point. Null when not measured. */
    fun vulkanLoaderLoadable(output: String): Boolean? = when (value(output, "vulkan-loader:")) {
        "loadable" -> true
        "not loadable", "present but vkGetInstanceProcAddr is missing" -> false
        else -> null
    }

    /** One line for the launcher: which routes exist. */
    fun summary(output: String): String {
        if (!output.contains(SUCCESS_MARKER)) return "not measured"

        val parts = mutableListOf<String>()
        val nodes = gpuNodes(output)
        when {
            nodes.any { it.startsWith("mali") } -> parts += "Mali kbase node present"
            nodes.any { it.startsWith("dri/") } ->
                parts += "DRM render node present (${drmDriver(output) ?: "driver unknown"})"
            else -> parts += "no GPU device node"
        }
        when (vulkanLoaderLoadable(output)) {
            true -> parts += "Vulkan loader present"
            false -> parts += "no Vulkan loader"
            null -> Unit
        }
        return parts.joinToString(" · ").ifEmpty { "measured" }
    }

    /** Runs the probe and waits for it. Keep this off the main thread. */
    fun run(context: Context): Result {
        val executable = binary(context)
        if (!executable.exists()) {
            return Result(
                exitCode = EXIT_MISSING,
                output = "Graphics probe not found at ${executable.absolutePath}\n" +
                    "The APK was built without the graphics probe for this ABI.",
            )
        }

        return try {
            val process = ProcessBuilder(executable.absolutePath)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result(EXIT_TIMEOUT, "$output\n(the graphics probe did not finish in time)")
            } else {
                Result(process.exitValue(), output)
            }
        } catch (error: Exception) {
            Result(EXIT_FAILED, "Could not start the graphics probe: ${error.message}")
        }
    }

    private fun value(output: String, key: String): String? =
        output.lineSequence()
            .firstOrNull { it.startsWith(key) }
            ?.substringAfter(key)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Exit codes the runner itself uses when the probe never really ran. */
    const val EXIT_MISSING = -1
    const val EXIT_TIMEOUT = -2
    const val EXIT_FAILED = -3

    data class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val isSuccessful: Boolean get() = isSuccessful(exitCode, output)
    }
}
