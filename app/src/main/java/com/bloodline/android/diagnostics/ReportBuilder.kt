package com.bloodline.android.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns collected facts into the readable text that goes inside a report.
 *
 * Pure string work on plain maps, so the wording can be unit tested and the
 * Android specific collection stays somewhere else.
 */
object ReportBuilder {

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Short line for the launcher card. */
    fun headline(classification: SessionEnd): String = when (classification) {
        SessionEnd.CRASH -> "The app closed unexpectedly"
        SessionEnd.KILLED_WHILE_BACKGROUNDED ->
            "Android stopped the app while it was in the background"

        SessionEnd.UNKNOWN -> "The previous session did not finish"
        SessionEnd.CLEAN -> "Closed normally"
    }

    /**
     * The first file in the archive: what happened, when, and what is inside.
     */
    fun summary(
        createdAtMillis: Long,
        session: UnfinishedSession?,
        classification: SessionEnd,
        appVersion: String,
        sections: List<String>,
        logNames: List<String>,
    ): String = buildString {
        appendLine("Bloodlines diagnostics report")
        appendLine("=============================")
        appendLine()
        appendLine("Generated: ${STAMP.format(Date(createdAtMillis))}")
        appendLine("App version: $appVersion")
        appendLine()

        appendLine("What happened")
        appendLine("-------------")
        appendLine(headline(classification))
        if (session != null) {
            appendLine("Session started: ${STAMP.format(Date(session.startedAtMillis))}")
            appendLine("Last seen: ${STAMP.format(Date(session.endedAtMillis))}")
            val seconds = (session.endedAtMillis - session.startedAtMillis) / 1000
            appendLine("Ran for: ${formatDuration(seconds)}")
            appendLine(
                if (session.wasBackgrounded) {
                    "The app was in the background when it stopped, so Android most likely " +
                        "reclaimed the process. This is not a crash."
                } else {
                    "The app was in front when it stopped, so this was most likely a crash " +
                        "or a forced stop."
                }
            )
        } else {
            appendLine("This report was generated on request, not because a session ended badly.")
        }
        appendLine()

        appendLine("Contents")
        appendLine("--------")
        sections.forEach { appendLine("- $it") }
        logNames.forEach { appendLine("- logs/$it") }
        appendLine()

        appendLine("Privacy")
        appendLine("-------")
        appendLine("This archive holds only what this app produced: its own logs, facts about")
        appendLine("the device, and the settings you chose. It contains no account, no")
        appendLine("password, and none of your personal files.")
    }

    /** Renders a `key: value` block, with keys aligned. */
    fun section(title: String, facts: Map<String, String>): String = buildString {
        appendLine(title)
        appendLine("-".repeat(title.length))
        if (facts.isEmpty()) {
            appendLine("(nothing recorded)")
        } else {
            val width = facts.keys.maxOf { it.length }
            facts.forEach { (key, value) ->
                appendLine("${key.padEnd(width)} : $value")
            }
        }
        appendLine()
    }

    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${secs}s"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }
}
