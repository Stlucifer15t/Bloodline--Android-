package com.bloodline.android.display

import kotlin.math.min
import kotlin.math.roundToInt

/** What the current display and resolution choices add up to. */
data class RenderPlan(
    /** Size the game surface is presented at: what the Windows desktop is told. */
    val presented: Size,

    /** Size the game actually renders at: [presented] × [scalePercent]. */
    val rendered: Size,

    /** Scaling in effect, after clamping. */
    val scalePercent: Int,

    /**
     * True when the requested resolution was larger than this screen can show
     * and has been reduced to fit. Reported rather than hidden: the user asked
     * for something the display cannot do, and should be told.
     */
    val reducedToFit: Boolean,
)

/** How a finished frame is placed on the display. */
data class Presentation(
    val scaleX: Float,
    val scaleY: Float,
    val bars: BarMode,

    /** True when part of the frame falls outside the display. */
    val cropped: Boolean,
)

/**
 * All of the resolution arithmetic, kept free of Android so it can be tested
 * on the JVM.
 *
 * Two rules run through everything here:
 *
 * 1. **Nothing is invented.** A resolution is only ever what the display
 *    reported ([DisplayInfo]) scaled by a stored percentage. There is no
 *    "supports 1080p" guess.
 * 2. **Nothing impossible is requested.** If the chosen resolution is larger
 *    than the screen, it is scaled down to fit and [RenderPlan.reducedToFit]
 *    says so, instead of handing the graphics stack a surface it cannot
 *    present.
 */
object ResolutionCalculator {

    /** The smallest internal render size worth producing. */
    private val MIN_RENDER = Size(160, 90)

    /**
     * The size the game surface is presented at.
     *
     * [RenderResolution.NATIVE] follows the display. A fixed preset is reduced
     * only if it does not fit; a custom size is first clamped to sane bounds.
     */
    fun presentedSize(
        display: DisplayInfo,
        resolution: RenderResolution,
        custom: Size,
    ): Pair<Size, Boolean> {
        val bounds = display.landscapeBounds
        val wanted = when {
            resolution.isCustom -> clampCustom(custom)
            resolution == RenderResolution.NATIVE -> bounds
            else -> Size(resolution.width, resolution.height)
        }

        val fits = wanted.width <= bounds.width && wanted.height <= bounds.height
        if (fits) return wanted to false

        // Larger than the screen: keep the aspect ratio, shrink until it fits.
        val factor = min(
            bounds.width.toFloat() / wanted.width,
            bounds.height.toFloat() / wanted.height,
        )
        return scaleEven(wanted, factor).coerceAtLeast(MIN_RENDER) to true
    }

    /** Internal render size: the presented size multiplied by the scaling. */
    fun renderSize(presented: Size, scalePercent: Int): Size {
        val percent = scalePercent.coerceIn(
            ResolutionScale.MIN_PERCENT,
            ResolutionScale.MAX_PERCENT,
        )
        return scaleEven(presented, percent / 100f).coerceAtLeast(MIN_RENDER)
    }

    /** Everything at once, which is what the UI needs to describe a choice. */
    fun plan(
        display: DisplayInfo,
        resolution: RenderResolution,
        custom: Size,
        scalePercent: Int,
    ): RenderPlan {
        val (presented, reduced) = presentedSize(display, resolution, custom)
        return RenderPlan(
            presented = presented,
            rendered = renderSize(presented, scalePercent),
            scalePercent = scalePercent.coerceIn(
                ResolutionScale.MIN_PERCENT,
                ResolutionScale.MAX_PERCENT,
            ),
            reducedToFit = reduced,
        )
    }

    /**
     * Brings a user typed size back into a range the GPU can actually allocate.
     *
     * Both dimensions are made even: an odd width costs Mali an extra padding
     * row on every texture upload.
     */
    fun clampCustom(size: Size): Size = Size(
        width = size.width
            .coerceIn(RenderResolution.MIN_WIDTH, RenderResolution.MAX_WIDTH) / 2 * 2,
        height = size.height
            .coerceIn(RenderResolution.MIN_HEIGHT, RenderResolution.MAX_HEIGHT) / 2 * 2,
    )

    /**
     * Where the frame lands on the display.
     *
     * `letterbox` decides whether a preserved frame is fitted inside the screen
     * (black bars) or scaled to cover it (edges cropped). `fullscreenScaling`
     * decides whether a frame smaller than the display is blown up to fill it,
     * or shown 1:1 and centred.
     */
    fun presentation(
        display: DisplayInfo,
        frame: Size,
        aspect: AspectMode,
        letterbox: Boolean,
        fullscreenScaling: Boolean,
    ): Presentation {
        val bounds = display.landscapeBounds
        if (frame.width <= 0 || frame.height <= 0) {
            return Presentation(1f, 1f, BarMode.NONE, cropped = false)
        }

        val fitX = bounds.width.toFloat() / frame.width
        val fitY = bounds.height.toFloat() / frame.height

        if (aspect == AspectMode.STRETCH) {
            // Fill the display on both axes; the frame is distorted if the
            // shapes differ, which is exactly what the label promises.
            return Presentation(fitX, fitY, BarMode.NONE, cropped = false)
        }

        val uniform = if (aspect == AspectMode.PRESERVE && !letterbox) {
            maxOf(fitX, fitY) // cover: no bars, edges cropped
        } else {
            minOf(fitX, fitY) // contain: whole frame visible
        }
        val scale = if (fullscreenScaling) uniform else minOf(uniform, 1f)

        val drawnWidth = frame.width * scale
        val drawnHeight = frame.height * scale
        val bars = when {
            drawnWidth < bounds.width - 0.5f -> BarMode.PILLARBOX
            drawnHeight < bounds.height - 0.5f -> BarMode.LETTERBOX
            else -> BarMode.NONE
        }

        return Presentation(
            scaleX = scale,
            scaleY = scale,
            bars = bars,
            cropped = drawnWidth > bounds.width + 0.5f || drawnHeight > bounds.height + 0.5f,
        )
    }

    private fun scaleEven(size: Size, factor: Float): Size = Size(
        width = ((size.width * factor).roundToInt() / 2 * 2).coerceAtLeast(2),
        height = ((size.height * factor).roundToInt() / 2 * 2).coerceAtLeast(2),
    )

    private fun Size.coerceAtLeast(minimum: Size): Size = Size(
        width = width.coerceAtLeast(minimum.width),
        height = height.coerceAtLeast(minimum.height),
    )
}
