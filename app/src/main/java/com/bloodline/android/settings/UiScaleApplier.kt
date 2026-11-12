package com.bloodline.android.settings

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * Scales the launcher's own UI.
 *
 * Applied by wrapping the base context, which is the only reliable way to scale
 * an existing set of XML layouts.
 *
 * **Only the density is changed.** This used to set `fontScale` as well, which
 * multiplied: `scaledDensity = density × fontScale`, so at the Large setting
 * text grew by 1.15 × 1.15 = 1.32 while every dp box around it grew by 1.15.
 * That is precisely how labels ended up overflowing their own rows at 115%.
 * Leaving `fontScale` alone makes text and spacing scale together, and it also
 * keeps whatever font size the user has set in the device's display settings.
 */
object UiScaleApplier {

    fun wrap(context: Context, scale: UiScale): Context {
        if (scale == UiScale.NORMAL) return context

        val configuration = Configuration(context.resources.configuration)

        // Configuration.densityDpi can legitimately be UNDEFINED (0) on a
        // context that was never attached to a display, so fall back to the
        // real device metrics before scaling.
        val baseDpi = configuration.densityDpi.takeIf { it > 0 }
            ?: context.resources.displayMetrics.densityDpi

        configuration.densityDpi = (baseDpi * scale.scaleFactor).roundToInt().coerceAtLeast(1)

        return context.createConfigurationContext(configuration)
    }
}
