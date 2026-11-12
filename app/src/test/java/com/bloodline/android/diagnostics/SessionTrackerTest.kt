package com.bloodline.android.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Session detection is the whole basis of crash reporting: if it reports a
 * crash after a normal close, nobody will trust it, and if it misses a real
 * one, the report that would have explained it is never written.
 */
class SessionTrackerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** One folder per test, created once: the rule refuses duplicates. */
    private lateinit var directory: File

    @Before
    fun createDirectory() {
        directory = temporaryFolder.newFolder("sessions")
    }

    private fun tracker(clock: () -> Long = { NOW }) =
        SessionTracker(directory, clock, pid = { 4242 })

    // ------------------------------------------------------------------- tests

    @Test
    fun `a session that was closed cleanly leaves nothing behind`() {
        val tracker = tracker()

        tracker.begin()
        tracker.finishCleanly()

        assertNull(tracker().takeUnfinished())
    }

    @Test
    fun `a session that never finished is reported`() {
        tracker().begin()

        val unfinished = tracker().takeUnfinished()

        assertNotNull(unfinished)
        assertEquals(NOW, unfinished!!.startedAtMillis)
    }

    @Test
    fun `a crash while the app was in front is classified as a crash`() {
        tracker().begin()
        tracker().noteForegrounded()

        val unfinished = tracker().takeUnfinished()

        assertEquals(SessionEnd.CRASH, unfinished?.classification)
        assertFalse(unfinished!!.wasBackgrounded)
    }

    @Test
    fun `a death while backgrounded is not reported as a crash`() {
        tracker().begin()
        tracker().noteBackgrounded()

        val unfinished = tracker().takeUnfinished()

        assertEquals(SessionEnd.KILLED_WHILE_BACKGROUNDED, unfinished?.classification)
        assertTrue(unfinished!!.wasBackgrounded)
    }

    @Test
    fun `coming back to the foreground clears the backgrounded state`() {
        tracker().begin()
        tracker().noteBackgrounded()
        tracker().noteForegrounded()

        assertEquals(SessionEnd.CRASH, tracker().takeUnfinished()?.classification)
    }

    @Test
    fun `taking the unfinished session removes it`() {
        tracker().begin()

        tracker().takeUnfinished()

        // The second call is a fresh start, and must not see it again.
        assertNull(tracker().takeUnfinished())
    }

    @Test
    fun `begin keeps the original start time of an open session`() {
        val later = NOW + 60_000

        tracker(clock = { NOW }).begin()
        // Recreating the launcher must not restart the clock.
        tracker(clock = { later }).begin()

        assertEquals(NOW, tracker(clock = { later }).takeUnfinished()?.startedAtMillis)
    }

    @Test
    fun `an unreadable marker yields nothing rather than a false report`() {
        File(directory, SessionTracker.MARKER_NAME).writeText("garbage")

        assertNull(tracker().takeUnfinished())
    }

    @Test
    fun `note methods are safe when no session is open`() {
        val tracker = tracker()

        tracker.noteForegrounded()
        tracker.noteBackgrounded()
        tracker.noteAlive()

        assertNull(tracker.takeUnfinished())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
