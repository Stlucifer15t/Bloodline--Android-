package com.bloodline.android.settings

import com.bloodline.android.display.AspectMode
import com.bloodline.android.display.RenderResolution
import com.bloodline.android.display.ResolutionScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings are persisted, so they must survive a restart and must never be
 * readable as a value the code does not understand.
 */
class SettingsRepositoryTest {

    private val store = InMemorySettingsStore()
    private val settings = SettingsRepository(store)

    @Test
    fun `defaults are the documented safe values`() {
        assertTrue(settings.immersiveMode)
        assertTrue(settings.keepScreenAwake)
        assertEquals(UiScale.NORMAL, settings.uiScale)
        assertEquals(SettingsRepository.BRIGHTNESS_FOLLOW_SYSTEM, settings.brightnessPercent)
        assertEquals(FpsLimit.UNCAPPED, settings.fpsLimit)
        assertFalse(settings.fpsOverlayEnabled)
        assertEquals(RenderResolution.NATIVE, settings.displayResolution)
        assertEquals(ResolutionScale.FULL, settings.renderScale)
        assertEquals(AspectMode.PRESERVE, settings.aspectMode)
        assertEquals(RendererChoice.WINE_D3D_VIRGL, settings.renderer)
        assertEquals(AudioBackend.AUTO, settings.audioBackend)
        assertEquals(-1, settings.masterVolume)
    }

    @Test
    fun `booleans survive a new repository over the same store`() {
        settings.immersiveMode = false
        settings.keepScreenAwake = false

        val reloaded = SettingsRepository(store)

        assertFalse(reloaded.immersiveMode)
        assertFalse(reloaded.keepScreenAwake)
    }

    @Test
    fun `enums survive a new repository over the same store`() {
        settings.fpsLimit = FpsLimit.FPS_30
        settings.renderScale = ResolutionScale.HALF
        settings.aspectMode = AspectMode.STRETCH
        settings.displayResolution = RenderResolution.RES_960_540

        val reloaded = SettingsRepository(store)

        assertEquals(FpsLimit.FPS_30, reloaded.fpsLimit)
        assertEquals(ResolutionScale.HALF, reloaded.renderScale)
        assertEquals(AspectMode.STRETCH, reloaded.aspectMode)
        assertEquals(RenderResolution.RES_960_540, reloaded.displayResolution)
    }

    @Test
    fun `a corrupted stored enum falls back to the default`() {
        store.putString(SettingsRepository.KEY_FPS_LIMIT, "not-an-enum")
        store.putString(SettingsRepository.KEY_RENDERER, "GLIDE")

        assertEquals(FpsLimit.UNCAPPED, settings.fpsLimit)
        assertEquals(RendererChoice.WINE_D3D_VIRGL, settings.renderer)
    }

    @Test
    fun `brightness is clamped to the allowed range`() {
        settings.brightnessPercent = 1000
        assertEquals(100, settings.brightnessPercent)

        settings.brightnessPercent = -50
        assertEquals(SettingsRepository.BRIGHTNESS_FOLLOW_SYSTEM, settings.brightnessPercent)
    }

    @Test
    fun `volume is clamped and never negative`() {
        settings.masterVolume = 250
        assertEquals(100, settings.masterVolume)

        settings.masterVolume = -20
        assertEquals(0, settings.masterVolume)
    }

    @Test
    fun `master volume is seeded from the device only once`() {
        settings.initialiseMasterVolume(65)
        assertEquals(65, settings.masterVolume)

        settings.masterVolume = 20
        settings.initialiseMasterVolume(90)
        assertEquals(20, settings.masterVolume)
    }

    @Test
    fun `listeners are told which key changed`() {
        val changes = mutableListOf<String>()
        settings.addListener { changes += it }

        settings.fpsLimit = FpsLimit.FPS_45
        settings.immersiveMode = false

        assertEquals(
            listOf(SettingsRepository.KEY_FPS_LIMIT, SettingsRepository.KEY_IMMERSIVE),
            changes,
        )
    }

    @Test
    fun `a removed listener is not called again`() {
        var calls = 0
        val listener: (String) -> Unit = { calls++ }

        settings.addListener(listener)
        settings.fpsOverlayEnabled = true
        settings.removeListener(listener)
        settings.fpsOverlayEnabled = false

        assertEquals(1, calls)
    }

    @Test
    fun `reset removes every stored value`() {
        settings.fpsLimit = FpsLimit.FPS_20
        settings.immersiveMode = false
        settings.brightnessPercent = 50

        settings.resetToDefaults()

        assertFalse(store.contains(SettingsRepository.KEY_FPS_LIMIT))
        assertFalse(store.contains(SettingsRepository.KEY_IMMERSIVE))
        assertFalse(store.contains(SettingsRepository.KEY_BRIGHTNESS))
        assertEquals(FpsLimit.UNCAPPED, settings.fpsLimit)
    }

    @Test
    fun `snapshot reports every setting for the diagnostics report`() {
        val snapshot = settings.snapshot()

        listOf(
            "display.resolution",
            "display.customSize",
            "display.renderScale",
            "display.aspectMode",
            "display.letterbox",
            "display.fullscreenScaling",
            "display.immersive",
            "display.keepAwake",
            "display.uiScale",
            "display.brightness",
            "performance.fpsLimit",
            "performance.vsync",
            "performance.framePacing",
            "performance.fpsOverlay",
            "performance.preset",
            "graphics.renderer",
            "audio.masterVolume",
            "audio.backend",
        ).forEach { key ->
            assertTrue("missing $key", snapshot.containsKey(key))
        }
    }

    @Test
    fun `the experimental renderer is never the default`() {
        assertFalse(settings.renderer.isExperimental)
        assertTrue(RendererChoice.PANVK_DXVK.isExperimental)
    }

    // --------------------------------------------------------------- display

    @Test
    fun `a custom display size is clamped to a size a GPU can allocate`() {
        settings.customDisplayWidth = 8
        settings.customDisplayHeight = 8
        assertEquals(RenderResolution.MIN_WIDTH, settings.customDisplayWidth)
        assertEquals(RenderResolution.MIN_HEIGHT, settings.customDisplayHeight)

        settings.customDisplayWidth = 99999
        settings.customDisplayHeight = 99999
        assertEquals(RenderResolution.MAX_WIDTH, settings.customDisplayWidth)
        assertEquals(RenderResolution.MAX_HEIGHT, settings.customDisplayHeight)
    }

    @Test
    fun `a stored custom size outside the range is clamped on read too`() {
        store.putInt(SettingsRepository.KEY_CUSTOM_WIDTH, 1)
        store.putInt(SettingsRepository.KEY_CUSTOM_HEIGHT, 99999)

        assertEquals(RenderResolution.MIN_WIDTH, settings.customDisplayWidth)
        assertEquals(RenderResolution.MAX_HEIGHT, settings.customDisplayHeight)
    }

    @Test
    fun `the effective scale resolves the Custom case`() {
        assertEquals(100, settings.effectiveScalePercent())

        settings.renderScale = ResolutionScale.P75
        assertEquals(75, settings.effectiveScalePercent())

        settings.renderScale = ResolutionScale.CUSTOM
        settings.customScalePercent = 55
        assertEquals(55, settings.effectiveScalePercent())
    }

    @Test
    fun `a custom scale is clamped to the range the renderer accepts`() {
        settings.customScalePercent = 5
        assertEquals(ResolutionScale.MIN_PERCENT, settings.customScalePercent)

        settings.customScalePercent = 900
        assertEquals(ResolutionScale.MAX_PERCENT, settings.customScalePercent)
    }

    @Test
    fun `resetting graphics leaves the rest of the settings alone`() {
        settings.displayResolution = RenderResolution.RES_854_480
        settings.renderScale = ResolutionScale.HALF
        settings.aspectMode = AspectMode.STRETCH
        settings.letterboxEnabled = false
        settings.fullscreenScaling = false
        settings.renderer = RendererChoice.PANVK_DXVK
        settings.immersiveMode = false
        settings.fpsLimit = FpsLimit.FPS_30

        settings.resetGraphics()

        assertEquals(RenderResolution.NATIVE, settings.displayResolution)
        assertEquals(ResolutionScale.FULL, settings.renderScale)
        assertEquals(AspectMode.PRESERVE, settings.aspectMode)
        assertTrue(settings.letterboxEnabled)
        assertTrue(settings.fullscreenScaling)
        assertFalse(settings.renderer.isExperimental)

        // Untouched by a graphics reset.
        assertFalse(settings.immersiveMode)
        assertEquals(FpsLimit.FPS_30, settings.fpsLimit)
    }

    @Test
    fun `reset clears the retired orientation key as well as the live ones`() {
        store.putString("display.orientation", "LANDSCAPE")
        store.putString("display.native_resolution", "false")

        settings.resetToDefaults()

        assertFalse(store.contains("display.orientation"))
        assertFalse(store.contains("display.native_resolution"))
    }
}

