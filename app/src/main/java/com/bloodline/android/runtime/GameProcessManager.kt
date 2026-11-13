package com.bloodline.android.runtime

import com.bloodline.android.runtime.process.Termination

/**
 * Starts the game process, watches it, and reports how it ended.
 *
 * This is the module Android makes awkward, for two reasons that shaped it:
 *
 * 1. **Android 12+ kills an app's child processes past a fixed cap.** Wine does
 *    not start one process, it starts a small tree: a server, device services,
 *    then the game. If the tree is reaped, the game vanishes with *no exit
 *    status at all*. A process manager that only understands exit codes reports
 *    "the game closed" and loses the actual cause, so [ExitReason.REAPED] and
 *    [ExitReason.MISSING] exist as first-class outcomes.
 * 2. **The process is a black box for the whole of its run.** There is no
 *    "is it rendering yet?" signal from a translated Windows game. The manager
 *    therefore reports *transitions* — started, still alive, gone — and leaves
 *    interpretation to [RuntimeDiagnostics].
 *
 * Nothing here spawns a process yet. The state machine is real and tested; the
 * actual `fork`/`exec` waits for binaries to exist.
 */
class GameProcessManager(
    /**
     * A **monotonic** milliseconds source, such as Android's
     * `SystemClock.elapsedRealtime()`.
     *
     * Monotonic matters here and the default is not: wall-clock time jumps
     * when the device syncs, when the user changes it, and across sleep. A
     * session length measured with it can come out negative, or zero for a
     * process that ran for minutes — and a run that "lasted 0s" is the
     * difference between "it was killed instantly" and "the clock is lying".
     * The default is wall-clock only because the unit tests run on a JVM with
     * no Android clock; the real wiring injects the monotonic one.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What the manager believes is happening. */
    sealed interface State {

        /** Nothing has been started. */
        data object Idle : State

        /** The process was started and has not been reaped yet. */
        data class Running(
            val startMillis: Long,
            val pid: Long? = null,
            val command: List<String> = emptyList(),
        ) : State

        /** The process ended, and we know why. */
        data class Exited(val result: ExitResult) : State

        /** The launch itself never happened. */
        data class Failed(val reason: String) : State
    }

    /** Why a run ended. */
    enum class ExitReason {
        /** The game closed normally. */
        CLEAN,

        /** The process ended with a non-zero code. */
        ERROR_CODE,

        /** The process was killed by a signal: a crash, or Android reclaiming it. */
        SIGNAL,

        /** The process disappeared without ever producing an exit status. */
        REAPED,

        /** It was still running at our deadline, so we stopped it. */
        TIMED_OUT,

        /** The manager was told to stop it. */
        STOPPED,

        /** The process was never there to begin with. */
        MISSING,
    }

    /**
     * The record of one run, written into diagnostics verbatim.
     *
     * Everything here is evidence, and nothing here is an interpretation
     * pretending to be one: [pid] and [command] say *what* was running,
     * [exitCode] and [signal] say *how* it ended, and [termination] is the
     * reading of those two, decided by [Termination.of].
     */
    data class ExitResult(
        val reason: ExitReason,
        val termination: Termination,
        val exitCode: Int?,
        val signal: Int?,
        val durationMillis: Long,
        /** The process id, when the platform would tell us. */
        val pid: Long?,
        /** The exact command that was started, argument for argument. */
        val command: List<String>,
    ) {

        /** The signal by name, e.g. `SIGSEGV`, or null when it simply exited. */
        val signalName: String? get() = signal?.let { Termination.signalName(it) }

        /** One sentence for a person, derived from the evidence above. */
        fun describe(): String = Termination.describe(termination, exitCode, signal)
    }

    @Volatile
    private var current: State = State.Idle

    fun state(): State = current

    val isRunning: Boolean get() = current is State.Running

    /**
     * Records that a launch was attempted.
     *
     * @return false, with a [State.Failed], when the launch cannot even be
     *         attempted: no command, or a start that arrived while a game was
     *         already running. The caller is expected to surface [State] to the
     *         user rather than log and carry on.
     */
    fun onLaunch(command: List<String>, pid: Long? = null): Boolean {
        if (command.isEmpty()) {
            current = State.Failed("There is no command to run: the runtime binaries are missing.")
            return false
        }
        val state = current
        if (state is State.Running) {
            current = State.Failed("A game process is already running.")
            return false
        }
        // The command is kept, not just checked. "What exactly did you run?"
        // is the first question in any report, and by the time it is asked the
        // plan that built this list may already have been replaced.
        current = State.Running(clock(), pid, command)
        return true
    }

    /** Records a normal end, with the exit status the process gave. */
    fun onExit(exitCode: Int, signal: Int? = null): ExitResult {
        // The signal decides the reading, so it is derived when the caller
        // did not work it out. A launcher that reports 139 as an exit code
        // turns a segmentation fault into "an error", which is the most
        // misleading thing it could say.
        val killed = signal ?: Termination.signalFromExitCode(exitCode)
        val termination = Termination.of(exitCode, killed)
        val result = ExitResult(
            reason = when (termination) {
                Termination.NORMAL -> ExitReason.CLEAN
                Termination.CRASHED, Termination.KILLED_BY_SYSTEM -> ExitReason.SIGNAL
                Termination.TERMINATED -> ExitReason.STOPPED
                else -> ExitReason.ERROR_CODE
            },
            termination = termination,
            exitCode = exitCode.takeIf { killed == null },
            signal = killed,
            durationMillis = elapsedMillis(),
            pid = runningPid(),
            command = runningCommand(),
        )
        current = State.Exited(result)
        return result
    }

    /**
     * Records that the process vanished without an exit status.
     *
     * This is the phantom-process case, and it is worth distinguishing from a
     * crash: a crash tells you about the game, a reaped process tells you about
     * Android's process limits.
     */
    /**
     * Records that the process vanished without an exit status.
     *
     * Kept separate from a timeout and from a failed `exec`, which it used to
     * swallow. Those three all mean "no status", but they do not mean the
     * same thing: a timeout is *our* deadline, a failed exec is a missing
     * binary, and this is Android having taken the child away. Reporting all
     * three as one is how a missing file gets blamed on the OS.
     */
    fun onReaped(): ExitResult = ended(Termination.VANISHED, ExitReason.REAPED)

    /** Records that we killed it at our own deadline. */
    fun onTimedOut(timeoutMs: Long): ExitResult {
        lastError = "the game was still running after ${timeoutMs}ms"
        return ended(Termination.TIMED_OUT, ExitReason.TIMED_OUT)
    }

    /** Records that we asked it to stop. */
    fun onStopped(): ExitResult = ended(Termination.STOPPED, ExitReason.STOPPED)

    /**
     * Records that no process was ever created.
     *
     * Terminal, unlike the others: there is nothing to have ended, so the
     * state is a failure rather than an exit. It used to be followed by a
     * call to [onReaped] as well, which overwrote this reason and threw away
     * the thing that actually went wrong.
     */
    fun onMissing(detail: String): State.Failed {
        val failed = State.Failed(detail)
        current = failed
        return failed
    }

    /**
     * Records that `exec` never happened, so no process ever existed.
     *
     * Returns the record as well as setting the failure state, because the
     * caller has to put something on the screen and should not have to invent
     * it. This used to record the failure and then immediately overwrite it
     * with "reaped", throwing away the reason — the only useful part.
     */
    fun onLaunchFailed(reason: String): ExitResult {
        lastError = reason
        val result = ExitResult(
            reason = ExitReason.MISSING,
            termination = Termination.LAUNCH_FAILED,
            exitCode = null,
            signal = null,
            durationMillis = elapsedMillis(),
            pid = runningPid(),
            command = runningCommand(),
        )
        current = State.Failed(reason)
        return result
    }

    /**
     * Records the process id once the process actually exists.
     *
     * The id is only known after `fork`, which is after [onLaunch], so it
     * arrives separately. It is worth the second call: a tombstone in logcat
     * can only be tied to this run through it.
     */
    fun attachPid(pid: Long?) {
        val state = current
        if (state is State.Running) current = state.copy(pid = pid)
    }

    /** Why the run ended, when [onMissing] was not the answer. */
    var lastError: String? = null
        private set

    private fun ended(termination: Termination, reason: ExitReason): ExitResult {
        val result = ExitResult(
            reason = reason,
            termination = termination,
            exitCode = null,
            signal = null,
            durationMillis = elapsedMillis(),
            pid = runningPid(),
            command = runningCommand(),
        )
        current = State.Exited(result)
        return result
    }

    private fun runningPid(): Long? = (current as? State.Running)?.pid

    private fun runningCommand(): List<String> = (current as? State.Running)?.command.orEmpty()

    /** Back to [State.Idle] once the launcher has been restored. */
    fun reset() {
        current = State.Idle
    }

    private fun elapsedMillis(): Long {
        val state = current
        val start = if (state is State.Running) state.startMillis else return 0L
        return (clock() - start).coerceAtLeast(0L)
    }
}
