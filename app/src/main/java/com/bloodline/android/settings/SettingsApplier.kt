package com.bloodline.android.settings

import android.app.Activity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Applies every setting that has a real, immediate effect on an Activity.
 *
 * Settings that need the Windows compatibility runtime (frame limiting,
 * resolution scaling, renderer choice, audio routing) are deliberately absent:
 * they cannot do anything yet, and pretending otherwise would be a lie in the
 * UI. They are stored by [SettingsRepository] and read by the runtime later.
 *
 * Orientation is also absent, on purpose. It used to be applied from a stored
 * preference, but that preference only ever controlled the launcher's own
 * screens and could contradict the one orientation rule that is not a
 * preference — the game has to run landscape. Orientation now belongs to
 * [OrientationController], which keeps the launcher phase and the gameplay
 * phase apart.
 */
object SettingsApplier {

    fun apply(activity: Activity, settings: SettingsRepository) {
        applyImmersiveMode(activity, settings)
        applyKeepAwake(activity, settings)
        applyBrightness(activity, settings)
    }

    private fun applyImmersiveMode(activity: Activity, settings: SettingsRepository) {
        val window = activity.window
        val immersive = settings.immersiveMode

        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (immersive) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun applyKeepAwake(activity: Activity, settings: SettingsRepository) {
        val window = activity.window
        if (settings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Brightness is applied to this app's window only. Android has not allowed
     * an app to change the system-wide brightness since API 23 without a
     * special permission, so there is nothing else we could honestly do.
     */
    private fun applyBrightness(activity: Activity, settings: SettingsRepository) {
        val percent = settings.brightnessPercent
        val value = if (percent == SettingsRepository.BRIGHTNESS_FOLLOW_SYSTEM) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            (percent.coerceIn(0, 100) / 100f).coerceIn(0.01f, 1f)
        }

        val attributes = activity.window.attributes
        attributes.screenBrightness = value
        activity.window.attributes = attributes
    }
}
