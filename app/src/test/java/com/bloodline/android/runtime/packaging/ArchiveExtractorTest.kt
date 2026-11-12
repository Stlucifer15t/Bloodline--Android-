package com.bloodline.android.runtime.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for unpacking a runtime package.
 *
 * Two properties matter, and both are security properties rather than
 * convenience ones:
 *
 * 1. **A hostile entry name cannot write outside the target directory.** These
 *    archives are third-party files fetched over the network. An entry called
 *    `../../../../data/data/...` is a real attack, and the only safe answer is
 *    to refuse the whole archive.
 * 2. **The container is identified from its bytes, not its name.** Upstream
 *    ships a private `.wcp` extension over an ordinary archive, so the
 *    filename carries no information.
 */
class ArchiveExtractorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val extractor = ArchiveExtractor()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temporary.newFile("package.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun `unpacks a zip into the target directory`() {
        val archive = zip("bin/wine" to "wine", "lib/libwine.so" to "so")
        val target = temporary.newFolder("target")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Success)
        assertEquals(2, (result as ArchiveExtractor.Result.Success).fileCount)
        assertEquals(ArchiveFormat.ZIP, result.format)
        assertEquals("wine", File(target, "bin/wine").readText())
        assertEquals("so", File(target, "lib/libwine.so").readText())
    }

    @Test
    fun `empty directories in the archive are created`() {
        val file = temporary.newFile("dirs.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("prefix/"))
            zip.closeEntry()
        }
        val target = temporary.newFolder("target2")

        extractor.extract(file, target)

        assertTrue(File(target, "prefix").isDirectory)
    }

    // ---------------------------------------------------------- path traversal

    @Test
    fun `refuses an entry that escapes the target directory`() {
        val archive = zip("../../../../data/data/com.other/evil" to "x")
        val target = temporary.newFolder("target3")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Failure)
        assertTrue((result as ArchiveExtractor.Result.Failure).reason.contains("unsafe path"))
    }

    @Test
    fun `refuses an absolute entry name`() {
        val archive = zip("/system/bin/sh" to "x")
        val target = temporary.newFolder("target4")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Failure)
    }

    @Test
    fun `nothing is written outside the target when an entry is refused`() {
        // A partial extraction is worse than none: the runtime would look
        // installed and fail somewhere unrecognisable.
        val archive = zip(
            "bin/wine" to "wine",
            "../escaped" to "no",
        )
        val target = temporary.newFolder("target5")

        extractor.extract(archive, target)

        assertFalse(File(target.parentFile, "escaped").exists())
    }

    // ----------------------------------------------------------- format sniff

    @Test
    fun `detects a zip from its magic bytes`() {
        assertEquals(ArchiveFormat.ZIP, extractor.detectFormat(zip("a" to "b")))
    }

    @Test
    fun `detects zstd and refuses it rather than guessing`() {
        val file = temporary.newFile("package.wcp")
        file.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64))

        assertEquals(ArchiveFormat.TAR_ZST, extractor.detectFormat(file))
        assertTrue(
            extractor.extract(file, temporary.newFolder("target6")) is
                ArchiveExtractor.Result.Failure,
        )
    }

    @Test
    fun `detects xz`() {
        val file = temporary.newFile("package.xz")
        file.writeBytes(
            byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00) + ByteArray(64),
        )

        assertEquals(ArchiveFormat.TAR_XZ, extractor.detectFormat(file))
    }

    @Test
    fun `detects gzip`() {
        val file = temporary.newFile("package.gz")
        file.writeBytes(byteArrayOf(0x1F, 0x8B.toByte()) + ByteArray(64))

        assertEquals(ArchiveFormat.TAR_GZ, extractor.detectFormat(file))
    }

    @Test
    fun `an unrecognised file is reported, not guessed at`() {
        // `.wcp` is a private extension; if it turns out not to be one of the
        // formats we can read, the user is told rather than handed a broken
        // runtime.
        val file = temporary.newFile("package.wcp")
        file.writeBytes(ByteArray(512))

        assertEquals(ArchiveFormat.UNKNOWN, extractor.detectFormat(file))
        assertTrue(
            (extractor.extract(file, temporary.newFolder("target7")) as
                ArchiveExtractor.Result.Failure).reason.contains("not recognised"),
        )
    }

    @Test
    fun `a missing archive fails with a reason rather than throwing`() {
        val result = extractor.extract(File(temporary.root, "nope.zip"), temporary.newFolder("t8"))

        assertTrue(result is ArchiveExtractor.Result.Failure)
        assertTrue((result as ArchiveExtractor.Result.Failure).reason.contains("not a file"))
    }
}
