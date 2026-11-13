package com.bloodline.android.runtime

import com.bloodline.android.BuildConfig
import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import com.bloodline.android.runtime.backends.GraphicsSelection

/**
 * Turns the runtime's state into the `runtime` section of a diagnostics report.
 *
 * The brief asks for specific things to be recorded: initialisation results,
 * the Wine version, the CPU translator, the graphics backend and driver, the GPU
 * renderer and the APIs it supports, how the process ended, and any errors. This
 * is the single place that decides what those lines look like, so a report is
 * the same whether it is produced after a crash or exported by hand.
 *
 * Two rules:
 *
 * 1. **Say what is absent.** A report that lists only the things that worked
 *    looks like a working system. Every component that is not present is
 *    written down as "not present", with the reason.
 * 2. **No sensitive data.** No game paths beyond the folder the user chose (and
 *    even that is the display path, not a full URI), no serial numbers, no
 *    account identifiers. What is recorded is what a bug report needs.
 */
object RuntimeDiagnostics {

    /** The section name as the report builder expects it. */
    const val SECTION = "runtime"

    /**
     * Builds the runtime section.
     *
     * @param wineVersion  the Wine build's own version string, or null when no
     *                     Wine is present — which is the case today, and is
     *                     written down as such rather than omitted.
     */
    fun section(
        capabilities: RuntimeCapabilities,
        graphics: GraphicsSelection,
        translator: CpuTranslator,
        audio: AudioBridge,
        configuration: RuntimeConfiguration,
        validation: InstallValidation?,
        wineVersion: String? = null,
        driverVersion: String? = null,
        phase: RuntimeManager.Phase? = null,
        exit: GameProcessManager.ExitResult? = null,
        errors: List<String> = emptyList(),
        initialisation: WineInitialiser.Report? = null,
    ): Map<String, String> = linkedMapOf<String, String>().apply {

        // --- what this device can do, all of it measured -------------------
        put("Device ABI", capabilities.primaryAbi.ifEmpty { "unknown" })
        put("32-bit userland", yesNo(capabilities.has32BitAbi))
        put("32-bit execution", yesNo(capabilities.canExecute32Bit))
        put("ptrace (needed for PRoot)", yesNo(capabilities.ptraceAllowed))
        put("Execute from memory", yesNo(capabilities.memfdExecAllowed))
        put(
            "Process limit",
            if (capabilities.processLimit > 0) "${capabilities.processLimit}" else "unknown",
        )
        put("GPU nodes", capabilities.gpuNodes.sorted().joinToString(", ").ifEmpty { "none found" })
        put("GPU renderer", capabilities.glRenderer.ifEmpty { "unknown" })
        put("OpenGL ES", capabilities.glesVersion.ifEmpty { "unknown" })
        put(
            "Vulkan device",
            capabilities.vulkanDevice ?: "none",
        )
        put(
            "Vulkan usable",
            if (capabilities.measured) yesNo(capabilities.vulkanUsable) else "not measured",
        )
        put("Measurements available", yesNo(capabilities.measured))

        // --- which build produced this report ------------------------------
        // First, because a report that cannot be tied to a build is a report
        // that can be argued with. Two pasted failures looked identical and
        // came from builds an entire fix apart; the version is what makes
        // that impossible to repeat.
        put("Launcher version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        // --- what the runtime chose, and why -------------------------------
        put("Graphics backend", graphics.backend?.title ?: "none available")
        put("Graphics reason", graphics.reason)
        put("Graphics fell back", yesNo(graphics.wasFallback))
        put("CPU translator", translator.title)
        put("Audio bridge", audio.title)
        put("Wine version", wineVersion ?: "not present")

        // --- configuration -------------------------------------------------
        put("Graphics preference", configuration.graphicsBackend?.title ?: "automatic")
        put("CPU preference", configuration.cpuTranslator?.title ?: "automatic")
        put("Audio preference", configuration.audioBackend?.title ?: "automatic")
        // What Wine is told to print. Recorded because a report saying "Wine
        // printed nothing" means opposite things depending on it: silence when
        // asked to speak is a finding, silence when told to be quiet is not.
        put("WINEDEBUG", configuration.wineDebug ?: WineEnvironment.DEFAULT_WINE_DEBUG)

        // --- the installation ---------------------------------------------
        when (validation) {
            is InstallValidation.Valid -> {
                put("Install", "validated")
                put("Install executable", validation.install.machine.description)
                put("Install subsystem", validation.install.subsystem?.description ?: "unknown")
            }

            is InstallValidation.Invalid -> {
                put("Install", "rejected")
                put("Install problem", validation.reason)
            }

            null -> put("Install", "not checked")
        }

        // --- how far Wine actually got --------------------------------------
        // One row per stage, because "the runtime is not ready" is not a
        // diagnosis. Which stage failed, and what Wine printed when it did, is.
        if (initialisation == null) {
            put("Wine", "not initialised")
        } else {
            initialisation.results.forEach { result ->
                put("Wine · ${result.stage.title.lowercase()}", result.describe())
            }
            if (initialisation.unverified.isNotEmpty()) {
                put(
                    "Wine · unverified",
                    initialisation.unverified.joinToString(", ") { it.title.lowercase() },
                )
            }
        }

        // --- how the run went ---------------------------------------------
        phase?.let { put("Last phase", describePhase(it)) }
        exit?.let {
            put("Exit reason", it.reason.name)
            it.exitCode?.let { code -> put("Exit code", code.toString()) }
            it.signal?.let { signal -> put("Exit signal", signal.toString()) }
            put("Session length", "${it.durationMillis / 1000}s")
        }
        if (errors.isNotEmpty()) {
            put("Errors", errors.joinToString(" | "))
        }
    }

    /** Human-readable summary of a lifecycle phase, for `last phase`. */
    fun describePhase(phase: RuntimeManager.Phase): String = when (phase) {
        is RuntimeManager.Phase.Idle -> "idle"
        is RuntimeManager.Phase.Validating -> "validating the installation"
        is RuntimeManager.Phase.Preparing -> "preparing the runtime"
        is RuntimeManager.Phase.Initialising ->
            "initialising ${phase.graphics.backend?.title ?: "no backend"} with ${phase.translator.title}"
        is RuntimeManager.Phase.Launching -> "starting the game process"
        is RuntimeManager.Phase.InGame -> "in game"
        is RuntimeManager.Phase.Restored -> "launcher restored (${phase.cause})"
    }

    /** A line per component, for the on-screen runtime list. */
    fun componentLines(
        capabilities: RuntimeCapabilities,
        graphics: GraphicsSelection,
        translator: CpuTranslator,
        audio: AudioBridge,
    ): List<String> = listOf(
        line("Wine", null, "not bundled in this build"),
        line(translator.title, translator.blocker(capabilities).ifEmpty { null }, null),
        line("Container (PRoot + glibc)", null, "not bundled in this build"),
        line(graphics.backend?.title ?: "Graphics", graphics.backend?.blocker(capabilities), null),
        line("${audio.title} bridge", audio.blocker(capabilities).ifEmpty { null }, null),
    )

    private fun line(name: String, blocker: String?, absent: String?): String = when {
        blocker != null -> "$name — $blocker"
        absent != null -> "$name — $absent"
        else -> "$name — ready"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
