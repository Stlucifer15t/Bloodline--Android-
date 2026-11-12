package com.bloodline.android.runtime.process

import com.bloodline.android.runtime.process.ProcessRunner.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests that run real processes.
 *
 * These are the tests in this project that are not about our own bookkeeping:
 * they start actual programs and check what came back. That is the whole point
 * of the class under test, and a fake would only confirm that we call the
 * methods we thought we called.
 *
 * They are also the honest limit of what can be verified here. Every command
 * below is a shell on this machine. None of them is Wine, and none of them is
 * the game: what is proven is that this runner starts a process, drains its
 * output without deadlocking, honours a timeout, and reports an exit status
 * truthfully. What Wine does with that is a separate question, answerable only
 * on a device.
 *
 * Each test returns early when the host has no `/bin/sh`, so they run on any
 * machine the suite runs on and do real work where it matters.
 */
class ProcessRunnerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val runner = ProcessRunner()

    private fun shell(script: String, timeoutMs: Long = 30_000L): Outcome =
        runner.run(
            ProcessRunner.Request(
                command = listOf("/bin/sh", "-c", script),
                timeoutMs = timeoutMs,
            ),
        )

    @Test
    fun `reports the exit code a program finished with`() {
        if (!hasShell()) return

        val outcome = shell("exit 3")

        assertTrue(outcome is Outcome.Exited)
        assertEquals(3, (outcome as Outcome.Exited).exitCode)
        assertFalse(outcome.succeeded)
        assertTrue(outcome.describe().contains("exited with 3"))
    }

    @Test
    fun `a program that succeeds is reported as succeeding`() {
        if (!hasShell()) return

        val outcome = shell("exit 0")

        assertTrue(outcome is Outcome.Exited)
        assertTrue((outcome as Outcome.Exited).succeeded)
    }

    @Test
    fun `captures output from both stdout and stderr`() {
        if (!hasShell()) return

        val outcome = shell("echo on-stdout; echo on-stderr 1>&2") as Outcome.Exited

        assertTrue(outcome.output.joinToString(), outcome.output.contains("on-stdout"))
        assertTrue(outcome.output.joinToString(), outcome.output.contains("on-stderr"))
    }

    @Test
    fun `does not deadlock on a program that floods stderr`() {
        // The failure this class exists to avoid. A child that writes more to
        // stderr than the pipe holds blocks until someone reads it; a parent
        // reading only stdout then waits forever. Merging the streams before
        // reading is what stops it, and a large write is what proves it: the
        // pipe buffer is 64 KB and this writes well past a megabyte.
        if (!hasShell()) return

        val outcome = shell(
            "i=0; while [ \$i -lt 20000 ]; do " +
                "echo xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx 1>&2; " +
                "i=\$((i+1)); done; echo finished",
            timeoutMs = 60_000L,
        )

        assertTrue(outcome.toString(), outcome is Outcome.Exited)
        assertEquals(0, (outcome as Outcome.Exited).exitCode)
    }

    @Test
    fun `a program that runs past its timeout is killed and said to have timed out`() {
        if (!hasShell()) return

        val outcome = shell("sleep 30", timeoutMs = 500L)

        assertTrue(outcome.toString(), outcome is Outcome.TimedOut)
        assertEquals(500L, (outcome as Outcome.TimedOut).timeoutMs)
        assertTrue(outcome.describe().contains("killed"))
    }

    @Test
    fun `a program that does not exist is reported rather than thrown`() {
        val outcome = runner.run(
            ProcessRunner.Request(command = listOf("/nonexistent/definitely-not-here")),
        )

        assertTrue(outcome is Outcome.Failed)
        assertTrue(
            (outcome as Outcome.Failed).reason,
            outcome.reason.contains("No such file or directory"),
        )
        // The explanation is the point: ENOENT on exec usually means the
        // loader, not the file.
        assertTrue(outcome.reason, outcome.reason.contains("loader"))
    }

    @Test
    fun `a file with no execute bit is explained rather than merely reported`() {
        if (!hasShell()) return

        val script = temporary.newFile("not-executable.sh")
        script.writeText("#!/bin/sh\necho hi\n")
        script.setExecutable(false)

        val outcome = runner.run(ProcessRunner.Request(command = listOf(script.absolutePath)))

        assertTrue(outcome is Outcome.Failed)
        assertTrue(
            (outcome as Outcome.Failed).reason,
            outcome.reason.contains("Permission denied"),
        )
        assertTrue(outcome.reason, outcome.reason.contains("execute"))
    }

    @Test
    fun `keeps the tail of a long output rather than growing without bound`() {
        if (!hasShell()) return

        val outcome = runner.run(
            ProcessRunner.Request(
                command = listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 1000 ]; do echo line\$i; i=\$((i+1)); done"),
                maxOutputLines = 10,
            ),
        ) as Outcome.Exited

        assertEquals(10, outcome.output.size)
        assertTrue(outcome.output.last().contains("line999"))
        assertFalse(outcome.output.joinToString().contains("line0\n"))
    }

    @Test
    fun `launch reports the exit status from a thread of its own`() {
        if (!hasShell()) return
        val done = CountDownLatch(1)
        var reported: Outcome? = null

        val handle = runner.launch(
            ProcessRunner.Request(command = listOf("/bin/sh", "-c", "echo started; exit 7")),
            onExit = { outcome ->
                reported = outcome
                done.countDown()
            },
        )

        assertTrue("the exit was reported within ten seconds", done.await(10, TimeUnit.SECONDS))
        val outcome = reported
        assertTrue(outcome is Outcome.Exited)
        assertEquals(7, (outcome as Outcome.Exited).exitCode)
        assertTrue(outcome.output.contains("started"))
        handle.cancel()
    }

    private fun hasShell(): Boolean = File("/bin/sh").isFile
}
