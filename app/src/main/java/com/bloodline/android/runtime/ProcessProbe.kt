package com.bloodline.android.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs and interprets the process management probe
 * (`lib*.so` built from `app/src/main/cpp/process_probe.c`).
 *
 * This is the least glamorous and most load-bearing measurement in the runtime.
 * Wine does not start one process, it starts a tree — a server, device services,
 * and the game — and Android 12 and later kill an app's children once it passes
 * a fixed cap. The victim does not exit, it simply disappears, with no status to
 * report. So three things have to be true before a launch is meaningful:
 *
 * * `fork` → `exec` → `waitpid` returns a real exit status [execWaitWorks];
 * * a small tree of children can be started and every one of them reaped
 *   [treeWorks] — if children vanish here, they will vanish during a game;
 * * the process cap is known [processLimit], so the runtime can tell the
 *   difference between "the game crashed" and "Android killed it".
 *
 * The cap is *read* from `/proc/self/limits`, never discovered by spawning
 * processes until something dies: deliberately provoking the killer on someone's
 * phone would be a hostile way to run a diagnostic.
 */
object ProcessProbe {

    /** Name the probe is packaged under; see CMakeLists.txt. */
    const val LIBRARY_NAME = "libprocess_probe.so"

    /** Printed by the probe when it ran. */
    const val SUCCESS_MARKER = "process-probe: ok"

    private const val TIMEOUT_SECONDS = 30L

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)

    fun isSuccessful(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.contains(SUCCESS_MARKER)

    /** Whether fork/exec/waitpid returned a proper exit status. */
    fun execWaitWorks(output: String): Boolean? =
        flag(output, "exec-wait:")

    /** Whether every child of the test tree was reaped. */
    fun treeWorks(output: String): Boolean? =
        flag(output, "process-tree:")

    /** How many children vanished before they could be reaped. 0 is the good answer. */
    fun lostChildren(output: String): Int? =
        intValue(output, "process-tree-lost:")

    /**
     * The soft process limit, or 0 when it could not be read.
     *
     * Android 12's cap is 32. A device reporting less than that is a real
     * constraint on the runtime, not a rounding detail.
     */
    fun processLimit(output: String): Int =
        intValue(output, "max-processes-soft:")
            ?: intValue(output, "max-processes-hard:")
            ?: 0

    /** One line for the launcher. */
    fun summary(output: String): String {
        if (!output.contains(SUCCESS_MARKER)) return "not measured"

        val parts = mutableListOf<String>()
        when (execWaitWorks(output)) {
            true -> parts += "exec and wait work"
            false -> parts += "exec or wait is blocked"
            null -> Unit
        }
        when (treeWorks(output)) {
            true -> parts += "child processes survive"
            false -> parts += "children are lost"
            null -> Unit
        }
        processLimit(output).takeIf { it > 0 }?.let { parts += "limit $it processes" }
        return parts.joinToString(" · ").ifEmpty { "measured" }
    }

    /** Runs the probe and waits for it. Keep this off the main thread. */
    fun run(context: Context): Result {
        val executable = binary(context)
        if (!executable.exists()) {
            return Result(
                exitCode = EXIT_MISSING,
                output = "Process probe not found at ${executable.absolutePath}\n" +
                    "The APK was built without the process probe for this ABI.",
            )
        }

        return try {
            val process = ProcessBuilder(executable.absolutePath)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result(EXIT_TIMEOUT, "$output\n(the process probe did not finish in time)")
            } else {
                Result(process.exitValue(), output)
            }
        } catch (error: Exception) {
            Result(EXIT_FAILED, "Could not start the process probe: ${error.message}")
        }
    }

    private fun flag(output: String, key: String): Boolean? = when {
        output.lineSequence().any { it.startsWith("$key ALLOWED") } -> true
        output.lineSequence().any { it.startsWith(key) } -> false
        else -> null
    }

    private fun intValue(output: String, key: String): Int? =
        output.lineSequence()
            .firstOrNull { it.startsWith(key) }
            ?.substringAfter(key)
            ?.trim()
            ?.toIntOrNull()

    /** Exit codes the runner itself uses when the probe never really ran. */
    const val EXIT_MISSING = -1
    const val EXIT_TIMEOUT = -2
    const val EXIT_FAILED = -3

    data class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val isSuccessful: Boolean get() = isSuccessful(exitCode, output)
    }
}
