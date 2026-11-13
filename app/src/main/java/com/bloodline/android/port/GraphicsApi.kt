package com.bloodline.android.port

/**
 * The renderer the port is configured to use.
 *
 * **This encoding is inferred, and the inference is recorded rather than
 * hidden.** The port stores `ui.gfxApi` as a number in its settings file and
 * its APK ships two interchangeable D3D9 backends — `libdxvk_d3d9.so` (Vulkan)
 * and `libd3d9_gles.so` (its own OpenGL ES translator) — with a note in its UI
 * that says Vulkan is preferred where the phone supports it and OpenGL ES is
 * the fallback. The observed value on a Mali device is `0`, and the port's own
 * DXVK log proves Vulkan is what actually loaded there.
 *
 * So `0` = Vulkan is measured, and [OPENGL_ES] = `1` is the only other value
 * the control can take. Nothing here claims more than that, and [value] is
 * written back exactly as the port stores it.
 */
enum class GraphicsApi(
    /** The number the port keeps in `ui.gfxApi`. */
    val value: Double,
    val title: String,
    val detail: String,
) {

    /** Direct3D 9 translated to Vulkan by DXVK. */
    VULKAN(
        value = 0.0,
        title = "Vulkan (DXVK)",
        detail = "The port's faster path where the driver implements what DXVK needs. " +
            "On this device the stock Arm driver reports Vulkan 1.1 and DXVK disables " +
            "several features it asks for; a PanVK driver raises that ceiling.",
    ),

    /** Direct3D 9 translated to OpenGL ES by the port's own renderer. */
    OPENGL_ES(
        value = 1.0,
        title = "OpenGL ES",
        detail = "The port's own Direct3D 9 to GLES translator, meant for phones without " +
            "a usable Vulkan driver. It asks far less of the driver, which can make it " +
            "the faster of the two when Vulkan is a weak implementation.",
    ),
    ;

    companion object {

        /**
         * Reads the stored value.
         *
         * Null means "the port has not written it", which is not the same as
         * either renderer: guessing there would overwrite the port's own
         * first-run choice.
         */
        fun fromValue(value: Double?): GraphicsApi? = when (value) {
            null -> null
            VULKAN.value -> VULKAN
            OPENGL_ES.value -> OPENGL_ES
            else -> null
        }
    }
}

/** The frame-cap choices the port's own control offers, in its order. */
object FrameLimit {

    /**
     * Values exposed by the port's "FPS limit" control.
     *
     * `0` is "unlimited". The list is ordered as the control presents it, so a
     * preset can be expressed as an index into this array.
     */
    val CHOICES: List<Int> = listOf(0, 30, 40, 50, 60)

    /** The largest cap at or below [fps], or the lowest choice when none fits. */
    fun atMost(fps: Int): Int =
        CHOICES.filter { it in 1..fps }.maxOrNull() ?: CHOICES.first { it > 0 }
}

/**
 * The render heights the port's "Render resolution" control offers.
 *
 * Read from the port's own UI strings: `Native`, `480p`, `720p`, `900p`,
 * `1080p`. The port stores the height in `render_h`, with `0` meaning native —
 * which is the value that hurts a 600x1333 phone panel, because it asks the
 * game to draw 1333 rows for a GPU that is already the bottleneck.
 */
object RenderHeight {

    /** `0` is the port's "Native": draw at the screen's own height. */
    const val NATIVE = 0

    val CHOICES: List<Int> = listOf(NATIVE, 480, 720, 900, 1080)

    /**
     * The tallest setting that is still no more than [screenHeight], never
     * above 720 by default.
     *
     * 720 is the ceiling because this is the setting that trades sharpness for
     * frame rate, and on a Mali-G52 the sharpness is not what anyone is
     * missing: the panel is ~233 dpi at 600x1333, so a 720-row render is
     * already close to what the eye resolves in motion, while being roughly
     * half the pixels of native.
     */
    fun recommendedFor(screenHeight: Int, ceiling: Int = 720): Int {
        if (screenHeight <= 0) return 480
        val usable = CHOICES.filter { it in 1..minOf(screenHeight, ceiling) }
        return usable.maxOrNull() ?: 480
    }
}
