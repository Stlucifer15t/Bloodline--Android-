package com.bloodline.android.settings

/** Text and layout scaling inside the app. */
enum class UiScale(val percent: Int, val scaleFactor: Float) {
    SMALL(85, 0.85f),
    NORMAL(100, 1.0f),
    LARGE(115, 1.15f),
    ;

    companion object {
        fun fromPercent(percent: Int): UiScale? = entries.firstOrNull { it.percent == percent }
    }
}

/** Frame rate cap for the game surface. 0 means uncapped. */
enum class FpsLimit(val framesPerSecond: Int) {
    UNCAPPED(0),
    FPS_60(60),
    FPS_45(45),
    FPS_40(40),
    FPS_30(30),
    FPS_20(20),
    ;

    val label: String
        get() = if (framesPerSecond == 0) "Uncapped" else "$framesPerSecond fps"

    companion object {
        fun fromValue(framesPerSecond: Int): FpsLimit? =
            entries.firstOrNull { it.framesPerSecond == framesPerSecond }
    }
}

/**
 * Which part of the app currently owns the screen.
 *
 * The two phases need different things: the launcher is usable either way up,
 * while the game is a landscape Windows title that only ever makes sense in
 * landscape. Keeping them as separate phases is what stops a launcher
 * preference from being applied while the game is running.
 */
enum class OrientationPhase {
    /** The launcher, the settings screen, diagnostics. */
    LAUNCHER,

    /** The game surface. Always landscape, whatever the launcher was doing. */
    GAMEPLAY,
}

/**
 * What the app asks Android for.
 *
 * Kept as its own enum rather than storing `ActivityInfo` constants so the
 * policy below can be unit tested on a plain JVM.
 */
enum class OrientationRequest {
    /** Let the device decide: the launcher works in either orientation. */
    FOLLOW_DEVICE,

    /** Landscape, following the sensor so either 0° or 180° is accepted. */
    SENSOR_LANDSCAPE,
}

/**
 * Graphics backends the launcher knows about.
 *
 * Nothing is loaded yet: this only records the user's choice for the milestone
 * that integrates the graphics stack.
 */
enum class RendererChoice(val isExperimental: Boolean) {
    /** OpenGL path via WineD3D over VirGL: works on Mali, and is the safe default. */
    WINE_D3D_VIRGL(isExperimental = false),

    /** Vulkan path via DXVK over PanVK: unproven on Mali-G52, never auto-selected. */
    PANVK_DXVK(isExperimental = true),
}

/**
 * Audio backend preference for the future audio bridge.
 *
 * `label` is only for display; persistence uses the enum name, so adding a
 * label can never invalidate a stored value.
 */
enum class AudioBackend(val label: String) {
    AUTO("Auto"),
    AAUDIO("AAudio"),
    OPENSLES("OpenSL ES"),
}

/** Coarse tuning bundle applied to the runtime once it exists. */
enum class PerformancePreset {
    SAFE,
    BALANCED,
    AGGRESSIVE,
}
