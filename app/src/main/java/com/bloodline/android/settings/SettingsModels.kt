package com.bloodline.android.settings

/** How the launcher (and later the game surface) is oriented. */
enum class ScreenOrientation {
    PORTRAIT,
    LANDSCAPE,
    FOLLOW_DEVICE,
}

/** Text and layout scaling inside the app. */
enum class UiScale(val percent: Int, val fontScale: Float) {
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

/** Render resolution as a percentage of the native surface size. */
enum class ResolutionScale(val percent: Int) {
    FULL(100),
    THREE_QUARTERS(75),
    HALF(50),
    ;

    companion object {
        fun fromPercent(percent: Int): ResolutionScale? = entries.firstOrNull { it.percent == percent }
    }
}

/** How a fixed aspect ratio is presented on a differently shaped screen. */
enum class AspectRatioMode {
    PRESERVE,
    FIT,
    STRETCH,
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

/** Audio backend preference for the future audio bridge. */
enum class AudioBackend {
    AUTO,
    AAUDIO,
    OPENSLES,
}

/** Coarse tuning bundle applied to the runtime once it exists. */
enum class PerformancePreset {
    SAFE,
    BALANCED,
    AGGRESSIVE,
}
