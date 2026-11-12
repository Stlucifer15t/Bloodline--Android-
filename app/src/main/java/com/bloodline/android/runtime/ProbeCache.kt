package com.bloodline.android.runtime

import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * Remembers the result of a bundled native probe.
 *
 * A probe answers a question about the *device*: can an executable shipped
 * inside this APK run, is ptrace available, does the kernel accept 32-bit
 * processes? Those answers do not change between app starts, so re-running the
 * probe on every single launch is wasted work at the exact moment the user is
 * waiting for the launcher.
 *
 * Entries are keyed by probe name and invalidated by a new app version or an
 * OS upgrade.
 */
class ProbeCache(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(key: String, appVersion: String): Snapshot? {
        if (preferences.getString(versionKey(key), null) != stamp(appVersion)) return null
        if (!preferences.contains(exitCodeKey(key))) return null

        return Snapshot(
            exitCode = preferences.getInt(exitCodeKey(key), 0),
            output = preferences.getString(outputKey(key), "").orEmpty(),
        )
    }

    fun save(key: String, appVersion: String, snapshot: Snapshot) {
        preferences.edit {
            putString(versionKey(key), stamp(appVersion))
            putInt(exitCodeKey(key), snapshot.exitCode)
            putString(outputKey(key), snapshot.output)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun stamp(appVersion: String) = "$appVersion|${Build.VERSION.SDK_INT}"

    private fun versionKey(key: String) = "$key.version"
    private fun exitCodeKey(key: String) = "$key.exit_code"
    private fun outputKey(key: String) = "$key.output"

    /** What a probe returned the last time it ran. */
    data class Snapshot(
        val exitCode: Int,
        val output: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "bloodlines_probes"
    }
}
