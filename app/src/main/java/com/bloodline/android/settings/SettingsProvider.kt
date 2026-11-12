package com.bloodline.android.settings

import android.content.Context

/**
 * Single [SettingsRepository] for the whole app.
 *
 * It is created once, from the application context, so every screen reads and
 * writes the same settings and no Activity needs to pass it around.
 */
object SettingsProvider {

    @Volatile
    private var repository: SettingsRepository? = null

    fun get(context: Context): SettingsRepository = repository ?: synchronized(this) {
        repository ?: SettingsRepository(
            SharedPreferencesSettingsStore(context.applicationContext),
        ).also { repository = it }
    }
}
