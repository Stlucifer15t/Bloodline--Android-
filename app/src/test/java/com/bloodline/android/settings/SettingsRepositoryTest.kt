package com.bloodline.android.settings

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
        assertEquals(ScreenOrientation.PORTRAIT, settings.screenOrientation)
        assertEquals(SettingsRepository.BRIGHTNESS_FOLLOW_SYSTEM, settings.brightnessPercent)
        assertEquals(FpsLimit.UNCAPPED, settings.fpsLimit)
        assertFalse(settings.fpsOverlayEnabled)
        assertEquals(ResolutionScale.FULL, settings.resolutionScale)
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
        settings.resolutionScale = ResolutionScale.HALF
        settings.screenOrientation = ScreenOrientation.LANDSCAPE

        val reloaded = SettingsRepository(store)

        assertEquals(FpsLimit.FPS_30, reloaded.fpsLimit)
        assertEquals(ResolutionScale.HALF, reloaded.resolutionScale)
        assertEquals(ScreenOrientation.LANDSCAPE, reloaded.screenOrientation)
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
            "display.nativeResolution",
            "display.resolutionScale",
            "display.aspectRatio",
            "display.immersive",
            "display.orientation",
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
}
