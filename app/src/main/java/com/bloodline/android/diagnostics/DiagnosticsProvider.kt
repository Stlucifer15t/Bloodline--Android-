package com.bloodline.android.diagnostics

import android.content.Context

/**
 * One [DiagnosticsManager] per process.
 *
 * It has to outlive an Activity: rotating the screen destroys and recreates the
 * launcher, and a session that is merely being recreated must not be mistaken
 * for one that died.
 */
object DiagnosticsProvider {

    @Volatile
    private var manager: DiagnosticsManager? = null

    fun get(context: Context, appVersion: String): DiagnosticsManager =
        manager ?: synchronized(this) {
            manager ?: DiagnosticsManager(context.applicationContext, appVersion)
                .also { manager = it }
        }
}
