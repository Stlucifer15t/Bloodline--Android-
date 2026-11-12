package com.bloodline.android.diagnostics

import java.io.File

/** How the last session ended. */
enum class SessionEnd {
    /** The user left the app and the activity finished normally. No report. */
    CLEAN,

    /** The process died while the app was in front. */
    CRASH,

    /** Android stopped the process while the app was in the background. */
    KILLED_WHILE_BACKGROUNDED,

    /** We genuinely cannot tell. Reported as an unclean exit. */
    UNKNOWN,
}

/** A session that started but never reported a clean finish. */
data class UnfinishedSession(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val classification: SessionEnd,
    val wasBackgrounded: Boolean,
)

/**
 * Works out whether the previous run of the app ended properly.
 *
 * The trick is a marker file: it is written when a session starts and removed
 * only when the app is deliberately closed. Android gives no "app exited"
 * callback, and when a process is killed or crashes nothing runs at all, so a
 * marker that is still there on the next start is the only reliable evidence
 * that the previous run did not end cleanly.
 *
 * Everything lives in the app's own storage and no personal data is involved.
 */
class SessionTracker(
    private val directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Supplied by the caller: reading a pid is an Android call. */
    private val pid: () -> Int = { -1 },
) {

    private val marker: File get() = File(directory, MARKER_NAME)

    /**
     * Claims any unfinished session and forgets it.
     *
     * Call this *before* [begin], or the new session's marker would overwrite
     * the evidence of the old one.
     */
    fun takeUnfinished(): UnfinishedSession? {
        val file = marker
        if (!file.exists()) return null

        val values = runCatching { readValues(file) }.getOrNull()
        file.delete()

        val startedAt = values?.get(KEY_STARTED_AT)?.toLongOrNull() ?: return null
        val lastSeenAt = values[KEY_LAST_SEEN_AT]?.toLongOrNull() ?: startedAt

        // Recorded as an explicit state rather than compared by timestamp: an
        // app killed in the same millisecond it was backgrounded still counts
        // as backgrounded.
        val wasBackgrounded = values[KEY_STATE] == STATE_BACKGROUND

        return UnfinishedSession(
            startedAtMillis = startedAt,
            endedAtMillis = lastSeenAt,
            classification = if (wasBackgrounded) {
                SessionEnd.KILLED_WHILE_BACKGROUNDED
            } else {
                SessionEnd.CRASH
            },
            wasBackgrounded = wasBackgrounded,
        )
    }

    /** Starts a new session, keeping the marker if one is already open. */
    fun begin() {
        val existing = if (marker.exists()) runCatching { readValues(marker) }.getOrNull() else null
        val startedAt = existing?.get(KEY_STARTED_AT)?.toLongOrNull() ?: clock()
        val lastBackgroundedAt = existing?.get(KEY_BACKGROUNDED_AT)?.toLongOrNull() ?: 0L

        val values = linkedMapOf(
            KEY_STARTED_AT to startedAt.toString(),
            KEY_FOREGROUNDED_AT to clock().toString(),
            KEY_BACKGROUNDED_AT to lastBackgroundedAt.toString(),
            KEY_LAST_SEEN_AT to clock().toString(),
            KEY_STATE to STATE_FOREGROUND,
        )
        if (pid() >= 0) values[KEY_PID] = pid().toString()

        write(values)
    }

    /** Records that the app is in front again. */
    fun noteForegrounded() = update(
        KEY_FOREGROUNDED_AT to clock().toString(),
        KEY_LAST_SEEN_AT to clock().toString(),
        KEY_STATE to STATE_FOREGROUND,
    )

    /** Records that the app went to the background. */
    fun noteBackgrounded() = update(
        KEY_BACKGROUNDED_AT to clock().toString(),
        KEY_LAST_SEEN_AT to clock().toString(),
        KEY_STATE to STATE_BACKGROUND,
    )

    /** Keeps the "last seen" stamp fresh while the app is alive. */
    fun noteAlive() = update(KEY_LAST_SEEN_AT to clock().toString())

    /** Removes the marker: this run is ending because the user closed it. */
    fun finishCleanly() {
        marker.delete()
    }

    /** Applies changes to the open session, if there is one. */
    private fun update(vararg changes: Pair<String, String>) {
        if (!marker.exists()) return
        val values = runCatching { readValues(marker) }.getOrNull() ?: return

        val updated = LinkedHashMap(values)
        changes.forEach { (key, value) -> updated[key] = value }
        write(updated)
    }

    private fun write(values: Map<String, String>) {
        directory.mkdirs()
        runCatching {
            marker.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    private fun readValues(file: File): Map<String, String> =
        file.readLines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()

    companion object {
        const val MARKER_NAME = "session.marker"

        const val KEY_STARTED_AT = "startedAt"
        const val KEY_FOREGROUNDED_AT = "foregroundedAt"
        const val KEY_BACKGROUNDED_AT = "backgroundedAt"
        const val KEY_LAST_SEEN_AT = "lastSeenAt"
        const val KEY_STATE = "state"
        const val KEY_PID = "pid"

        const val STATE_FOREGROUND = "foreground"
        const val STATE_BACKGROUND = "background"
    }
}
