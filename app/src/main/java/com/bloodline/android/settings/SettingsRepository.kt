package com.bloodline.android.settings

import com.bloodline.android.display.AspectMode
import com.bloodline.android.display.RenderResolution
import com.bloodline.android.display.ResolutionScale
import com.bloodline.android.display.Size

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
 *
 * The display group keeps two different things apart on purpose:
 *
 * - **display resolution** ([displayResolution]) is the size the game surface is
 *   presented at — what the Windows desktop inside the container is told to use.
 * - **internal render resolution** ([renderScale]) is the fraction of that size
 *   the game actually renders at before the GPU scales it back up.
 *
 * Conflating them is what makes a "resolution" slider meaningless on a phone.
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

    /** Size the game surface is presented at. Native follows the real display. */
    var displayResolution: RenderResolution
        get() = store.getEnum(KEY_DISPLAY_RESOLUTION, RenderResolution.NATIVE)
        set(value) {
            store.putEnum(KEY_DISPLAY_RESOLUTION, value)
            notifyChanged(KEY_DISPLAY_RESOLUTION)
        }

    /** Used only when [displayResolution] is [RenderResolution.CUSTOM]. */
    var customDisplayWidth: Int
        get() = store.getInt(KEY_CUSTOM_WIDTH, DEFAULT_CUSTOM_WIDTH)
            .coerceIn(RenderResolution.MIN_WIDTH, RenderResolution.MAX_WIDTH)
        set(value) {
            store.putInt(
                KEY_CUSTOM_WIDTH,
                value.coerceIn(RenderResolution.MIN_WIDTH, RenderResolution.MAX_WIDTH),
            )
            notifyChanged(KEY_CUSTOM_WIDTH)
        }

    var customDisplayHeight: Int
        get() = store.getInt(KEY_CUSTOM_HEIGHT, DEFAULT_CUSTOM_HEIGHT)
            .coerceIn(RenderResolution.MIN_HEIGHT, RenderResolution.MAX_HEIGHT)
        set(value) {
            store.putInt(
                KEY_CUSTOM_HEIGHT,
                value.coerceIn(RenderResolution.MIN_HEIGHT, RenderResolution.MAX_HEIGHT),
            )
            notifyChanged(KEY_CUSTOM_HEIGHT)
        }

    fun customDisplaySize(): Size = Size(customDisplayWidth, customDisplayHeight)

    /**
     * Internal render resolution, as a percentage of the presented size.
     *
     * Rendering below the presented size and upscaling is the cheapest way to
     * keep a Mali-G52 fed, which is why the scale has a fine-grained ladder
     * rather than a single "low" step.
     */
    var renderScale: ResolutionScale
        get() = store.getEnum(KEY_RENDER_SCALE, ResolutionScale.FULL)
        set(value) {
            store.putEnum(KEY_RENDER_SCALE, value)
            notifyChanged(KEY_RENDER_SCALE)
        }

    /** Used only when [renderScale] is [ResolutionScale.CUSTOM]. */
    var customScalePercent: Int
        get() = store.getInt(KEY_CUSTOM_SCALE, DEFAULT_CUSTOM_SCALE).coerceIn(
            ResolutionScale.MIN_PERCENT,
            ResolutionScale.MAX_PERCENT,
        )
        set(value) {
            store.putInt(
                KEY_CUSTOM_SCALE,
                value.coerceIn(ResolutionScale.MIN_PERCENT, ResolutionScale.MAX_PERCENT),
            )
            notifyChanged(KEY_CUSTOM_SCALE)
        }

    /** The scaling actually in effect, resolving the Custom case. */
    fun effectiveScalePercent(): Int = when (val scale = renderScale) {
        ResolutionScale.CUSTOM -> customScalePercent
        else -> scale.percent
    }

    /** How a frame whose shape differs from the display is fitted to it. */
    var aspectMode: AspectMode
        get() = store.getEnum(KEY_ASPECT_MODE, AspectMode.PRESERVE)
        set(value) {
            store.putEnum(KEY_ASPECT_MODE, value)
            notifyChanged(KEY_ASPECT_MODE)
        }

    /**
     * Fill the area a preserved frame cannot cover with black bars, instead of
     * cropping the frame to fill the screen.
     */
    var letterboxEnabled: Boolean
        get() = store.getBoolean(KEY_LETTERBOX, true)
        set(value) {
            store.putBoolean(KEY_LETTERBOX, value)
            notifyChanged(KEY_LETTERBOX)
        }

    /** Scale a smaller frame up to fill the display instead of showing it 1:1. */
    var fullscreenScaling: Boolean
        get() = store.getBoolean(KEY_FULLSCREEN_SCALING, true)
        set(value) {
            store.putBoolean(KEY_FULLSCREEN_SCALING, value)
            notifyChanged(KEY_FULLSCREEN_SCALING)
        }

    /** Hide the system bars. Takes effect immediately on the launcher. */
    var immersiveMode: Boolean
        get() = store.getBoolean(KEY_IMMERSIVE, true)
        set(value) {
            store.putBoolean(KEY_IMMERSIVE, value)
            notifyChanged(KEY_IMMERSIVE)
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
        "display.resolution" to displayResolution.name,
        "display.customSize" to (
            if (displayResolution.isCustom) {
                "${customDisplayWidth}x$customDisplayHeight"
            } else {
                "n/a"
            }
            ),
        "display.renderScale" to "${effectiveScalePercent()}%",
        "display.aspectMode" to aspectMode.name,
        "display.letterbox" to letterboxEnabled.toString(),
        "display.fullscreenScaling" to fullscreenScaling.toString(),
        "display.immersive" to immersiveMode.toString(),
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

    /** Keys of every setting that exists today. */
    private val allKeys: List<String> get() = listOf(
        KEY_DISPLAY_RESOLUTION,
        KEY_CUSTOM_WIDTH,
        KEY_CUSTOM_HEIGHT,
        KEY_RENDER_SCALE,
        KEY_CUSTOM_SCALE,
        KEY_ASPECT_MODE,
        KEY_LETTERBOX,
        KEY_FULLSCREEN_SCALING,
        KEY_IMMERSIVE,
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
    )

    /** Keys that no longer exist, cleared so an upgrade leaves nothing behind. */
    private val retiredKeys: List<String> get() = listOf(
        "display.native_resolution",
        "display.resolution_scale",
        "display.aspect_ratio",
        "display.orientation",
    )

    fun resetToDefaults() {
        (allKeys + retiredKeys).forEach { key ->
            store.remove(key)
            notifyChanged(key)
        }
    }

    /**
     * Restores only the display and graphics choices.
     *
     * This is a real action, not a promise: unlike the resolution rows
     * themselves, which need a renderer, forgetting a preference is something
     * the launcher can do today.
     */
    fun resetGraphics() {
        listOf(
            KEY_DISPLAY_RESOLUTION,
            KEY_CUSTOM_WIDTH,
            KEY_CUSTOM_HEIGHT,
            KEY_RENDER_SCALE,
            KEY_CUSTOM_SCALE,
            KEY_ASPECT_MODE,
            KEY_LETTERBOX,
            KEY_FULLSCREEN_SCALING,
            KEY_RENDERER,
        ).forEach { key ->
            store.remove(key)
            notifyChanged(key)
        }
    }

    companion object {
        const val BRIGHTNESS_FOLLOW_SYSTEM = -1
        const val VOLUME_UNSET = -1

        const val DEFAULT_CUSTOM_WIDTH = 1280
        const val DEFAULT_CUSTOM_HEIGHT = 720
        const val DEFAULT_CUSTOM_SCALE = 75

        const val KEY_DISPLAY_RESOLUTION = "display.resolution"
        const val KEY_CUSTOM_WIDTH = "display.custom_width"
        const val KEY_CUSTOM_HEIGHT = "display.custom_height"
        const val KEY_RENDER_SCALE = "display.render_scale"
        const val KEY_CUSTOM_SCALE = "display.custom_scale_percent"
        const val KEY_ASPECT_MODE = "display.aspect_mode"
        const val KEY_LETTERBOX = "display.letterbox"
        const val KEY_FULLSCREEN_SCALING = "display.fullscreen_scaling"
        const val KEY_IMMERSIVE = "display.immersive"
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
