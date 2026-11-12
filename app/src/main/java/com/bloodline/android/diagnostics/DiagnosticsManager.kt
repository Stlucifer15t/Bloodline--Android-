package com.bloodline.android.diagnostics

import android.content.Context
import android.os.Process
import java.io.File

/** Everything one report needs, gathered by whoever has it to hand. */
data class ReportInputs(
    val settings: Map<String, String> = emptyMap(),
    val runtime: Map<String, String> = emptyMap(),
    val install: Map<String, String> = emptyMap(),
    val nativeProbe: String? = null,
    val runtimeProbe: String? = null,
    /**
     * What the runtime printed while it ran, or null when it has not run since
     * the app started.
     *
     * This is the section that matters most and was the one being thrown away:
     * RuntimeManager takes an onOutput, the launcher was not passing one, and
     * every line Wine wrote went nowhere.
     */
    val runtimeOutput: String? = null,
)

/**
 * Owns session tracking, log capture and the report archive.
 *
 * Lifecycle, as the launcher drives it:
 * - [startSession] at startup: claims any unfinished session and starts logging.
 * - [noteForegrounded] / [noteBackgrounded] as the app comes and goes.
 * - [closeCleanly] when the user actually closes the app, which is the only
 *   moment that counts as a normal shutdown.
 *
 * A report is therefore produced only when the previous run never got to say
 * goodbye: a crash, or Android reclaiming the process.
 */
class DiagnosticsManager(
    private val context: Context,
    private val appVersion: String,
) {

    /** Reports live here, which a file manager can reach. */
    private val reportsDirectory: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "diagnostics/reports",
    )

    /** Logs and the session marker live in ordinary app storage. */
    private val logsDirectory: File = File(context.filesDir, "diagnostics/logs")
    private val stateDirectory: File = File(context.filesDir, "diagnostics/state")

    private val tracker = SessionTracker(stateDirectory, pid = { Process.myPid() })
    private val recorder = LogcatRecorder(logsDirectory)
    val archive = ReportArchive(reportsDirectory)

    @Volatile
    private var sessionStarted = false

    /**
     * Claims the previous run's unfinished session, if there was one, and opens
     * a new one.
     *
     * Only the first call in a process does anything. Recreating the Activity
     * (a rotation, a theme change) is not a new session, and treating it as one
     * would report the running app as a crash.
     */
    fun startSession(): UnfinishedSession? {
        if (sessionStarted) {
            tracker.noteForegrounded()
            return null
        }
        sessionStarted = true

        val unfinished = tracker.takeUnfinished()
        recorder.startNewSession()
        tracker.begin()
        recorder.start()
        return unfinished
    }

    fun noteForegrounded() = tracker.noteForegrounded()

    fun noteBackgrounded() = tracker.noteBackgrounded()

    /** The app is being closed by the user: no report should follow. */
    fun closeCleanly() {
        recorder.stop()
        tracker.finishCleanly()
    }

    fun latestReport(): CrashReport? = archive.latest()

    /** The whole of a report as text. See [ReportArchive.readText]. */
    fun readText(report: CrashReport): String = archive.readText(report)

    fun reportCount(): Int = archive.count()

    fun delete(report: CrashReport) = archive.delete(report)

    fun deleteAll() = archive.deleteAll()

    /**
     * Writes a report. Call this off the main thread: it reads device
     * information and zips the logs.
     */
    fun generateReport(
        session: UnfinishedSession?,
        classification: SessionEnd,
        inputs: ReportInputs,
    ): CrashReport {
        val logs = recorder.logFiles()
        val sections = linkedMapOf<String, String>()

        val contents = listOf(
            "summary.txt",
            "device.txt",
            "runtime.txt",
            "settings.txt",
            "install.txt",
            "session.txt",
        ) + listOfNotNull(
            "native-probe.txt".takeIf { inputs.nativeProbe != null },
            "runtime-probe.txt".takeIf { inputs.runtimeProbe != null },
            // Last of the sections but first in usefulness: what Wine said.
            "wine.txt".takeIf { inputs.runtimeOutput?.isNotBlank() == true },
        ) + logs.map { "logs/${it.name}" }

        sections["summary.txt"] = ReportBuilder.summary(
            createdAtMillis = System.currentTimeMillis(),
            session = session,
            classification = classification,
            appVersion = appVersion,
            sections = contents,
            logNames = logs.map { it.name },
        )
        sections["device.txt"] = ReportBuilder.section("Device", DeviceInfo.collect(context))
        sections["runtime.txt"] = ReportBuilder.section("Compatibility runtime", inputs.runtime)
        sections["settings.txt"] = ReportBuilder.section("Settings", inputs.settings)
        sections["install.txt"] = ReportBuilder.section("Game files", inputs.install)
        sections["session.txt"] = ReportBuilder.section("Session", sessionFacts(session))
        inputs.nativeProbe?.let {
            sections["native-probe.txt"] = "Bundled native probe\n-------------------\n$it\n"
        }
        inputs.runtimeProbe?.let {
            sections["runtime-probe.txt"] = "Runtime capability probe\n------------------------\n$it\n"
        }
        inputs.runtimeOutput?.takeIf { it.isNotBlank() }?.let {
            sections["wine.txt"] = "Runtime output (Wine)\n---------------------\n$it\n"
        }

        return archive.write(
            classification = classification,
            sections = sections,
            logs = logs,
        )
    }

    /** Facts about the session a report is being written for. */
    private fun sessionFacts(session: UnfinishedSession?): Map<String, String> {
        if (session == null) {
            return mapOf("Reason" to "Generated on request")
        }
        return linkedMapOf(
            "Started" to session.startedAtMillis.toString(),
            "Last seen" to session.endedAtMillis.toString(),
            "Classification" to session.classification.name,
            "In background" to if (session.wasBackgrounded) "yes" else "no",
        )
    }
}
