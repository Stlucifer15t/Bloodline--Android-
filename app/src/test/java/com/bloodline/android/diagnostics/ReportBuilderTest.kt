package com.bloodline.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report text is what a human reads when something went wrong, so the
 * wording and the honesty of the classification matter.
 */
class ReportBuilderTest {

    private val session = UnfinishedSession(
        startedAtMillis = 1_700_000_000_000L,
        endedAtMillis = 1_700_000_125_000L,
        classification = SessionEnd.CRASH,
        wasBackgrounded = false,
    )

    @Test
    fun `a crash is described as a crash`() {
        assertTrue(ReportBuilder.headline(SessionEnd.CRASH).contains("unexpectedly"))
    }

    @Test
    fun `a background stop is not called a crash`() {
        val headline = ReportBuilder.headline(SessionEnd.KILLED_WHILE_BACKGROUNDED)

        assertTrue(headline.contains("background"))
        assertTrue(!headline.contains("crash", ignoreCase = true))
    }

    @Test
    fun `the summary says how long the session ran`() {
        val summary = ReportBuilder.summary(
            createdAtMillis = 1_700_000_200_000L,
            session = session,
            classification = SessionEnd.CRASH,
            appVersion = "0.5.0",
            sections = listOf("summary.txt", "device.txt"),
            logNames = listOf("session.1.log"),
        )

        assertTrue(summary.contains("Ran for: 2m 5s"))
        assertTrue(summary.contains("App version: 0.5.0"))
        assertTrue(summary.contains("- device.txt"))
        assertTrue(summary.contains("- logs/session.1.log"))
    }

    @Test
    fun `a report made on request says so instead of inventing a crash`() {
        val summary = ReportBuilder.summary(
            createdAtMillis = 1_700_000_200_000L,
            session = null,
            classification = SessionEnd.UNKNOWN,
            appVersion = "0.5.0",
            sections = emptyList(),
            logNames = emptyList(),
        )

        assertTrue(summary.contains("generated on request"))
        assertTrue(!summary.contains("crash", ignoreCase = true))
    }

    @Test
    fun `a backgrounded session explains that it was not a crash`() {
        val summary = ReportBuilder.summary(
            createdAtMillis = 1_700_000_200_000L,
            session = session.copy(wasBackgrounded = true),
            classification = SessionEnd.KILLED_WHILE_BACKGROUNDED,
            appVersion = "0.5.0",
            sections = emptyList(),
            logNames = emptyList(),
        )

        assertTrue(summary.contains("in the background"))
        assertTrue(summary.contains("not a crash"))
    }

    @Test
    fun `the summary states what the archive does not contain`() {
        val summary = ReportBuilder.summary(
            createdAtMillis = 1_700_000_200_000L,
            session = session,
            classification = SessionEnd.CRASH,
            appVersion = "0.5.0",
            sections = emptyList(),
            logNames = emptyList(),
        )

        assertTrue(summary.contains("no account"))
        assertTrue(summary.contains("no"))
        assertTrue(summary.contains("password"))
    }

    @Test
    fun `sections are rendered as aligned key value pairs`() {
        val text = ReportBuilder.section(
            "Settings",
            mapOf("display.immersive" to "true", "a" to "b"),
        )

        assertTrue(text.startsWith("Settings"))
        assertTrue(text.contains("display.immersive : true"))
        // Keys are padded so the values line up.
        assertTrue(text.contains("a".padEnd("display.immersive".length) + " : b"))
    }

    @Test
    fun `an empty section says so instead of looking empty by accident`() {
        assertTrue(ReportBuilder.section("Nothing", emptyMap()).contains("nothing recorded"))
    }

    @Test
    fun `durations are readable`() {
        assertEquals("45s", ReportBuilder.formatDuration(45L))
        assertEquals("2m 5s", ReportBuilder.formatDuration(125L))
        assertEquals("1h 0m 3s", ReportBuilder.formatDuration(3603L))
        assertEquals("0s", ReportBuilder.formatDuration(-5L))
    }
}
