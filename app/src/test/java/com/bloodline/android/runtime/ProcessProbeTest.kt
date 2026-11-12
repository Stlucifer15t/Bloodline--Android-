package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests how the process probe's output is interpreted.
 *
 * The measurements here decide whether a Wine process tree can survive on the
 * device at all. The one that matters most is [ProcessProbe.processLimit]:
 * Android 12+ kills an app's children past a cap, and the runtime needs to know
 * the number so it can tell "the game crashed" apart from "Android killed it".
 */
class ProcessProbeTest {

    private val healthy = """
        process-probe: ok
        pid: 1234
        max-processes-soft: 4096
        max-processes-hard: 4096
        exec-wait: ALLOWED
        exec-wait-status: 0
        process-tree-started: 4
        process-tree-reaped: 4
        process-tree-lost: 0
        process-tree: ALLOWED (every child was reaped)
        proc-entries: 210
    """.trimIndent()

    /** What a device under a tight cap looks like. */
    private val capped = """
        process-probe: ok
        max-processes-soft: 32
        max-processes-hard: 32
        exec-wait: ALLOWED
        process-tree-started: 4
        process-tree-reaped: 4
        process-tree-lost: 0
        process-tree: ALLOWED
    """.trimIndent()

    /** Children vanishing before they can be reaped. */
    private val losing = """
        process-probe: ok
        max-processes-soft: 32
        exec-wait: ALLOWED
        process-tree-started: 4
        process-tree-reaped: 2
        process-tree-lost: 2
        process-tree: DENIED (some children vanished before reaping)
    """.trimIndent()

    @Test
    fun `accepts a clean run that announced itself`() {
        assertTrue(ProcessProbe.isSuccessful(exitCode = 0, output = healthy))
        assertFalse(ProcessProbe.isSuccessful(exitCode = 1, output = healthy))
        assertFalse(ProcessProbe.isSuccessful(exitCode = 0, output = "fork: not permitted"))
    }

    @Test
    fun `reads the soft process limit`() {
        assertEquals(4096, ProcessProbe.processLimit(healthy))
    }

    @Test
    fun `a tight cap is read rather than assumed`() {
        assertEquals(32, ProcessProbe.processLimit(capped))
    }

    @Test
    fun `falls back to the hard limit when the soft one was not printed`() {
        val output = """
            process-probe: ok
            max-processes-hard: 1024
        """.trimIndent()

        assertEquals(1024, ProcessProbe.processLimit(output))
    }

    @Test
    fun `an unreadable limit is zero, not a guess`() {
        // Zero means "not read", and the runtime treats it as no constraint
        // rather than inventing a number.
        assertEquals(0, ProcessProbe.processLimit("process-probe: ok\n"))
    }

    @Test
    fun `reads whether exec and wait returned a status`() {
        assertEquals(true, ProcessProbe.execWaitWorks(healthy))
    }

    @Test
    fun `a blocked exec is reported as false`() {
        val output = "process-probe: ok\nexec-wait: DENIED (not permitted)\n"

        assertEquals(false, ProcessProbe.execWaitWorks(output))
    }

    @Test
    fun `a probe that did not say is unknown`() {
        assertNull(ProcessProbe.execWaitWorks("process-probe: ok\n"))
        assertNull(ProcessProbe.treeWorks("process-probe: ok\n"))
    }

    @Test
    fun `counts children that vanished`() {
        assertEquals(0, ProcessProbe.lostChildren(healthy))
        assertEquals(2, ProcessProbe.lostChildren(losing))
        assertEquals(false, ProcessProbe.treeWorks(losing))
    }

    @Test
    fun `summarises a healthy device`() {
        val summary = ProcessProbe.summary(healthy)

        assertTrue(summary.contains("exec and wait work"))
        assertTrue(summary.contains("child processes survive"))
        assertTrue(summary.contains("limit 4096"))
    }

    @Test
    fun `summarises a device that loses children`() {
        assertTrue(ProcessProbe.summary(losing).contains("children are lost"))
    }

    @Test
    fun `a probe that never ran is reported as not measured`() {
        assertEquals("not measured", ProcessProbe.summary(""))
    }
}
