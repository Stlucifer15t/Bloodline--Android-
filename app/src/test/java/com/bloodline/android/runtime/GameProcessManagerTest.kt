package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
