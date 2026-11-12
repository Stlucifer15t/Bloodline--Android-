package com.bloodline.android.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One generated diagnostics report. */
data class CrashReport(
    val file: File,
    val createdAtMillis: Long,
    val classification: SessionEnd,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/**
 * Writes, lists, rotates and deletes diagnostics reports.
 *
 * A report is a plain ZIP: `summary.txt` first, then one text file per area,
 * then the captured logs. Nothing encrypted, nothing hidden; the point is that
 * the user can open it, send it, or delete it.
 *
 * Only what the app produced is included: its own logs, device facts and the
 * chosen settings. No account, no contact, no personal files.
 */
class ReportArchive(
    private val directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxReports: Int = DEFAULT_MAX_REPORTS,
) {

    /** Newest first. */
    fun list(): List<CrashReport> = directory.listFiles()
        ?.mapNotNull { file -> parse(file) }
        ?.sortedByDescending { it.createdAtMillis }
        ?: emptyList()

    fun latest(): CrashReport? = list().firstOrNull()

    fun count(): Int = list().size

    /**
     * Writes a new report and rotates the old ones away.
     *
     * @param sections text files to include, by file name.
     * @param logs     captured log files, stored under `logs/`.
     */
    fun write(
        classification: SessionEnd,
        sections: Map<String, String>,
        logs: List<File> = emptyList(),
    ): CrashReport {
        directory.mkdirs()

        val createdAt = clock()
        val file = File(directory, fileName(createdAt, classification))

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            sections.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }

            logs.filter { it.exists() && it.length() > 0 }.forEach { log ->
                zip.putNextEntry(ZipEntry("logs/${log.name}")
                    .apply { time = log.lastModified() })
                log.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        rotate()
        return CrashReport(file, createdAt, classification)
    }

    /**
     * Every section of a report, as one piece of text.
     *
     * The zip is the right thing to write to disk, but it is the wrong thing to
     * hand to a person who has to paste it into a conversation — which is the
     * only way the person debugging this can see it. Each entry is labelled so
     * the sections stay readable once they are flat.
     */
    fun readText(report: CrashReport): String {
        val out = StringBuilder()
        java.util.zip.ZipInputStream(report.file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                out.append("===== ").append(entry.name).append(" =====\n")
                out.append(zip.readBytes().toString(Charsets.UTF_8))
                if (!out.endsWith("\n")) out.append('\n')
                out.append('\n')
            }
        }
        return out.toString()
    }

    fun delete(report: CrashReport) {
        report.file.delete()
    }

    fun deleteAll() {
        list().forEach { delete(it) }
    }

    /** Keeps the newest [maxReports] and removes the rest. */
    fun rotate() {
        list().drop(maxReports).forEach { delete(it) }
    }

    private fun parse(file: File): CrashReport? {
        val match = REPORT_PATTERN.matchEntire(file.name) ?: return null
        val stamp = match.groupValues[1]
        val kind = match.groupValues[2]

        val createdAt = runCatching { FILE_STAMP.parse(stamp)?.time }.getOrNull() ?: file.lastModified()
        val classification = runCatching { SessionEnd.valueOf(kind.uppercase(Locale.US)) }
            .getOrDefault(SessionEnd.UNKNOWN)

        return CrashReport(file, createdAt, classification)
    }

    private fun fileName(createdAtMillis: Long, classification: SessionEnd): String =
        "report-${FILE_STAMP.format(Date(createdAtMillis))}-" +
            classification.name.lowercase(Locale.US) + ".zip"

    companion object {
        const val DEFAULT_MAX_REPORTS = 10

        /** Matches report-20261111-142233-crash.zip */
        private val REPORT_PATTERN = Regex("report-(\\d{8}-\\d{6})-([a-z_]+)\\.zip")

        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
