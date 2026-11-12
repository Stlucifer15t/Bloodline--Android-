package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.Requirement
import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.RuntimeModule

/**
 * How Windows audio reaches the phone's speakers.
 *
 * Bloodlines uses **DirectSound3D**. Wine turns that into ALSA (or PulseAudio)
 * inside the prefix; the bridge is the last hop, from the Linux sound API to an
 * Android one. Two host targets are worth having:
 *
 * * **[AAUDIO]** — lower latency, present since Android 8.0. Our `minSdk` is
 *   26, so it is guaranteed to exist on anything that can install this app.
 * * **[OPENSL_ES]** — broader device coverage, officially deprecated but still
 *   shipped everywhere, and occasionally the only one that behaves on a
 *   given vendor's HAL.
 *
 * [NONE] is a real and useful option: it starts the game with audio disabled so
 * that a sound-server crash cannot stop the rest of the runtime from being
 * tested.
 */
enum class AudioBridge(
    override val id: String,
    override val title: String,
    val summary: String,

    /** True when this is a real output path rather than a deliberate silence. */
    val producesSound: Boolean,
) : RuntimeModule {

    AAUDIO(
        id = "aaudio",
        title = "AAudio",
        summary = "Android's low-latency audio API, available on every device " +
            "this app can be installed on.",
        producesSound = true,
    ),

    OPENSL_ES(
        id = "opensles",
        title = "OpenSL ES",
        summary = "The older Android audio API. Deprecated, but broader " +
            "coverage of vendor HALs.",
        producesSound = true,
    ),

    NONE(
        id = "none",
        title = "No audio",
        summary = "Starts the game muted. Useful for testing the rest of the " +
            "runtime without an audio server in the way.",
        producesSound = false,
    ),
    ;

    override fun requirements(capabilities: RuntimeCapabilities): List<Requirement> =
        buildList {
            if (producesSound) {
                add(
                    Requirement(
                        "The audio bridge binary shipped in the APK",
                        satisfied = false,
                        measured = false,
                    ),
                )
            }
            // Even a muted start needs the process plumbing, which is why this
            // is not an empty list for NONE either.
            add(
                Requirement(
                    "A working process manager",
                    capabilities.processLimit == 0 || capabilities.processLimit >= MIN_PROCESSES,
                    measured = capabilities.measured,
                ),
            )
        }

    override fun status(capabilities: RuntimeCapabilities): IntegrationStatus {
        val requirements = requirements(capabilities)
        return when {
            requirements.all { it.satisfied } -> IntegrationStatus.READY
            requirements.any { it.satisfied } -> IntegrationStatus.PARTIAL
            else -> IntegrationStatus.NOT_INTEGRATED
        }
    }

    override fun blocker(capabilities: RuntimeCapabilities): String {
        val missing = requirements(capabilities).filter { !it.satisfied }
        if (missing.isEmpty()) return ""
        return "Waiting on: " + missing.joinToString(", ") { it.description }
    }

    companion object {

        /**
         * Wine spawns a lot of processes: a server, device services, and one
         * per Windows program. Android 12 and later kill an app's children past
         * a fixed cap, so a device that reports fewer than this is a real risk
         * rather than a theoretical one — the game would die mid-session with
         * no exit status to report.
         */
        const val MIN_PROCESSES = 32

        val ENTRIES: List<AudioBridge> get() = entries.toList()

        fun fromId(id: String): AudioBridge? = entries.firstOrNull { it.id == id }

        fun recommendedFor(capabilities: RuntimeCapabilities): AudioBridge =
            if (capabilities.processLimit > 0 && capabilities.processLimit < MIN_PROCESSES) {
                OPENSL_ES
            } else {
                AAUDIO
            }
    }
}
