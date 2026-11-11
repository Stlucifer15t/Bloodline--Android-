package com.bloodline.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application entry point.
 *
 * The launcher is a night-only experience, so night mode is forced here rather
 * than relying on the device setting. This keeps the custom dark theme looking
 * identical on every device.
 */
class BloodlinesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
