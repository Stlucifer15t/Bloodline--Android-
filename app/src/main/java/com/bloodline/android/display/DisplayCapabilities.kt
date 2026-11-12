package com.bloodline.android.display

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * Reads the display facts the resolution settings have to be judged against.
 *
 * Everything returned here is measured on the device. Nothing is inferred from
 * the model name, and nothing is hard-coded: the values are what Android
 * reports for the panel that is currently attached.
 */
object DisplayCapabilities {

    fun detect(context: Context): DisplayInfo {
        val metrics = DisplayMetrics()
        val refreshRateHz = readRealMetrics(context, metrics)

        return DisplayInfo(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            refreshRateHz = refreshRateHz,
            densityDpi = metrics.densityDpi,
        )
    }

    @Suppress("DEPRECATION")
    private fun readRealMetrics(context: Context, metrics: DisplayMetrics): Float {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = windowManager?.defaultDisplay

        if (display != null) {
            // Display#getRealMetrics reports the whole panel, without
            // subtracting the system bars or applying any compatibility
            // scaling — which is exactly the number a resolution choice has to
            // be measured against. The API 30 replacement
            // (WindowManager#getCurrentWindowMetrics) reports the *window*,
            // and that shrinks the moment a system bar becomes visible, so it
            // would make the same phone report two different "native" sizes
            // depending on whether immersive mode was on.
            display.getRealMetrics(metrics)
            return display.refreshRate
        }

        // No WindowManager (a bare context, or a headless test): fall back to
        // the resources, which are at least real numbers for this device.
        context.resources.displayMetrics.let { fallback ->
            metrics.setTo(fallback)
        }
        return 0f
    }

    /** True when the reading is usable; guards against a 0 × 0 panel. */
    fun isUsable(info: DisplayInfo): Boolean = info.widthPx > 0 && info.heightPx > 0
}

/**
 * "720 × 1600 · 60 Hz", ready to drop into a row.
 *
 * Built here rather than in an Activity so the launcher and the settings screen
 * cannot drift apart and format the same measurement two different ways. Empty
 * when nothing could be measured, so the caller can fall back to its own text.
 */
fun DisplayInfo.describe(): String {
    if (widthPx <= 0 || heightPx <= 0) return ""
    val size = "$widthPx × $heightPx"
    return if (refreshRateHz > 0f) "$size · ${refreshLabel()}" else size
}

/** Turns the measured refresh rate into something worth showing, e.g. "60 Hz". */
private fun DisplayInfo.refreshLabel(): String {
    if (refreshRateHz <= 0f) return ""
    val rounded = (refreshRateHz * 10).toInt() / 10f
    return if (rounded % 1f == 0f) {
        "${rounded.toInt()} Hz"
    } else {
        "$rounded Hz"
    }
}
