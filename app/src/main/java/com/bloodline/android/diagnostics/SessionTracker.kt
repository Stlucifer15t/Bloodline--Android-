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

    /** The marker file, or null when no session is currently open. */
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

        if (values == null) return null

        val startedAt = values[KEY_STARTED_AT]?.toLongOrNull() ?: return null
        val backgroundedAt = values[KEY_BACKGROUNDED_AT]?.toLongOrNull() ?: 0L
        val foregroundedAt = values[KEY_FOREGROUNDED_AT]?.toLongOrNull() ?: startedAt
        val lastSeenAt = values[KEY_LAST_SEEN_AT]?.toLongOrNull() ?: startedAt

        val wasBackgrounded = backgroundedAt > foregroundedAt
        val classification = when {
            wasBackgrounded -> SessionEnd.KILLED_WHILE_BACKGROUNDED
            else -> SessionEnd.CRASH
        }

        return UnfinishedSession(
            startedAtMillis = startedAt,
            endedAtMillis = lastSeenAt,
            classification = classification,
            wasBackgrounded = wasBackgrounded,
        )
    }

    /** Starts a new session, keeping the marker if one is already open. */
    fun begin() {
        val existing = if (marker.exists()) runCatching { readValues(marker) }.getOrNull() else null
        val startedAt = existing?.get(KEY_STARTED_AT)?.toLongOrNull() ?: clock()

        write(
            mapOf(
                KEY_STARTED_AT to startedAt,
                KEY_FOREGROUNDED_AT to clock(),
                KEY_BACKGROUNDED_AT to (existing?.get(KEY_BACKGROUNDED_AT)?.toLongOrNull() ?: 0L),
                KEY_LAST_SEEN_AT to clock(),
            ) + if (pid() >= 0) mapOf(KEY_PID to pid()) else emptyMap()
        )
    }

    /** Records that the app is in front again. */
    fun noteForegrounded() {
        if (!marker.exists()) return
        val values = runCatching { readValues(marker) }.getOrNull() ?: return
        write(values + (KEY_FOREGROUNDED_AT to clock()) + (KEY_LAST_SEEN_AT to clock()))
    }

    /** Records that the app went to the background. */
    fun noteBackgrounded() {
        if (!marker.exists()) return
        val values = runCatching { readValues(marker) }.getOrNull() ?: return
        write(values + (KEY_BACKGROUNDED_AT to clock()) + (KEY_LAST_SEEN_AT to clock()))
    }

    /** Keeps the "last seen" stamp fresh while the app is alive. */
    fun noteAlive() {
        if (!marker.exists()) return
        val values = runCatching { readValues(marker) }.getOrNull() ?: return
        write(values + (KEY_LAST_SEEN_AT to clock()))
    }

    /** Removes the marker: this run is ending because the user closed it. */
    fun finishCleanly() {
        marker.delete()
    }

    private fun write(values: Map<String, Any>) {
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
        const val KEY_PID = "pid"
    }
}
