package com.bloodline.android.settings

/**
 * Typed access to every launcher setting.
 *
 * Rules this class enforces:
 * - every read has a safe default, and a corrupted stored value falls back to it
 * - every write notifies listeners so the UI can apply the change immediately
 * - nothing here knows about Android, so it is unit testable
 *
 * Some settings cannot take effect yet because the Windows compatibility
 * runtime does not exist. They are still stored (so the runtime reads them the
 * day it arrives) but the UI disables them and says why.
 */
class SettingsRepository(
    private val store: SettingsStore,
) {

    private val listeners = LinkedHashSet<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged(key: String) {
        listeners.forEach { it(key) }
    }

    // ---------------------------------------------------------- A. display

    /** Render at the surface's native resolution once the runtime exists. */
    var useNativeResolution: Boolean
        get() = store.getBoolean(KEY_NATIVE_RESOLUTION, true)
        set(value) {
            store.putBoolean(KEY_NATIVE_RESOLUTION, value)
            notifyChanged(KEY_NATIVE_RESOLUTION)
        }

    var resolutionScale: ResolutionScale
        get() = store.getEnum(KEY_RESOLUTION_SCALE, ResolutionScale.FULL)
        set(value) {
            store.putEnum(KEY_RESOLUTION_SCALE, value)
            notifyChanged(KEY_RESOLUTION_SCALE)
        }

    var aspectRatio: AspectRatioMode
        get() = store.getEnum(KEY_ASPECT_RATIO, AspectRatioMode.PRESERVE)
        set(value) {
            store.putEnum(KEY_ASPECT_RATIO, value)
            notifyChanged(KEY_ASPECT_RATIO)
        }

    /** Hide the system bars. Takes effect immediately on the launcher. */
    var immersiveMode: Boolean
        get() = store.getBoolean(KEY_IMMERSIVE, true)
        set(value) {
            store.putBoolean(KEY_IMMERSIVE, value)
            notifyChanged(KEY_IMMERSIVE)
        }

    var screenOrientation: ScreenOrientation
        get() = store.getEnum(KEY_ORIENTATION, ScreenOrientation.PORTRAIT)
        set(value) {
            store.putEnum(KEY_ORIENTATION, value)
            notifyChanged(KEY_ORIENTATION)
        }

    /** Keep the screen on while the launcher, and later the game, is in front. */
    var keepScreenAwake: Boolean
        get() = store.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) {
            store.putBoolean(KEY_KEEP_AWAKE, value)
            notifyChanged(KEY_KEEP_AWAKE)
        }

    var uiScale: UiScale
        get() = store.getEnum(KEY_UI_SCALE, UiScale.NORMAL)
        set(value) {
            store.putEnum(KEY_UI_SCALE, value)
            notifyChanged(KEY_UI_SCALE)
        }

    /** Window brightness percent, or [BRIGHTNESS_FOLLOW_SYSTEM] to leave it alone. */
    var brightnessPercent: Int
        get() = store.getInt(KEY_BRIGHTNESS, BRIGHTNESS_FOLLOW_SYSTEM).coerceIn(-1, 100)
        set(value) {
            store.putInt(KEY_BRIGHTNESS, value.coerceIn(-1, 100))
            notifyChanged(KEY_BRIGHTNESS)
        }

    // ------------------------------------------------------ B. performance

    /** Frame cap for the game surface. Inert until the runtime has a frame loop. */
    var fpsLimit: FpsLimit
        get() = store.getEnum(KEY_FPS_LIMIT, FpsLimit.UNCAPPED)
        set(value) {
            store.putEnum(KEY_FPS_LIMIT, value)
            notifyChanged(KEY_FPS_LIMIT)
        }

    var vsyncEnabled: Boolean
        get() = store.getBoolean(KEY_VSYNC, true)
        set(value) {
            store.putBoolean(KEY_VSYNC, value)
            notifyChanged(KEY_VSYNC)
        }

    var framePacingEnabled: Boolean
        get() = store.getBoolean(KEY_FRAME_PACING, true)
        set(value) {
            store.putBoolean(KEY_FRAME_PACING, value)
            notifyChanged(KEY_FRAME_PACING)
        }

    /** Live frame-rate overlay. Works today: it measures the real surface. */
    var fpsOverlayEnabled: Boolean
        get() = store.getBoolean(KEY_FPS_OVERLAY, false)
        set(value) {
            store.putBoolean(KEY_FPS_OVERLAY, value)
            notifyChanged(KEY_FPS_OVERLAY)
        }

    var performancePreset: PerformancePreset
        get() = store.getEnum(KEY_PERFORMANCE_PRESET, PerformancePreset.SAFE)
        set(value) {
            store.putEnum(KEY_PERFORMANCE_PRESET, value)
            notifyChanged(KEY_PERFORMANCE_PRESET)
        }

    // --------------------------------------------------------- C. graphics

    /** Chosen backend; only applied once the graphics stack is integrated. */
    var renderer: RendererChoice
        get() = store.getEnum(KEY_RENDERER, RendererChoice.WINE_D3D_VIRGL)
        set(value) {
            store.putEnum(KEY_RENDERER, value)
            notifyChanged(KEY_RENDERER)
        }

    // ------------------------------------------------------------ D. audio

    /** Android music-stream volume. Real effect: this is the system volume. */
    var masterVolume: Int
        get() = store.getInt(KEY_MASTER_VOLUME, VOLUME_UNSET).coerceIn(-1, 100)
        set(value) {
            store.putInt(KEY_MASTER_VOLUME, value.coerceIn(0, 100))
            notifyChanged(KEY_MASTER_VOLUME)
        }

    /** Seeds the master volume from the device the first time it is needed. */
    fun initialiseMasterVolume(systemVolumePercent: Int) {
        if (!store.contains(KEY_MASTER_VOLUME)) {
            masterVolume = systemVolumePercent.coerceIn(0, 100)
        }
    }

    var audioBackend: AudioBackend
        get() = store.getEnum(KEY_AUDIO_BACKEND, AudioBackend.AUTO)
        set(value) {
            store.putEnum(KEY_AUDIO_BACKEND, value)
            notifyChanged(KEY_AUDIO_BACKEND)
        }

    // ---------------------------------------------------------------- misc

    /** Everything currently chosen, for the diagnostics report. */
    fun snapshot(): Map<String, String> = linkedMapOf(
        "display.nativeResolution" to useNativeResolution.toString(),
        "display.resolutionScale" to "${resolutionScale.percent}%",
        "display.aspectRatio" to aspectRatio.name,
        "display.immersive" to immersiveMode.toString(),
        "display.orientation" to screenOrientation.name,
        "display.keepAwake" to keepScreenAwake.toString(),
        "display.uiScale" to "${uiScale.percent}%",
        "display.brightness" to (
            if (brightnessPercent == BRIGHTNESS_FOLLOW_SYSTEM) {
                "follow system"
            } else {
                "$brightnessPercent%"
            }
            ),
        "performance.fpsLimit" to fpsLimit.label,
        "performance.vsync" to vsyncEnabled.toString(),
        "performance.framePacing" to framePacingEnabled.toString(),
        "performance.fpsOverlay" to fpsOverlayEnabled.toString(),
        "performance.preset" to performancePreset.name,
        "graphics.renderer" to renderer.name +
            if (renderer.isExperimental) " (experimental)" else "",
        "audio.masterVolume" to if (masterVolume < 0) "unchanged" else "$masterVolume%",
        "audio.backend" to audioBackend.name,
    )

    fun resetToDefaults() {
        listOf(
            KEY_NATIVE_RESOLUTION,
            KEY_RESOLUTION_SCALE,
            KEY_ASPECT_RATIO,
            KEY_IMMERSIVE,
            KEY_ORIENTATION,
            KEY_KEEP_AWAKE,
            KEY_UI_SCALE,
            KEY_BRIGHTNESS,
            KEY_FPS_LIMIT,
            KEY_VSYNC,
            KEY_FRAME_PACING,
            KEY_FPS_OVERLAY,
            KEY_PERFORMANCE_PRESET,
            KEY_RENDERER,
            KEY_MASTER_VOLUME,
            KEY_AUDIO_BACKEND,
        ).forEach { key ->
            store.remove(key)
            notifyChanged(key)
        }
    }

    companion object {
        const val BRIGHTNESS_FOLLOW_SYSTEM = -1
        const val VOLUME_UNSET = -1

        const val KEY_NATIVE_RESOLUTION = "display.native_resolution"
        const val KEY_RESOLUTION_SCALE = "display.resolution_scale"
        const val KEY_ASPECT_RATIO = "display.aspect_ratio"
        const val KEY_IMMERSIVE = "display.immersive"
        const val KEY_ORIENTATION = "display.orientation"
        const val KEY_KEEP_AWAKE = "display.keep_awake"
        const val KEY_UI_SCALE = "display.ui_scale"
        const val KEY_BRIGHTNESS = "display.brightness"
        const val KEY_FPS_LIMIT = "performance.fps_limit"
        const val KEY_VSYNC = "performance.vsync"
        const val KEY_FRAME_PACING = "performance.frame_pacing"
        const val KEY_FPS_OVERLAY = "performance.fps_overlay"
        const val KEY_PERFORMANCE_PRESET = "performance.preset"
        const val KEY_RENDERER = "graphics.renderer"
        const val KEY_MASTER_VOLUME = "audio.master_volume"
        const val KEY_AUDIO_BACKEND = "audio.backend"
    }
}

private inline fun <reified T : Enum<T>> SettingsStore.getEnum(key: String, default: T): T =
    runCatching { enumValueOf<T>(getString(key, default.name)) }.getOrDefault(default)

private fun <T : Enum<T>> SettingsStore.putEnum(key: String, value: T) = putString(key, value.name)
