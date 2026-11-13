package com.bloodline.android.port

/**
 * The port's `port_settings.txt`, read and written without losing anything.
 *
 * The format is one `key=value` per line, and it is **not** this app's file:
 * the port writes it itself, adds keys between releases, and drops any line it
 * does not understand. Two consequences shape this class:
 *
 * 1. **Unknown keys are preserved verbatim.** A model that kept only the keys
 *    it knew would delete the port's controller bindings (`ui.padBind12=…`)
 *    the first time anything was saved — which is the classic way a
 *    "settings app" quietly breaks someone's configuration.
 * 2. **Order is preserved.** The port writes a stable order; keeping it makes
 *    a diff between two files readable when something goes wrong.
 *
 * Nothing here touches the filesystem: parsing and rendering are pure, so the
 * whole of it is unit testable on the JVM.
 */
class PortSettings private constructor(
    private val lines: MutableList<Line>,
) {

    /** One line: a key/value pair, or verbatim text (blank, comment) with no key. */
    private sealed interface Line {
        data class Entry(val key: String, var value: String) : Line
        data class Raw(val text: String) : Line
    }

    /** Every key present, in file order. Useful for a diff in a report. */
    fun keys(): List<String> = lines.filterIsInstance<Line.Entry>().map { it.key }

    /** The value of [key], or null when the file does not carry it. */
    fun get(key: String): String? =
        lines.filterIsInstance<Line.Entry>().firstOrNull { it.key == key }?.value

    /**
     * Sets [key].
     *
     * An existing key keeps its position; a new one is appended, because
     * inserting it in the middle would move every following line and make the
     * port's own next write produce a large, confusing diff.
     */
    fun set(key: String, value: String): PortSettings {
        val existing = lines.filterIsInstance<Line.Entry>().firstOrNull { it.key == key }
        if (existing != null) {
            existing.value = value
        } else {
            lines += Line.Entry(key, value)
        }
        return this
    }

    /** Removes [key] entirely, for a setting that should go back to the default. */
    fun remove(key: String): PortSettings {
        lines.removeAll { it is Line.Entry && it.key == key }
        return this
    }

    /** The exact bytes to write back. */
    fun render(): String = lines.joinToString("\n") { line ->
        when (line) {
            is Line.Entry -> "${line.key}=${line.value}"
            is Line.Raw -> line.text
        }
    }

    /** The typed view this app actually reasons about. */
    fun snapshot(): PortSettingsSnapshot = PortSettingsSnapshot(
        renderHeight = get(KEY_RENDER_HEIGHT)?.toIntOrNull(),
        frameLimit = get(KEY_FRAME_LIMIT)?.toIntOrNull(),
        renderScale = get(KEY_RENDER_SCALE)?.toDoubleOrNull(),
        graphicsApi = get(KEY_GRAPHICS_API)?.toDoubleOrNull(),
        imageQualityPreset = get(KEY_IMAGE_PRESET)?.toDoubleOrNull(),
        frameLimitPreset = get(KEY_FPS_PRESET)?.toDoubleOrNull(),
    )

    companion object {

        const val KEY_RENDER_HEIGHT = "render_h"
        const val KEY_FRAME_LIMIT = "fps_limit"
        const val KEY_RENDER_SCALE = "ui.renderRes"
        const val KEY_GRAPHICS_API = "ui.gfxApi"
        const val KEY_IMAGE_PRESET = "ui.gfxPreset"
        const val KEY_FPS_PRESET = "ui.fpsLimit"

        /**
         * Parses [text].
         *
         * A line without `=` is kept as raw text rather than dropped: the port
         * does not write comments, but a user editing the file by hand might,
         * and silently deleting their note is worse than preserving it.
         */
        fun parse(text: String): PortSettings {
            val lines = mutableListOf<Line>()
            // Normalise CRLF first: the file may have been touched by an editor
            // that adds it, and a stray \r would end up inside a value.
            text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
                if (raw.isEmpty()) {
                    lines += Line.Raw(raw)
                    return@forEach
                }
                val separator = raw.indexOf('=')
                if (separator <= 0) {
                    lines += Line.Raw(raw)
                } else {
                    lines += Line.Entry(
                        key = raw.substring(0, separator),
                        value = raw.substring(separator + 1),
                    )
                }
            }
            // A trailing newline produces one empty Raw line; keep it only if
            // the input really ended with one.
            return PortSettings(lines)
        }

        /** An empty document, for a file that does not exist yet. */
        fun empty(): PortSettings = PortSettings(mutableListOf())
    }
}

/**
 * The settings this app reasons about, read from [PortSettings].
 *
 * Every field is nullable on purpose: a missing key means "the port is using
 * its own default", which is different from "the value is zero" and must not
 * be written back as one.
 */
data class PortSettingsSnapshot(
    /** Screen height the game renders at, or 0/native. */
    val renderHeight: Int?,

    /** Frame cap in frames per second. */
    val frameLimit: Int?,

    /** Resolution multiplier, as the port stores it. */
    val renderScale: Double?,

    /** Which graphics API the port uses. See [GraphicsApi]. */
    val graphicsApi: Double?,

    /** Preset index for image quality. */
    val imageQualityPreset: Double?,

    /** Preset index for the frame limit control. */
    val frameLimitPreset: Double?,
) {
    /** Which renderer this configuration selects. */
    val renderer: GraphicsApi? get() = GraphicsApi.fromValue(graphicsApi)

    /** True when the game is asked to draw at the screen's own height. */
    val rendersAtNativeHeight: Boolean
        get() = renderHeight == null || renderHeight <= 0
}
