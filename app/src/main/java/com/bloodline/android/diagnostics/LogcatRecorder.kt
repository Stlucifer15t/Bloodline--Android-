package com.bloodline.android.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * Continuously captures this app's own log into files.
 *
 * Why bother: since Android 7 an app can only read *its own* log lines, and
 * after a crash the dead process cannot be asked for anything. The only way to
 * still have the previous run's log is to have been writing it all along, which
 * is what this does. When the app dies, the file is already on disk and the next
 * run can attach it to the report.
 *
 * Nothing outside this app's own log is captured, and only the last few
 * sessions are kept.
 */
class LogcatRecorder(
    private val logsDirectory: File,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES,
) {

    @Volatile
    private var running = false

    private var thread: Thread? = null

    /** The file the current session is being written to. */
    val currentLog: File get() = File(logsDirectory, CURRENT_LOG_NAME)

    /**
     * Moves the previous session's log out of the way, so a report can include
     * what the app was doing right before it died.
     */
    fun startNewSession() {
        logsDirectory.mkdirs()
        rotate()
    }

    /**
     * Shifts the older logs along and clears the way for a new one.
     *
     * Called at the start of a session and again every time the current file
     * fills, so the newest lines always survive.
     */
    private fun rotate() {
        // Shift the older files along, oldest dropped.
        for (index in maxFiles - 1 downTo 1) {
            val from = File(logsDirectory, "session.$index.log")
            if (!from.exists()) continue
            if (index >= maxFiles - 1) {
                from.delete()
            } else {
                from.renameTo(File(logsDirectory, "session.${index + 1}.log"))
            }
        }
        currentLog.takeIf { it.exists() }?.renameTo(File(logsDirectory, "session.1.log"))
    }

    /** Begins capturing. Safe to call more than once. */
    fun start() {
        if (running) return
        logsDirectory.mkdirs()
        running = true

        thread = Thread {
            // Two handles on the same writer: [out] is non-null so the write
            // loop needs no null checks, and [writer] is the nullable one the
            // finally block closes whether or not we ever got as far as
            // opening it.
            var writer: BufferedWriter? = null
            var process: Process? = null
            try {
                var out = BufferedWriter(FileWriter(currentLog, true))
                writer = out
                process = ProcessBuilder("logcat", "-v", "time")
                    .redirectErrorStream(true)
                    .start()

                process.inputStream.bufferedReader().useLines { lines ->
                    var written = 0L
                    for (line in lines) {
                        if (!running) break

                        // Roll over at the cap instead of stopping dead.
                        //
                        // This used to `break`, which kept the FIRST n bytes
                        // and threw away everything after. For a log that is
                        // the wrong end: the interesting part of a crash is
                        // the last thing that happened, and a session that
                        // ran long enough to fill one file lost precisely
                        // the lines describing how it ended. The first log
                        // off a real device stopped mid-prefix and never
                        // recorded the game starting, let alone dying.
                        //
                        // Storage is still bounded: only [maxFiles] files
                        // are kept, and the oldest is dropped on each roll.
                        if (written > maxBytesPerFile) {
                            runCatching { out.flush() }
                            runCatching { out.close() }
                            rotate()
                            out = BufferedWriter(FileWriter(currentLog, true))
                            writer = out
                            written = 0L
                        }

                        out.write(line)
                        out.newLine()
                        written += line.length + 1
                    }
                }
            } catch (ignored: IOException) {
                // Capturing logs is best effort: a device that refuses logcat
                // simply gets reports without them.
            } finally {
                runCatching { writer?.flush() }
                runCatching { writer?.close() }
                process?.destroy()
            }
        }.apply {
            isDaemon = true
            name = "bloodlines-logcat"
            start()
        }
    }

    /** Stops capturing. Called when the app is being closed deliberately. */
    fun stop() {
        running = false
        runCatching { thread?.interrupt() }
        thread = null
    }

    /** Every captured log file, newest first. */
    fun logFiles(): List<File> {
        val files = logsDirectory.listFiles()?.toMutableList() ?: return emptyList()
        return files.filter { it.isFile && it.name.startsWith("session") && it.name.endsWith(".log") }
            .sortedBy { it.name }
            .let { ordered -> currentLog.takeIf { it.exists() }?.let { listOf(it) + ordered } ?: ordered }
    }

    companion object {
        const val DEFAULT_MAX_FILES = 4
        const val DEFAULT_MAX_BYTES = 512L * 1024L
        const val CURRENT_LOG_NAME = "session.log"
    }
}
