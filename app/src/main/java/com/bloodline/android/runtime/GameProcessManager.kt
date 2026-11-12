package com.bloodline.android.runtime

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
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What the manager believes is happening. */
    sealed interface State {

        /** Nothing has been started. */
        data object Idle : State

        /** The process was started and has not been reaped yet. */
        data class Running(val startMillis: Long) : State

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

        /** The manager was told to stop it. */
        STOPPED,

        /** The process was never there to begin with. */
        MISSING,
    }

    /** The record of one run, written into diagnostics verbatim. */
    data class ExitResult(
        val reason: ExitReason,
        val exitCode: Int?,
        val signal: Int?,
        val durationMillis: Long,
    )

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
    fun onLaunch(command: List<String>): Boolean {
        if (command.isEmpty()) {
            current = State.Failed("There is no command to run: the runtime binaries are missing.")
            return false
        }
        val state = current
        if (state is State.Running) {
            current = State.Failed("A game process is already running.")
            return false
        }
        current = State.Running(clock())
        return true
    }

    /** Records a normal end, with the exit status the process gave. */
    fun onExit(exitCode: Int, signal: Int? = null): ExitResult {
        val result = ExitResult(
            reason = when {
                signal != null -> ExitReason.SIGNAL
                exitCode == 0 -> ExitReason.CLEAN
                else -> ExitReason.ERROR_CODE
            },
            exitCode = exitCode.takeIf { signal == null },
            signal = signal,
            durationMillis = elapsedMillis(),
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
    fun onReaped(): ExitResult {
        val result = ExitResult(
            reason = ExitReason.REAPED,
            exitCode = null,
            signal = null,
            durationMillis = elapsedMillis(),
        )
        current = State.Exited(result)
        return result
    }

    /** Records that we asked it to stop. */
    fun onStopped(): ExitResult {
        val result = ExitResult(
            reason = ExitReason.STOPPED,
            exitCode = null,
            signal = null,
            durationMillis = elapsedMillis(),
        )
        current = State.Exited(result)
        return result
    }

    /** Records that no process was ever created. */
    fun onMissing(detail: String): State.Failed {
        val failed = State.Failed(detail)
        current = failed
        return failed
    }

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
