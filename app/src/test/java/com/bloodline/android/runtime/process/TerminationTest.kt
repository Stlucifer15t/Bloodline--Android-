package com.bloodline.android.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification of process deaths.
 *
 * These cases exist because the alternative is what the launcher shipped:
 * one reason for every death with no exit status, reported as though the
 * cause were known. Each test here pins a different *cause* to a different
 * *answer*, so that a Wine crash, an Android process cap, a timeout and a
 * missing binary can never again collapse into the same sentence.
 */
class TerminationTest {

    @Test
    fun `exit zero is a normal close`() {
        assertEquals(Termination.NORMAL, Termination.of(0))
    }

    @Test
    fun `a non-zero exit with no signal is the program's own error`() {
        // It ran, it finished, it disagreed. Not a crash, and not the system.
        assertEquals(Termination.GUEST_ERROR, Termination.of(3))
        assertTrue(Termination.of(3).isProgramFault)
    }

    @Test
    fun `SIGSEGV is a crash, not an exit code`() {
        // 139 is 128 + 11. Read as an exit code it says "an error"; read as a
        // signal it says the program touched memory it did not own.
        assertEquals(11, Termination.signalFromExitCode(139))
        assertEquals(Termination.CRASHED, Termination.of(139))
        assertEquals("SIGSEGV", Termination.signalName(11))
    }

    @Test
    fun `SIGKILL is the system, not the program`() {
        // The distinction that matters most on Android. Nothing faults into
        // SIGKILL: the low-memory killer or the phantom-process cap decided.
        // Treating it as a crash sends someone to debug the game for a death
        // the game had no hand in.
        assertEquals(Termination.KILLED_BY_SYSTEM, Termination.of(137))
        assertEquals(Termination.KILLED_BY_SYSTEM, Termination.of(null, 9))

        assertFalse(Termination.of(137).isProgramFault)
        assertFalse(Termination.of(137).neverRan)
    }

    @Test
    fun `each crash signal is a crash`() {
        // A fault, whichever way it faulted.
        for (signal in listOf(4, 5, 6, 7, 8, 11, 31)) {
            assertEquals("signal $signal", Termination.CRASHED, Termination.of(null, signal))
        }
    }

    @Test
    fun `SIGTERM and SIGINT are a stop that was asked for`() {
        assertEquals(Termination.TERMINATED, Termination.of(null, 15))
        assertEquals(Termination.TERMINATED, Termination.of(null, 2))
    }

    @Test
    fun `no status at all is vanished, not killed`() {
        // "Vanished" is the honest answer when there is no evidence: it does
        // not claim Android did it, because nothing here can prove that.
        assertEquals(Termination.VANISHED, Termination.of(null))
        assertEquals(Termination.VANISHED, Termination.of(null, null))
    }

    @Test
    fun `a plain exit code inside the signal range is still read as a signal`() {
        // 129..191 is the signalled range. A program cannot genuinely exit
        // with these, so reading them as signals is the only sensible reading.
        assertEquals(1, Termination.signalFromExitCode(129))
        assertNull(Termination.signalFromExitCode(128))
        assertNull(Termination.signalFromExitCode(192))
        assertNull(Termination.signalFromExitCode(0))
        assertNull(Termination.signalFromExitCode(-139))
    }

    @Test
    fun `an unnamed signal is still named plainly`() {
        // Never invent a name. "signal 42" is honest and searchable.
        assertEquals("signal 42", Termination.signalName(42))
    }

    @Test
    fun `a crash is described as the program faulting`() {
        val text = Termination.describe(Termination.CRASHED, 139, 11)

        assertTrue(text, text.contains("SIGSEGV"))
        // The point of the whole exercise: say what it is NOT, so nobody
        // spends a day blaming the operating system for a segfault.
        assertTrue(text, text.contains("not the system reclaiming it"))
    }

    @Test
    fun `a SIGKILL is described as something outside the process`() {
        val text = Termination.describe(Termination.KILLED_BY_SYSTEM, 137, 9)

        assertTrue(text, text.contains("SIGKILL"))
        assertTrue(text, text.contains("low-memory killer"))
        assertTrue(text, text.contains("phantom-process cap"))
    }

    @Test
    fun `a vanished process does not claim Android did it`() {
        // Used to be asserted as fact. With no status there is no evidence of
        // who or what, so the reading is offered as a reading.
        val text = Termination.describe(Termination.VANISHED, null, null)

        assertTrue(text, text.contains("consistent with"))
        assertFalse(text, text.contains("stopped by Android"))
    }

    @Test
    fun `a guest error says it is not a crash`() {
        val text = Termination.describe(Termination.GUEST_ERROR, 3, null)

        assertTrue(text, text.contains("exit 3"))
        assertTrue(text, text.contains("not a crash"))
    }

    @Test
    fun `never-ran terminations are recognised as such`() {
        assertTrue(Termination.LAUNCH_FAILED.neverRan)
        assertTrue(Termination.MISSING.neverRan)
        assertFalse(Termination.CRASHED.neverRan)
    }
}
