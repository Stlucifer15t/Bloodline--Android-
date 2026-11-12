package com.bloodline.android.runtime.process

import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * Starts a process and reports what happened to it.
 *
 * Until this existed, nothing in the runtime executed anything: the process
 * manager recorded the *shape* of a launch and nothing more. Everything below
 * is about the difference between starting a process and having an opinion
 * about whether it worked.
 *
 * Four things here are load-bearing rather than incidental:
 *
 * 1. **stderr is merged into stdout before the streams are read.** The classic
 *    `ProcessBuilder` deadlock: a child writes more to stderr than the pipe
 *    buffer holds, the parent is blocked reading stdout, and both wait forever.
 *    Wine is extremely talkative on stderr, so this is not theoretical — it is
 *    the default outcome of doing it the obvious way.
 * 2. **Output is bounded.** A runaway process can emit gigabytes. The last
 *    [Request.maxOutputLines] lines are kept, which is what a diagnostics
 *    report needs, and memory use stays flat.
 * 3. **There is a timeout.** A Wine prefix that hangs on a network prompt hangs
 *    forever otherwise.
 * 4. **Failures are translated.** An `exec` that fails with ENOENT usually
 *    means the binary's *loader* is missing, not the binary — which is exactly
 *    what happens when a glibc build is run on Android. EACCES on a file we
 *    extracted ourselves means the platform will not execute from that
 *    directory. Saying "No such file or directory" for either sends the
 *    investigation to the wrong place entirely.
 */
/**
 * Runs one command and reports what happened.
 *
 * Split out from [ProcessRunner] so that the code which *decides* what to run
 * can be tested without executing it. Wine's initialisation is a sequence of
 * commands whose order and arguments matter, and none of that should be
 * unverifiable until a device is available.
 */
interface CommandRunner {
    fun run(request: ProcessRunner.Request, onLine: (String) -> Unit = {}): ProcessRunner.Outcome

    /**
     * Starts a process without waiting for it.
     *
     * @param onExit called from a thread of the runner's choosing, not the
     *        caller's.
     */
    fun launch(
        request: ProcessRunner.Request,
        onLine: (String) -> Unit = {},
        onExit: (ProcessRunner.Outcome) -> Unit,
    ): ProcessRunner.Handle
}

/**
 * [CommandRunner] backed by a real [Process].
 *
 * See the notes on deadlock, bounded output, timeouts and error translation
 * below: they are the substance of this class, not decoration.
 */
class ProcessRunner : CommandRunner {

    /** What to run. */
    data class Request(
        val command: List<String>,
        val environment: Map<String, String> = emptyMap(),
        val workingDirectory: File? = null,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** How many trailing output lines to keep for the report. */
        val maxOutputLines: Int = MAX_OUTPUT_LINES,
    )

    /** What happened. */
    sealed interface Outcome {

        /** The process ran and finished. */
        data class Exited(
            val exitCode: Int,
            val output: List<String>,
            val durationMillis: Long,
        ) : Outcome {

            val succeeded: Boolean get() = exitCode == 0

            /**
             * The signal that killed it, or null when it really did exit.
             *
             * A process killed by a signal comes back from `waitFor` as
             * 128 + signal, so 139 is not "exited with 139": it is signal 11,
             * SIGSEGV, a segmentation fault. Printing the raw integer leaves
             * the one person who has to act on it reading a number and
             * guessing, which is how a Wine crash gets mistaken for a
             * launcher bug.
             */
            val signal: Int? get() = if (exitCode in 129..191) exitCode - 128 else null
        }

        /** It was still running when the timeout expired, and was killed. */
        data class TimedOut(
            val output: List<String>,
            val timeoutMs: Long,
        ) : Outcome

        /** It never started. */
        data class Failed(
            val reason: String,
            val output: List<String> = emptyList(),
        ) : Outcome

        /** One line for a report. */
        fun describe(): String = when (this) {
            is Exited -> {
                val killed = signal
                if (killed != null) {
                    "crashed with ${SIGNAL_NAMES[killed] ?: "signal $killed"} " +
                        "after ${durationMillis}ms"
                } else {
                    "exited with $exitCode after ${durationMillis}ms"
                }
            }
            is TimedOut -> "was killed after ${timeoutMs}ms with no exit status"
            is Failed -> "did not start: $reason"
        }

        /**
         * What killed a process, for the handful of signals that turn up here.
         *
         * A crash is reported by signal, not by exit code: 139 means SIGSEGV,
         * and "crashed with SIGSEGV" is the sentence that tells someone the
         * program touched memory it did not own, where "exited with 139"
         * tells them nothing.
         */
        private val SIGNAL_NAMES = mapOf(
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
        )
    }

    /** A process that is still running, so a caller can stop it. */
    fun interface Handle {
        fun cancel()
    }

    /**
     * Runs [request] and waits for it to finish, up to its timeout.
     *
     * @param onLine called with each output line as it arrives, so a caller can
     *               stream progress without holding the whole thing.
     */
    override fun run(request: Request, onLine: (String) -> Unit): Outcome {
        val process = try {
            start(request)
        } catch (error: IOException) {
            return Outcome.Failed(translate(error, request))
        }

        val startedAt = System.currentTimeMillis()
        val output = BoundedLines(request.maxOutputLines)

        // Drained on a thread of its own, and this is the part that is easy to
        // get wrong. Reading the stream inline and *then* applying a timeout
        // looks right and is not: reading blocks until the child closes the
        // pipe, so a process that hangs without exiting hangs us, and the
        // timeout never gets a chance to fire. The wait has to be the thing
        // the timeout applies to, with the drain happening alongside it.
        val reader = Thread({
            drain(process, output, onLine)
        }, "bloodline-output").apply {
            isDaemon = true
            start()
        }

        return try {
            val finished = process.waitFor(request.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val duration = System.currentTimeMillis() - startedAt

            if (!finished) {
                process.destroyForcibly()
                reader.join(DRAIN_JOIN_MS)
                Outcome.TimedOut(output.toList(), request.timeoutMs)
            } else {
                // The process has gone, so the pipe is at or near EOF. Bounded
                // rather than open-ended: a child that forked and left a
                // grandchild holding the pipe open must not wedge the launcher.
                reader.join(DRAIN_JOIN_MS)
                Outcome.Exited(process.exitValue(), output.toList(), duration)
            }
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            Outcome.Failed("the wait was interrupted")
        }
    }

    /** Copies the merged stream into [output] until the pipe closes. */
    private fun drain(process: Process, output: BoundedLines, onLine: (String) -> Unit) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.add(line)
                    onLine(line)
                }
            }
        } catch (error: IOException) {
            // The child died mid-line. There is nothing more to read and
            // nothing useful to add: the exit status is the real answer.
        }
    }

    /**
     * Starts [request] without waiting, reporting to [onExit] from a thread of
     * its own.
     *
     * This is what the game itself uses: it runs for hours, and nothing may
     * block on it.
     */
    override fun launch(
        request: Request,
        onLine: (String) -> Unit,
        onExit: (Outcome) -> Unit,
    ): Handle {
        val process = try {
            start(request)
        } catch (error: IOException) {
            onExit(Outcome.Failed(translate(error, request)))
            return Handle { }
        }

        val thread = Thread({
            val output = BoundedLines(request.maxOutputLines)
            val startedAt = System.currentTimeMillis()
            val outcome = try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.add(line)
                        onLine(line)
                    }
                }
                process.waitFor()
                Outcome.Exited(process.exitValue(), output.toList(), System.currentTimeMillis() - startedAt)
            } catch (error: Exception) {
                Outcome.Failed(translate(error, request), output.toList())
            }
            onExit(outcome)
        }, "bloodline-runtime").apply { isDaemon = true }

        thread.start()
        return Handle { process.destroyForcibly() }
    }

    private fun start(request: Request): Process {
        require(request.command.isNotEmpty()) { "There is no command to run." }

        return ProcessBuilder(request.command).apply {
            // Inherited, then overridden: the guest needs a working PATH and
            // HOME before it needs anything we set.
            environment().putAll(request.environment)
            if (request.workingDirectory != null) directory(request.workingDirectory)
            redirectErrorStream(true)
        }.start()
    }

    /**
     * Turns an exception into a sentence a person can act on.
     *
     * `exec` reports a missing *loader* as "No such file or directory" for the
     * binary you named, and reports a noexec mount as "Permission denied" for
     * a file that is plainly there and plainly readable. Both are reported as
     * written, and then explained, because the literal message is the thing
     * that will appear in a log someone else reads.
     */
    private fun translate(error: Exception, request: Request): String {
        val executable = request.command.firstOrNull().orEmpty()
        val raw = error.message ?: error::class.simpleName.orEmpty()

        val hint = when {
            raw.contains("Permission denied", ignoreCase = true) ->
                " Android will not execute a program from that directory. This is the " +
                    "W^X restriction, which applies to any app targeting API 29 or newer."
            raw.contains("No such file or directory", ignoreCase = true) ->
                " Either the file is missing, or the loader it names in its ELF header " +
                    "is. A Wine built against glibc reports this on Android, which has " +
                    "no glibc."
            raw.contains("not executable", ignoreCase = true) ->
                " The file was extracted without an execute bit."
            else -> ""
        }

        return "Running ${File(executable).name} failed: $raw.$hint"
    }

    /** Keeps the last [limit] lines and forgets the rest. */
    private class BoundedLines(private val limit: Int) {
        private val lines = ArrayDeque<String>(limit.coerceAtLeast(1))

        fun add(line: String) {
            if (lines.size >= limit) lines.removeFirst()
            lines.addLast(line)
        }

        fun toList(): List<String> = lines.toList()
    }

    private companion object {
        /** Long enough for a `wineboot` on a slow device, short enough to notice. */
        const val DEFAULT_TIMEOUT_MS = 180_000L

        const val MAX_OUTPUT_LINES = 200

        /**
         * How long to give the reader thread to notice the pipe has closed.
         *
         * A grandchild left holding the pipe open would otherwise keep the
         * launcher waiting after the process it started has gone.
         */
        const val DRAIN_JOIN_MS = 2_000L
    }
}
