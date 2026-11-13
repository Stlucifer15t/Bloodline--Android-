package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend

/**
 * What the user chose, and nothing else.
 *
 * `null` means "you decide", never "off": a null graphics backend means pick the
 * safest one this device supports, which is what keeps the experimental Vulkan
 * route from becoming the default by accident.
 *
 * Stored as IDs so the keys survive a rename, and so a persisted value from an
 * older build that names a backend which no longer exists degrades to "you
 * decide" rather than crashing.
 */
data class RuntimeConfiguration(
    val graphicsBackend: GraphicsBackend? = null,
    val cpuTranslator: CpuTranslator? = null,
    val audioBackend: AudioBridge? = null,

    /**
     * Whether the game's own window is stretched to fill the screen.
     *
     * The launcher already owns resolution scaling and aspect handling. This
     * only decides what the runtime asks Wine to do with a window whose shape
     * does not match the panel.
     */
    val stretchToFill: Boolean = false,

    /** Cap the game's frame rate, or null for uncapped. */
    val frameLimit: Int? = null,

    /**
     * `WINEDEBUG` for the guest, or null for [WineEnvironment.DEFAULT_WINE_DEBUG].
     *
     * Null means "the default", never "off". Silence is the one value that
     * cannot be recovered from: with Wine mute, a crash has no explanation
     * and the only thing left to report is an exit status.
     */
    val wineDebug: String? = null,
) {

    /** Settings that were explicitly chosen. Absent keys mean "you decide". */
    fun asMap(): Map<String, String> = buildMap {
        graphicsBackend?.let { put(KEY_GRAPHICS, it.id) }
        cpuTranslator?.let { put(KEY_CPU, it.id) }
        audioBackend?.let { put(KEY_AUDIO, it.id) }
        put(KEY_STRETCH, stretchToFill.toString())
        frameLimit?.let { put(KEY_FRAME_LIMIT, it.toString()) }
        wineDebug?.let { put(KEY_WINE_DEBUG, it) }
    }

    companion object {
        const val KEY_GRAPHICS = "graphics_backend"
        const val KEY_CPU = "cpu_translator"
        const val KEY_AUDIO = "audio_backend"
        const val KEY_STRETCH = "stretch_to_fill"
        const val KEY_FRAME_LIMIT = "frame_limit"
        const val KEY_WINE_DEBUG = "wine_debug"

        /** Reads back what [asMap] wrote. Unknown values become `null`. */
        fun fromMap(values: Map<String, String>): RuntimeConfiguration = RuntimeConfiguration(
            graphicsBackend = values[KEY_GRAPHICS]?.let { GraphicsBackend.fromId(it) },
            cpuTranslator = values[KEY_CPU]?.let { CpuTranslator.fromId(it) },
            audioBackend = values[KEY_AUDIO]?.let { AudioBridge.fromId(it) },
            stretchToFill = values[KEY_STRETCH].toBoolean(),
            frameLimit = values[KEY_FRAME_LIMIT]?.toIntOrNull(),
            wineDebug = values[KEY_WINE_DEBUG],
        )
    }
}
