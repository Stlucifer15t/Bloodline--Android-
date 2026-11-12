package com.bloodline.android.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs and interprets the runtime capability probe
 * (`lib*.so` built from `app/src/main/cpp/runtime_probe.c`).
 *
 * Where [NativeProbe] answers "can this APK run a bundled executable at all",
 * this answers the questions that decide how the compatibility runtime has to
 * be built: whether one process may trace another (PRoot), whether seccomp is
 * already filtering us, whether a binary can be executed straight out of
 * anonymous memory despite Android's W^X policy, and whether the kernel still
 * accepts 32-bit processes — which Box86 needs, because the game is 32-bit x86
 * and Box86 is itself a 32-bit ARM program.
 *
 * Everything reported here is measured on the device. Nothing is assumed.
 */
object RuntimeProbe {

    /** Name the probe is packaged under; see CMakeLists.txt. */
    const val LIBRARY_NAME = "libruntime_probe.so"

    /** Printed by the probe when it ran. */
    const val SUCCESS_MARKER = "runtime-probe: ok"

    /** Printed by the copy that was executed out of a memfd: proof it worked. */
    const val CHILD_MARKER = "runtime-probe-child: ok"

    private const val TIMEOUT_SECONDS = 30L

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)

    fun isSuccessful(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.contains(SUCCESS_MARKER)

    /** Runs the probe and waits for it. Keep this off the main thread. */
    fun run(context: Context): Result {
        val executable = binary(context)
        if (!executable.exists()) {
            return Result(
                exitCode = EXIT_MISSING,
                output = "Runtime probe not found at ${executable.absolutePath}\n" +
                    "The APK was built without the runtime probe for this ABI.",
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
                Result(EXIT_TIMEOUT, "$output\n(the probe did not finish in time)")
            } else {
                Result(process.exitValue(), output)
            }
        } catch (error: Exception) {
            Result(EXIT_FAILED, "Could not start the runtime probe: ${error.message}")
        }
    }

    /** Every `key: value` line the probe printed, in order. */
    fun facts(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else {
                line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
        }.toMap()

    /** One measured fact, or null when the probe did not report it. */
    fun value(output: String, key: String): String? =
        output.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * A one line summary for the launcher: the facts that decide whether a
     * container can exist on this device at all.
     */
    fun summary(output: String): String {
        if (!output.contains(SUCCESS_MARKER)) return "not measured"

        val parts = mutableListOf<String>()

        value(output, "ptrace")?.let { parts += "ptrace $it" }

        // A successful exec prints nothing: the copy announces itself with its
        // own marker, and its absence means execve came back with an errno.
        val execFromMemory = when {
            output.contains(CHILD_MARKER) -> "ALLOWED"
            else -> value(output, "exec-memfd")
        }
        execFromMemory?.let { parts += "exec from memory $it" }
        value(output, "ro-zygote")?.takeIf { it != "unknown" }?.let { parts += it }
        value(output, "cores")?.let { parts += "$it cores" }
        value(output, "memtotal-mb")?.let { parts += "$it MB RAM" }

        return parts.joinToString(" · ").ifEmpty { "measured" }
    }

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
