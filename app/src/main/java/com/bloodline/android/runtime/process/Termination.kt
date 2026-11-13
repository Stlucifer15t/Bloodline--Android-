package com.bloodline.android.runtime.process

/**
 * Why a process ended, decided from evidence rather than assumed.
 *
 * This exists because the alternative is what the launcher used to do: a
 * process that vanished was reported as "stopped by Android", as a fact,
 * with nothing behind it. That sentence is a guess dressed as a finding,
 * and it sends the next person to look in the wrong place — or, worse,
 * convinces them there is nothing to look at.
 *
 * The distinctions that matter on this platform:
 *
 * - **A crash signal** (SIGSEGV, SIGILL, SIGBUS, SIGABRT, SIGFPE) is the
 *   guest or Wine faulting. It says something about the *program*.
 * - **SIGKILL** is different in kind. Nothing faults into SIGKILL; something
 *   *else* decided the process should die. On Android that is the
 *   low-memory killer or the phantom-process cap, and it says something
 *   about the *system*, not the program.
 * - **No exit status at all** is the phantom-process case: the child was
 *   reaped before anyone could ask. Also about the system.
 * - **A non-zero exit with no signal** is the program's own decision. It
 *   ran, it finished, it disagreed.
 *
 * Collapsing those into one reason is how a Wine crash and an Android
 * process cap end up producing the same useless message.
 */
enum class Termination {

    /** It finished and said so: exit status 0. */
    NORMAL,

    /** It ran, finished, and returned a non-zero status of its own. */
    GUEST_ERROR,

    /** It faulted: a crash signal from inside the program or Wine. */
    CRASHED,

    /** SIGKILL. Something outside the program decided it should die. */
    KILLED_BY_SYSTEM,

    /** SIGTERM or SIGINT: asked to stop, and did. */
    TERMINATED,

    /** We killed it at our own deadline. */
    TIMED_OUT,

    /** `exec` never happened, so no process ever existed. */
    LAUNCH_FAILED,

    /** It was there and then it was not, and no status was ever produced. */
    VANISHED,

    /** We stopped it. */
    STOPPED,

    /** Nothing was started. */
    MISSING,
    ;

    /** True when the process never got as far as running. */
    val neverRan: Boolean get() = this == LAUNCH_FAILED || this == MISSING

    /** True when this says something about the program, not the system. */
    val isProgramFault: Boolean get() = this == GUEST_ERROR || this == CRASHED

    companion object {

        /**
         * Classifies an end from what the process actually gave us.
         *
         * @param exitCode the status from `waitFor`, or null when there was
         *                 no status to be had.
         * @param signal   the signal, when the caller already worked it out.
         *                 Derived from [exitCode] when absent, because a
         *                 process killed by a signal reports `128 + signal`.
         */
        fun of(exitCode: Int?, signal: Int? = null): Termination {
            val killed = signal ?: exitCode?.let { signalFromExitCode(it) }

            if (killed != null) return when (killed) {
                9 -> KILLED_BY_SYSTEM
                15, 2 -> TERMINATED
                else -> CRASHED
            }

            return when (exitCode) {
                null -> VANISHED
                0 -> NORMAL
                else -> GUEST_ERROR
            }
        }

        /**
         * The signal a status encodes, or null when the process really exited.
         *
         * `waitFor` reports a signalled death as `128 + signal`, so 139 is
         * not "exited with 139" — it is SIGSEGV. Reading it as an exit code
         * is the single most misleading thing a launcher can do with it.
         */
        fun signalFromExitCode(exitCode: Int): Int? =
            exitCode.takeIf { it in 129..191 }?.minus(128)

        /** The signal's name, or a plain description when it has none. */
        fun signalName(signal: Int): String = SIGNAL_NAMES[signal] ?: "signal $signal"

        /**
         * One sentence, written for someone who has to act on it.
         *
         * The distinction is stated as a *reading*, not a fact, whenever the
         * evidence cannot settle it. "Consistent with Android's phantom
         * process cap" is honest; "stopped by Android" is not, unless the
         * signal says so.
         */
        fun describe(termination: Termination, exitCode: Int?, signal: Int?): String {
            val named = signal?.let { signalName(it) }
            return when (termination) {
                NORMAL -> "The game closed."
                GUEST_ERROR ->
                    "The game ran and finished with an error (exit $exitCode). " +
                        "The program's own status, not a crash."
                CRASHED ->
                    "The game crashed with $named. This is the program or Wine " +
                        "faulting, not the system reclaiming it."
                KILLED_BY_SYSTEM ->
                    "The game was killed with SIGKILL and never got to report " +
                        "anything. Nothing faults into SIGKILL: something outside " +
                        "the process decided it should die, which on Android is the " +
                        "low-memory killer or the phantom-process cap."
                TERMINATED -> "The game was asked to stop, and did ($named)."
                TIMED_OUT -> "The game was still running at the deadline, so we stopped it."
                LAUNCH_FAILED -> "The game process was never created."
                VANISHED ->
                    "The game process disappeared without ever producing an exit " +
                        "status. That is what being reaped looks like — consistent " +
                        "with Android's phantom-process cap, though the logcat will " +
                        "say more."
                STOPPED -> "Stopped."
                MISSING -> "The game process was never started."
            }
        }

        /**
         * The signals that turn up here.
         *
         * Only the ones that distinguish outcomes are interesting; the rest
         * would be noise. Not a crash dump's dictionary — enough to tell a
         * fault from a kill, which is the whole point.
         */
        private val SIGNAL_NAMES = mapOf(
            1 to "SIGHUP",
            2 to "SIGINT",
            4 to "SIGILL",
            5 to "SIGTRAP",
            6 to "SIGABRT",
            7 to "SIGBUS",
            8 to "SIGFPE",
            9 to "SIGKILL",
            11 to "SIGSEGV",
            13 to "SIGPIPE",
            15 to "SIGTERM",
            31 to "SIGSYS",
        )
    }
}
