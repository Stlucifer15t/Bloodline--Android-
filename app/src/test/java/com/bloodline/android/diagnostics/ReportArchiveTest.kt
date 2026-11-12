package com.bloodline.android.diagnostics

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reports are files the user can open, share and delete, so they must behave. */
class ReportArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Advanced between writes, so each report gets its own name. */
    private var now = NOW

    private fun archive(maxReports: Int = ReportArchive.DEFAULT_MAX_REPORTS) =
        ReportArchive(temporaryFolder.newFolder("reports"), clock = { now }, maxReports = maxReports)

    private fun report(
        archive: ReportArchive,
        name: String = "summary.txt",
        classification: SessionEnd = SessionEnd.CRASH,
        logs: List<File> = emptyList(),
    ): CrashReport {
        now += 60_000
        return archive.write(classification, mapOf(name to "content of $name"), logs)
    }

    @Test
    fun `a report is a zip the user can open`() {
        val written = report(archive())

        assertTrue(written.file.exists())
        assertTrue(written.file.name.endsWith(".zip"))
        ZipFile(written.file).use { zip ->
            val entry = zip.getEntry("summary.txt")
            assertNotNull(entry)
            assertEquals(
                "content of summary.txt",
                zip.getInputStream(entry).bufferedReader().readText(),
            )
        }
    }

    @Test
    fun `the classification survives in the file name`() {
        val archive = archive()
        report(archive, classification = SessionEnd.KILLED_WHILE_BACKGROUNDED)
        report(archive, classification = SessionEnd.CRASH)

        // Newest first, and each one remembers what it was.
        assertEquals(SessionEnd.CRASH, archive.latest()?.classification)
        assertEquals(
            SessionEnd.KILLED_WHILE_BACKGROUNDED,
            archive.list().last().classification,
        )
    }

    @Test
    fun `logs are stored under a logs folder inside the archive`() {
        val log = temporaryFolder.newFile("session.1.log").apply { writeText("line one\n") }

        val written = report(archive(), name = "summary.txt", logs = listOf(log))

        ZipFile(written.file).use { zip ->
            val entry = zip.getEntry("logs/session.1.log")
            assertNotNull(entry)
            assertEquals("line one\n", zip.getInputStream(entry!!).readBytes().decodeToString())
        }
    }

    @Test
    fun `old reports are rotated away`() {
        val archive = archive(maxReports = 3)

        repeat(5) { report(archive) }

        assertEquals(3, archive.count())
    }

    @Test
    fun `the newest reports are the ones kept`() {
        val archive = archive(maxReports = 2)
        val first = report(archive)
        val second = report(archive)
        val third = report(archive)

        val remaining = archive.list().map { it.file.name }.toSet()

        assertFalse(remaining.contains(first.file.name))
        assertTrue(remaining.contains(second.file.name))
        assertTrue(remaining.contains(third.file.name))
    }

    @Test
    fun `deleting the latest report works`() {
        val archive = archive()
        val written = report(archive)

        archive.delete(written)

        assertNull(archive.latest())
        assertFalse(written.file.exists())
    }

    @Test
    fun `deleting everything works`() {
        val archive = archive()
        report(archive)
        report(archive)

        archive.deleteAll()

        assertEquals(0, archive.count())
    }

    @Test
    fun `an empty archive reports nothing`() {
        val archive = archive()

        assertNull(archive.latest())
        assertEquals(0, archive.count())
    }

    @Test
    fun `a report knows its size and creation time`() {
        val written = report(archive())

        assertEquals(NOW + 60_000, written.createdAtMillis)
        assertTrue(written.sizeBytes > 0)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
