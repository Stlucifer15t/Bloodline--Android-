package com.bloodline.android.runtime

import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * Remembers the result of the bundled native probe.
 *
 * The probe answers a question about the *device*: can an executable shipped
 * inside this APK be run at all? That answer does not change between app
 * starts, so re-running the probe on every single start is wasted work at the
 * exact moment the user is waiting for the launcher.
 *
 * The cache is keyed on the app version and the Android API level, so a new
 * build or an OS upgrade re-verifies instead of trusting the old answer.
 */
class NativeProbeCache(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(appVersion: String): NativeProbe.Result? {
        if (preferences.getString(KEY_VERSION, null) != key(appVersion)) return null
        if (!preferences.contains(KEY_EXIT_CODE)) return null

        return NativeProbe.Result(
            exitCode = preferences.getInt(KEY_EXIT_CODE, NativeProbe.EXIT_FAILED),
            output = preferences.getString(KEY_OUTPUT, "").orEmpty(),
        )
    }

    fun save(appVersion: String, result: NativeProbe.Result) {
        preferences.edit {
            putString(KEY_VERSION, key(appVersion))
            putInt(KEY_EXIT_CODE, result.exitCode)
            putString(KEY_OUTPUT, result.output)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun key(appVersion: String) = "$appVersion|${Build.VERSION.SDK_INT}"

    private companion object {
        const val PREFERENCES_NAME = "bloodlines_native_probe"
        const val KEY_VERSION = "probe_version"
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_OUTPUT = "output"
    }
}
