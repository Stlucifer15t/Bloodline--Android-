package com.bloodline.android.runtime

import com.bloodline.android.runtime.process.Termination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Surviving the app being killed.
 *
 * These tests pin the property that matters: a run's ending has to be
 * readable *after* the process that saw it is gone, because that is the only
 * time anyone is looking. A report generated on request — the common case —
 * used to say only "the previous session did not finish", with the run
 * itself nowhere to be found.
 */
class LastRunStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store() = LastRunStore(temporary.newFile("last-run.txt"))

    private fun run(exitCode: Int, pid: Long? = 4242L) = GameProcessManager().apply {
        onLaunch(listOf("wine", "Vampire.exe", "-game", "vampire"), pid)
    }.onExit(exitCode)

    @Test
    fun `a crashed run survives being written and read back`() {
        val store = store()
        val original = run(139)

        store.save(original)

        val loaded = store.load()
        checkNotNull(loaded) { "the run should have been readable after a restart" }
        assertEquals(Termination.CRASHED, loaded.termination)
        assertEquals(11, loaded.signal)
        assertEquals("SIGSEGV", loaded.signalName)
        assertEquals(4242L, loaded.pid)
        assertEquals(listOf("wine", "Vampire.exe", "-game", "vampire"), loaded.command)
    }

    @Test
    fun `arguments containing spaces and equals signs come back intact`() {
        // A command is a list, not a string. Joining it naively would mangle
        // any path with a space in it, and a misquoted command in a report is
        // worse than none: it sends someone looking at the wrong file.
        val awkward = GameProcessManager().apply {
            onLaunch(listOf("wine", "/storage/Games/My Game/Vampire.exe", "a=b", "-fullscreen"))
        }.onExit(0)

        val store = store()
        store.save(awkward)

        assertEquals(
            listOf("wine", "/storage/Games/My Game/Vampire.exe", "a=b", "-fullscreen"),
            store.load()?.command,
        )
    }

    @Test
    fun `a run with no exit status is remembered as having none`() {
        // The distinction that started this: no status is not the same as
        // status zero.
        val vanished = GameProcessManager().apply {
            onLaunch(listOf("wine", "Vampire.exe"))
        }.onReaped()

        val store = store()
        store.save(vanished)

        val loaded = store.load()
        assertEquals(Termination.VANISHED, loaded?.termination)
        assertNull(loaded?.exitCode)
        assertNull(loaded?.signal)
    }

    @Test
    fun `there is nothing to read before anything has run`() {
        // Null, rather than a fabricated empty run: an invented default in a
        // report is a lie someone will act on.
        assertNull(store().load())
    }

    @Test
    fun `a record missing its termination is derived from the status, not defaulted`() {
        // The app could die between writing one line and the next. What is
        // recoverable is recovered from the evidence; what is not is left
        // absent, rather than filled in with a plausible-looking guess.
        val file = temporary.newFile("partial.txt")
        file.writeText("reason=SIGNAL\nsignal=9\ndurationMillis=12\n")

        val loaded = LastRunStore(file).load()

        assertEquals(Termination.KILLED_BY_SYSTEM, loaded?.termination)
        assertEquals(9, loaded?.signal)
        assertNull(loaded?.command?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `a run can be forgotten once it has been reported on`() {
        val store = store()
        store.save(run(139))
        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `a corrupt record is refused rather than reported as a crash`() {
        val file = temporary.newFile("corrupt.txt")
        file.writeText("reason=NOT_A_REAL_REASON\ntermination=CRASHED\n")

        assertNull(LastRunStore(file).load())
    }
}
