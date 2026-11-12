package com.bloodline.android.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolution arithmetic, checked against a real device profile.
 *
 * [TECNO] is TECNO CK6: a 720 × 1600 panel at 60 Hz. Every expectation below
 * is worked out from those numbers rather than from a round figure, because the
 * whole point of the display settings is that they follow what the device
 * actually has.
 */
class ResolutionCalculatorTest {

    private val tecno = DisplayInfo(widthPx = 720, heightPx = 1600, refreshRateHz = 60f, densityDpi = 320)

    // ---------------------------------------------------------- measurement

    @Test
    fun `the screen is described in landscape because the game is landscape`() {
        assertEquals(Size(1600, 720), tecno.landscapeBounds)
    }

    @Test
    fun `a measured display describes itself with its refresh rate`() {
        assertEquals("720 × 1600 · 60 Hz", tecno.describe())
    }

    @Test
    fun `an unmeasured display describes itself as nothing`() {
        assertEquals("", DisplayInfo(0, 0, 0f, 0).describe())
        assertFalse(DisplayCapabilities.isUsable(DisplayInfo(0, 0, 0f, 0)))
    }

    // ------------------------------------------------------ presented size

    @Test
    fun `native follows the measured display`() {
        val (size, reduced) = ResolutionCalculator.presentedSize(
            tecno, RenderResolution.NATIVE, Size(0, 0),
        )

        assertEquals(Size(1600, 720), size)
        assertFalse(reduced)
    }

    @Test
    fun `a fixed preset is used exactly when the screen can show it`() {
        val (size, reduced) = ResolutionCalculator.presentedSize(
            tecno, RenderResolution.RES_1280_720, Size(0, 0),
        )

        assertEquals(Size(1280, 720), size)
        assertFalse(reduced)
    }

    @Test
    fun `a preset taller than the screen is scaled down and flagged`() {
        // 1600 × 900 does not fit a 1600 × 720 landscape panel.
        val (size, reduced) = ResolutionCalculator.presentedSize(
            tecno, RenderResolution.RES_1600_900, Size(0, 0),
        )

        // Same aspect ratio, shrunk until the height fits.
        assertEquals(Size(1280, 720), size)
        assertTrue(reduced)
    }

    @Test
    fun `an unsupported resolution is never handed to the GPU`() {
        // A 4K screen: this phone is not one, but the rule must still hold.
        val small = DisplayInfo(widthPx = 480, heightPx = 800, refreshRateHz = 60f, densityDpi = 240)
        val (size, reduced) = ResolutionCalculator.presentedSize(
            small, RenderResolution.RES_1600_900, Size(0, 0),
        )

        assertTrue(size.width <= 800)
        assertTrue(size.height <= 480)
        assertTrue(reduced)
    }

    // -------------------------------------------------------- render size

    @Test
    fun `render size applies the scaling percentage`() {
        assertEquals(
            Size(960, 540),
            ResolutionCalculator.renderSize(Size(1280, 720), 75),
        )
    }

    @Test
    fun `render size keeps both dimensions even`() {
        // 1280 × 720 at 85% is 1088 × 612: still even, so no fix-up needed.
        assertEquals(
            Size(1088, 612),
            ResolutionCalculator.renderSize(Size(1280, 720), 85),
        )
    }

    @Test
    fun `the scaling ladder covers every step the UI offers`() {
        ResolutionScale.PRESETS.forEach { scale ->
            val rendered = ResolutionCalculator.renderSize(Size(1600, 720), scale.percent)

            assertTrue(
                "${scale.percent}% produced $rendered",
                rendered.width <= 1600 && rendered.height <= 720,
            )
            assertEquals(0, rendered.width % 2)
            assertEquals(0, rendered.height % 2)
        }
    }

    @Test
    fun `render size never falls below the smallest usable target`() {
        assertEquals(
            Size(160, 90),
            ResolutionCalculator.renderSize(Size(320, 240), 25),
        )
    }

    @Test
    fun `a scale outside the allowed range is clamped, not trusted`() {
        assertEquals(
            ResolutionCalculator.renderSize(Size(1280, 720), 100),
            ResolutionCalculator.renderSize(Size(1280, 720), 500),
        )
        assertEquals(
            ResolutionCalculator.renderSize(Size(1280, 720), 25),
            ResolutionCalculator.renderSize(Size(1280, 720), 1),
        )
    }

    // --------------------------------------------------------- custom size

    @Test
    fun `a custom size is clamped to bounds the GPU can allocate`() {
        assertEquals(Size(320, 240), ResolutionCalculator.clampCustom(Size(8, 8)))
        assertEquals(Size(3840, 2160), ResolutionCalculator.clampCustom(Size(99999, 99999)))
    }

    @Test
    fun `a custom size is made even`() {
        assertEquals(Size(1280, 720), ResolutionCalculator.clampCustom(Size(1281, 721)))
    }

    // ----------------------------------------------------------- the plan

    @Test
    fun `a plan combines the presented size and the scaling`() {
        val plan = ResolutionCalculator.plan(
            display = tecno,
            resolution = RenderResolution.RES_1280_720,
            custom = Size(0, 0),
            scalePercent = 50,
        )

        assertEquals(Size(1280, 720), plan.presented)
        assertEquals(Size(640, 360), plan.rendered)
        assertEquals(50, plan.scalePercent)
        assertFalse(plan.reducedToFit)
    }

    @Test
    fun `a plan says when the chosen resolution had to be reduced`() {
        val plan = ResolutionCalculator.plan(
            display = tecno,
            resolution = RenderResolution.RES_1600_900,
            custom = Size(0, 0),
            scalePercent = 100,
        )

        assertTrue(plan.reducedToFit)
        assertEquals(Size(1280, 720), plan.presented)
    }

    @Test
    fun `a plan from a custom resolution uses the stored custom size`() {
        val plan = ResolutionCalculator.plan(
            display = tecno,
            resolution = RenderResolution.CUSTOM,
            custom = Size(960, 540),
            scalePercent = 100,
        )

        assertEquals(Size(960, 540), plan.presented)
    }

    // -------------------------------------------------------- presentation

    @Test
    fun `stretching fills the display on both axes and leaves no bars`() {
        val presentation = ResolutionCalculator.presentation(
            display = tecno,
            frame = Size(1280, 720),
            aspect = AspectMode.STRETCH,
            letterbox = true,
            fullscreenScaling = true,
        )

        assertEquals(1600f / 1280f, presentation.scaleX, 0.001f)
        assertEquals(1f, presentation.scaleY, 0.001f)
        assertEquals(BarMode.NONE, presentation.bars)
    }

    @Test
    fun `preserving with letterbox fits the whole frame and bars the sides`() {
        val presentation = ResolutionCalculator.presentation(
            display = tecno,
            frame = Size(1280, 720),
            aspect = AspectMode.PRESERVE,
            letterbox = true,
            fullscreenScaling = true,
        )

        assertEquals(1f, presentation.scaleX, 0.001f)
        assertEquals(BarMode.PILLARBOX, presentation.bars)
        assertFalse(presentation.cropped)
    }

    @Test
    fun `preserving without letterbox covers the display and crops the frame`() {
        val presentation = ResolutionCalculator.presentation(
            display = tecno,
            frame = Size(1280, 720),
            aspect = AspectMode.PRESERVE,
            letterbox = false,
            fullscreenScaling = true,
        )

        assertEquals(1600f / 1280f, presentation.scaleX, 0.001f)
        assertEquals(BarMode.NONE, presentation.bars)
        assertTrue(presentation.cropped)
    }

    @Test
    fun `fitting never crops, whatever else is set`() {
        AspectMode.entries.forEach { aspect ->
            if (aspect == AspectMode.STRETCH) return@forEach
            val presentation = ResolutionCalculator.presentation(
                display = tecno,
                frame = Size(1280, 720),
                aspect = aspect,
                letterbox = true,
                fullscreenScaling = true,
            )
            assertFalse("$aspect cropped the frame", presentation.cropped)
        }
    }

    @Test
    fun `turning fullscreen scaling off shows a small frame one to one`() {
        val presentation = ResolutionCalculator.presentation(
            display = tecno,
            frame = Size(800, 600),
            aspect = AspectMode.FIT,
            letterbox = true,
            fullscreenScaling = false,
        )

        assertEquals(1f, presentation.scaleX, 0.001f)
        assertEquals(BarMode.PILLARBOX, presentation.bars)
    }

    @Test
    fun `a frame wider than the display letterboxes top and bottom`() {
        // 1600 × 600 is wider than the panel's 2.22:1, so the width binds and
        // the leftover height becomes bars above and below.
        val presentation = ResolutionCalculator.presentation(
            display = tecno,
            frame = Size(1600, 600),
            aspect = AspectMode.FIT,
            letterbox = true,
            fullscreenScaling = true,
        )

        assertEquals(1f, presentation.scaleX, 0.001f)
        assertEquals(BarMode.LETTERBOX, presentation.bars)
    }

    @Test
    fun `a frame taller than the display pillarboxes the sides`() {
        // The reverse case: the height binds, so the bars go down the sides.
        val presentation = ResolutionCalculator.presentation(
            display = tecno,
            frame = Size(1600, 1200),
            aspect = AspectMode.FIT,
            letterbox = true,
            fullscreenScaling = true,
        )

        assertEquals(0.6f, presentation.scaleX, 0.001f)
        assertEquals(BarMode.PILLARBOX, presentation.bars)
    }

    // --------------------------------------------------------- enum lookups

    @Test
    fun `resolution presets are looked up by exact size`() {
        assertEquals(RenderResolution.RES_960_540, RenderResolution.fromSize(960, 540))
        assertEquals(null, RenderResolution.fromSize(961, 540))
        assertEquals(null, RenderResolution.fromSize(0, 0))
    }

    @Test
    fun `every resolution the UI offers is a real preset or Custom`() {
        val offered = RenderResolution.PRESETS + RenderResolution.CUSTOM

        assertEquals(7, offered.size)
        assertTrue(offered.contains(RenderResolution.NATIVE))
        assertTrue(offered.contains(RenderResolution.RES_1600_900))
    }

    @Test
    fun `every scaling step the UI offers is between a half and full size`() {
        ResolutionScale.PRESETS.forEach { scale ->
            assertTrue(
                "${scale.percent}% is outside the offered range",
                scale.percent in ResolutionScale.MIN_PERCENT..ResolutionScale.MAX_PERCENT,
            )
        }
        assertEquals(8, ResolutionScale.PRESETS.size)
    }
}
