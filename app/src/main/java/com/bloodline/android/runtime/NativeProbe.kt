package com.bloodline.android.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled native probe and interprets what it says.
 *
 * The probe (`libnative_probe.so` in `app/src/main/cpp`) is an ordinary
 * executable shipped under the APK's library folder, because Android 10+ forbids
 * executing anything out of writable app storage. Running it successfully is the
 * first real proof that the Wine/Box86 stack can one day run at all.
 */
object NativeProbe {

    /** Name the probe is packaged under; see CMakeLists.txt. */
    const val LIBRARY_NAME = "libnative_probe.so"

    /** Printed by the probe on a successful run. */
    const val SUCCESS_MARKER = "native-probe: ok"

    private const val TIMEOUT_SECONDS = 20L

    /** Where the package installer extracted the probe. */
    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)

    /** The probe only counts as working when it exited cleanly and announced itself. */
    fun isSuccessful(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.contains(SUCCESS_MARKER)

    /** Short "arm64-v8a · API 34" style summary taken from the probe output. */
    fun summary(output: String): String {
        val values = listOfNotNull(
            value(output, "abi:"),
            value(output, "api-level:")?.let { "API $it" },
        )
        return values.joinToString(" · ")
    }

    /** What the probe concluded about executing files from writable app storage. */
    fun execFromAppDataVerdict(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.contains("exec-from-app-data:") }
            ?: return null
        return when {
            line.contains("ALLOWED") -> "files in app storage can be executed"
            line.contains("DENIED") -> "blocked by W^X, as expected"
            else -> line.substringAfter("exec-from-app-data:").trim()
        }
    }

    private fun value(output: String, key: String): String? =
        output.lineSequence()
            .firstOrNull { it.contains(key) }
            ?.substringAfter(key)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Runs the probe and waits for it.
     *
     * @param writableDirectory an app owned directory (the probe copies itself
     *                          there to test whether executables are allowed).
     */
    fun run(context: Context, writableDirectory: File): Result {
        val executable = binary(context)
        if (!executable.exists()) {
            return Result(
                exitCode = EXIT_MISSING,
                output = "Native probe not found at ${executable.absolutePath}\n" +
                    "The APK was built without the native probe for this ABI.",
            )
        }

        return try {
            val process = ProcessBuilder(executable.absolutePath, writableDirectory.absolutePath)
                .directory(writableDirectory)
                .redirectErrorStream(true)
                .start()

            // The probe prints a couple of kilobytes at most, so reading to the
            // end before waiting cannot fill the pipe and deadlock.
            val output = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result(EXIT_TIMEOUT, "$output\n(the probe did not finish in time)")
            } else {
                Result(process.exitValue(), output)
            }
        } catch (error: Exception) {
            Result(EXIT_FAILED, "Could not start the native probe: ${error.message}")
        }
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
