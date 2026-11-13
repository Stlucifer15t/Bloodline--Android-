package com.bloodline.android.runtime

import com.bloodline.android.runtime.process.Termination
import java.io.File
import java.io.IOException

/**
 * Remembers how the last game run ended, across restarts of the app.
 *
 * The reason this exists: a crash is investigated *after* it happens, and
 * often after the app has been killed and restarted — which is exactly when
 * the in-memory record is gone. A report generated on request then says
 * "the previous session did not finish" and nothing else, because the only
 * thing that knew what happened died with the process.
 *
 * So the record is written the moment a run ends, and read back the next
 * time a report is built. Plain `key=value` lines, in the app's own storage,
 * holding only measurements: no paths, no personal data.
 */
class LastRunStore(private val file: File) {

    /** Writes [result], replacing whatever was there. */
    fun save(result: GameProcessManager.ExitResult) {
        val lines = buildList {
            add("${KEY_REASON}=${result.reason.name}")
            add("${KEY_TERMINATION}=${result.termination.name}")
            result.exitCode?.let { add("$KEY_EXIT_CODE=$it") }
            result.signal?.let { add("$KEY_SIGNAL=$it") }
            add("$KEY_DURATION=${result.durationMillis}")
            result.pid?.let { add("$KEY_PID=$it") }
            add("$KEY_COMMAND_COUNT=${result.command.size}")
            result.command.forEachIndexed { index, argument ->
                add("$KEY_COMMAND_PREFIX$index=$argument")
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n"))
        }
    }

    /**
     * The last recorded run, or null when there has not been one.
     *
     * Returns null rather than a half-built record: a run whose reason cannot
     * be read is not evidence, and inventing a default would put a
     * fabrication in a report that someone is going to act on.
     */
    fun load(): GameProcessManager.ExitResult? {
        val values = readValues() ?: return null

        val reason = values[KEY_REASON]
            ?.let { runCatching { GameProcessManager.ExitReason.valueOf(it) }.getOrNull() }
            ?: return null
        val termination = values[KEY_TERMINATION]
            ?.let { runCatching { Termination.valueOf(it) }.getOrNull() }
            ?: Termination.of(values[KEY_EXIT_CODE]?.toIntOrNull(), values[KEY_SIGNAL]?.toIntOrNull())

        val count = values[KEY_COMMAND_COUNT]?.toIntOrNull() ?: 0
        val command = (0 until count).mapNotNull { index -> values["$KEY_COMMAND_PREFIX$index"] }

        return GameProcessManager.ExitResult(
            reason = reason,
            termination = termination,
            exitCode = values[KEY_EXIT_CODE]?.toIntOrNull(),
            signal = values[KEY_SIGNAL]?.toIntOrNull(),
            durationMillis = values[KEY_DURATION]?.toLongOrNull() ?: 0L,
            pid = values[KEY_PID]?.toLongOrNull(),
            command = command,
        )
    }

    /** Forgets the last run. Used when the record has been reported on. */
    fun clear() {
        runCatching { file.delete() }
    }

    private fun readValues(): Map<String, String>? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (ignored: IOException) {
            return null
        }
        val values = text.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        return values.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val KEY_REASON = "reason"
        const val KEY_TERMINATION = "termination"
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_SIGNAL = "signal"
        const val KEY_DURATION = "durationMillis"
        const val KEY_PID = "pid"
        const val KEY_COMMAND_COUNT = "command.count"
        const val KEY_COMMAND_PREFIX = "command."
    }
}
