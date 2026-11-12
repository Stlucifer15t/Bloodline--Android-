package com.bloodline.android.settings

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * Scales the launcher's own UI.
 *
 * Applied by wrapping the base context, which is the only reliable way to scale
 * an existing set of XML layouts. Both the font scale (sp) and the density (dp)
 * are adjusted so text *and* spacing scale together.
 */
object UiScaleApplier {

    fun wrap(context: Context, scale: UiScale): Context {
        if (scale == UiScale.NORMAL) return context

        val configuration = Configuration(context.resources.configuration)
        configuration.fontScale = scale.fontScale
        configuration.densityDpi = (configuration.densityDpi * scale.fontScale).roundToInt()
            .coerceAtLeast(1)

        return context.createConfigurationContext(configuration)
    }
}
