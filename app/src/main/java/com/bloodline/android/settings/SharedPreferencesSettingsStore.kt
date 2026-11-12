package com.bloodline.android.settings

import android.content.Context
import androidx.core.content.edit

/** [SettingsStore] backed by SharedPreferences. */
class SharedPreferencesSettingsStore(context: Context) : SettingsStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String =
        preferences.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    override fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        preferences.edit { putInt(key, value) }
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        preferences.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit { putBoolean(key, value) }
    }

    override fun remove(key: String) {
        preferences.edit { remove(key) }
    }

    override fun contains(key: String): Boolean = preferences.contains(key)

    private companion object {
        const val PREFERENCES_NAME = "bloodlines_settings"
    }
}
