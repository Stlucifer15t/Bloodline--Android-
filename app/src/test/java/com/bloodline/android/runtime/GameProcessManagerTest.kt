package com.bloodline.android.runtime

import com.bloodline.android.runtime.process.Termination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for process lifecycle bookkeeping.
 *
 * The outcome that matters is [GameProcessManager.ExitReason.REAPED]: Android
 * 12+ kills an app's child processes past a cap and the victim disappears with
 * **no exit status at all**. A manager that only understands exit codes would
 * report "the game closed" and lose the real cause, so that case is modelled
 * separately and asserted separately.
 */
class GameProcessManagerTest {

    private var now = 0L
    private fun manager() = GameProcessManager { now }

    @Test
    fun `a crash reported as 139 is read as SIGSEGV, not as an error code`() {
        // The signal used to be dropped on the way in, so the one fact that
        // identifies a crash arrived as a bare 139 and was filed under
        // "closed with an error". Wine crashing and the game returning 3
        // produced the same shape of report.
        val result = manager().onExit(139)

        assertEquals(11, result.signal)
        assertEquals("SIGSEGV", result.signalName)
        assertEquals(Termination.CRASHED, result.termination)
        // The exit code is not claimed, because there was not one.
        assertNull(result.exitCode)
    }

    @Test
    fun `SIGKILL is distinguished from a crash`() {
        // Same reporter, opposite meaning. 137 is Android reclaiming the
        // process; 139 is the program faulting. They need different fixes.
        val killed = manager().onExit(137)
        val crashed = manager().onExit(139)

        assertEquals(Termination.KILLED_BY_SYSTEM, killed.termination)
        assertEquals(Termination.CRASHED, crashed.termination)
        assertTrue(killed.describe(), killed.describe().contains("SIGKILL"))
        assertTrue(crashed.describe(), crashed.describe().contains("SIGSEGV"))
    }

    @Test
    fun `a timeout is not the same thing as being reaped`() {
        // Both have no exit status. One is our deadline, the other is Android
        // having taken the child away. They used to be the same result.
        val manager = manager()
        manager.onLaunch(listOf("wine", "Vampire.exe"))

        val timedOut = manager.onTimedOut(120_000L)

        assertEquals(Termination.TIMED_OUT, timedOut.termination)
        assertEquals(GameProcessManager.ExitReason.TIMED_OUT, timedOut.reason)
    }

    @Test
    fun `a failed launch keeps its reason instead of being overwritten`() {
        // onMissing used to be followed by onReaped, which replaced the
        // reason with "reaped" and threw away the thing that went wrong.
        val manager = manager()
        manager.onLaunch(listOf("wine", "Vampire.exe"))

        val result = manager.onLaunchFailed("No such file or directory")

        assertEquals(Termination.LAUNCH_FAILED, result.termination)
        assertTrue(
            (manager.state() as GameProcessManager.State.Failed).reason,
            (manager.state() as GameProcessManager.State.Failed)
                .reason.contains("No such file"),
        )
    }

    @Test
    fun `the command and pid are recorded so a run can be identified`() {
        val manager = manager()
        manager.onLaunch(listOf("wine", "Vampire.exe", "-game", "vampire"), 4321L)

        val result = manager.onExit(139)

        assertEquals(listOf("wine", "Vampire.exe", "-game", "vampire"), result.command)
        assertEquals(4321L, result.pid)
    }

    @Test
    fun `the pid can arrive after launch, because it only exists after fork`() {
        val manager = manager()
        manager.onLaunch(listOf("wine", "Vampire.exe"))
        manager.attachPid(99L)

        assertEquals(99L, manager.onExit(0).pid)
    }

    @Test
    fun `session length is measured with the injected clock`() {
        // Injected so it can be monotonic on a device. A clock that jumps is
        // how a ten-minute run reports as zero seconds.
        val manager = manager()
        manager.onLaunch(listOf("wine"))
        now = 4_321L

        assertEquals(4_321L, manager.onExit(0).durationMillis)
    }

    @Test
    fun `refuses an empty command`() {
        val manager = manager()

        assertFalse(manager.onLaunch(emptyList()))
        assertTrue(manager.state() is GameProcessManager.State.Failed)
    }

    @Test
    fun `says why an empty command was refused`() {
        val manager = manager()
        manager.onLaunch(emptyList())

        assertTrue((manager.state() as GameProcessManager.State.Failed).reason.contains("missing"))
    }

    @Test
    fun `accepts a real command and moves to running`() {
        val manager = manager()

        assertTrue(manager.onLaunch(listOf("/wine/bin/wine", "D:\\Vampire.exe")))
        assertTrue(manager.state() is GameProcessManager.State.Running)
        assertTrue(manager.isRunning)
    }

    @Test
    fun `refuses a second launch while one is running`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))

        assertFalse(manager.onLaunch(listOf("wine")))
        assertTrue(
            (manager.state() as GameProcessManager.State.Failed).reason.contains("already running"),
        )
    }

    @Test
    fun `a clean exit is reported as clean`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))

        val result = manager.onExit(0)

        assertEquals(GameProcessManager.ExitReason.CLEAN, result.reason)
        assertEquals(0, result.exitCode)
        assertFalse(manager.isRunning)
    }

    @Test
    fun `a non-zero exit is reported as an error code`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))

        val result = manager.onExit(53)

        assertEquals(GameProcessManager.ExitReason.ERROR_CODE, result.reason)
        assertEquals(53, result.exitCode)
    }

    @Test
    fun `a signal is reported as a signal, not an exit code`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))

        val result = manager.onExit(0, signal = 9)

        assertEquals(GameProcessManager.ExitReason.SIGNAL, result.reason)
        assertEquals(9, result.signal)
        assertEquals(null, result.exitCode)
    }

    @Test
    fun `a process that vanished is reported as reaped, with no exit code`() {
        // The phantom process killer leaves nothing behind, so the manager must
        // not invent an exit code for it.
        val manager = manager()
        manager.onLaunch(listOf("wine"))

        val result = manager.onReaped()

        assertEquals(GameProcessManager.ExitReason.REAPED, result.reason)
        assertEquals(null, result.exitCode)
        assertEquals(null, result.signal)
    }

    @Test
    fun `measures how long the run lasted`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))
        now += 12_345

        assertEquals(12_345L, manager.onExit(0).durationMillis)
    }

    @Test
    fun `a run that never started reports no duration`() {
        val manager = manager()

        assertEquals(0L, manager.onExit(0).durationMillis)
    }

    @Test
    fun `resetting returns to idle`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))
        manager.onExit(0)

        manager.reset()

        assertTrue(manager.state() is GameProcessManager.State.Idle)
    }

    @Test
    fun `stopping is distinguishable from the game closing`() {
        val manager = manager()
        manager.onLaunch(listOf("wine"))

        assertEquals(GameProcessManager.ExitReason.STOPPED, manager.onStopped().reason)
    }
}
