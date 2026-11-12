package com.bloodline.android.display

/** A pixel size. */
data class Size(val width: Int, val height: Int) {

    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()

    override fun toString(): String = "${width} × $height"
}

/**
 * What the device actually reports about its screen.
 *
 * Every value is measured, never assumed: the launcher reads it from
 * [DisplayCapabilities] and shows it verbatim, so a resolution choice can be
 * judged against the real hardware (TECNO CK6: 720 × 1600, 60 Hz).
 */
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float,
    val densityDpi: Int,
) {
    /** The screen as it is right now, in the device's current orientation. */
    val size: Size get() = Size(widthPx, heightPx)

    /**
     * The screen as the game will see it.
     *
     * The game is a landscape title, so its width is the display's long edge
     * and its height the short one. Every "does this resolution fit?" question
     * is answered against these bounds.
     */
    val landscapeBounds: Size
        get() = Size(
            width = maxOf(widthPx, heightPx),
            height = minOf(widthPx, heightPx),
        )
}

/**
 * The size the game surface is **presented** at: the resolution the Windows
 * desktop inside the container is told to use.
 *
 * This is deliberately not the same thing as the internal render resolution,
 * which is this size multiplied by [ResolutionScale]. Keeping the two apart is
 * what makes "1600 × 900 presented, rendered at 75%" expressible.
 */
enum class RenderResolution(val width: Int, val height: Int, val isCustom: Boolean = false) {

    /** Follow the display: whatever Android reports for this screen. */
    NATIVE(0, 0),

    RES_640_360(640, 360),
    RES_854_480(854, 480),
    RES_960_540(960, 540),
    RES_1280_720(1280, 720),
    RES_1600_900(1600, 900),

    /** User supplied, stored in `display.custom_width` / `display.custom_height`. */
    CUSTOM(0, 0, isCustom = true),
    ;

    companion object {
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 3840
        const val MIN_HEIGHT = 240
        const val MAX_HEIGHT = 2160

        val PRESETS: List<RenderResolution> get() = RenderResolution.entries.filter { !it.isCustom }

        fun fromSize(width: Int, height: Int): RenderResolution? =
            RenderResolution.entries.firstOrNull {
                !it.isCustom && it.width == width && it.height == height
            }
    }
}

/**
 * Internal render resolution, as a percentage of the presented size.
 *
 * Rendering below the presented size and letting the GPU upscale is the single
 * cheapest way to make a Mali-G52 keep up, which is why the list is dense
 * between 100% and 50% rather than jumping straight to a quarter.
 */
enum class ResolutionScale(val percent: Int, val isCustom: Boolean = false) {
    FULL(100),
    P90(90),
    P85(85),
    P80(80),
    P75(75),
    P70(70),
    P60(60),
    HALF(50),
    CUSTOM(0, isCustom = true),
    ;

    companion object {
        const val MIN_PERCENT = 25
        const val MAX_PERCENT = 100

        val PRESETS: List<ResolutionScale> get() = ResolutionScale.entries.filter { !it.isCustom }

        fun fromPercent(percent: Int): ResolutionScale? =
            ResolutionScale.entries.firstOrNull { !it.isCustom && it.percent == percent }
    }
}

/** How a frame whose shape differs from the display is fitted to it. */
enum class AspectMode {
    /** Keep the frame's own aspect ratio. */
    PRESERVE,

    /** Scale so the whole frame is visible; never crop. */
    FIT,

    /** Fill the display on both axes, distorting the frame if the shapes differ. */
    STRETCH,
}

/** Which edges carry black bars. */
enum class BarMode { NONE, LETTERBOX, PILLARBOX }
