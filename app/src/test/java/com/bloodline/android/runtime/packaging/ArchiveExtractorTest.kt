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
 * Three properties matter, and the first two are security properties rather
 * than convenience ones:
 *
 * 1. **A hostile entry name cannot write outside the target directory.** These
 *    archives are third-party files fetched over the network. An entry called
 *    `../../../../data/data/...` is a real attack, and the only safe answer is
 *    to refuse the whole archive.
 * 2. **The container is identified from its bytes, not its name.** Upstream
 *    ships a private `.wcp` extension over a compressed tar, so the filename
 *    carries no information.
 * 3. **Both compressions work.** Upstream's own installer tries XZ first and
 *    falls back to Zstandard, so a build that handles only one silently fails
 *    on some packages. These tests cover the tar reading itself; decoding
 *    Zstandard needs the real library and is therefore only checked end to end
 *    where that library is present.
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

    /** The 512-byte tar header, with the checksum the format requires. */
    private fun tarHeader(
        name: String,
        size: Int,
        typeflag: Byte = '0'.code.toByte(),
        linkName: String = "",
    ): ByteArray {
        val block = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, block, 0, minOf(nameBytes.size, 100))
        val sizeText = String.format("%011o", size).toByteArray(Charsets.US_ASCII)
        System.arraycopy(sizeText, 0, block, 124, sizeText.size)
        block[156] = typeflag
        // The target of a link lives at offset 157, 100 bytes. Without it a
        // symlink entry carries an empty name and is correctly refused.
        val linkBytes = linkName.toByteArray(Charsets.UTF_8)
        System.arraycopy(linkBytes, 0, block, 157, minOf(linkBytes.size, 100))
        "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(block, 257)

        // The checksum counts its own field as eight spaces, so the field is
        // filled with spaces before the sum is taken. Writing the size first
        // and summing whatever happens to be in the checksum field - the
        // obvious way to write this helper - produces a header real tar
        // rejects, and would have hidden the fact that we now check.
        block.fill(0x20.toByte(), 148, 156)
        var sum = 0
        for (index in block.indices) sum += block[index].toInt() and 0xFF
        val checksum = String.format("%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII)
        checksum.copyInto(block, 148)
        return block
    }

    private fun tar(vararg entries: Pair<String, String>): File {
        val file = temporary.newFile("package.tar")
        file.outputStream().buffered().use { out ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                out.write(tarHeader(name, bytes.size))
                out.write(bytes)
                out.write(ByteArray((512 - bytes.size % 512) % 512))
            }
            out.write(ByteArray(1024))
        }
        return file
    }

    private fun target(name: String) = temporary.newFolder(name)

    // ------------------------------------------------------------- happy path

    @Test
    fun `unpacks a zip into the target directory`() {
        val archive = zip("bin/wine" to "wine", "lib/libwine.so" to "so")
        val target = target("zip-ok")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Success)
        assertEquals(2, (result as ArchiveExtractor.Result.Success).fileCount)
        assertEquals(ArchiveFormat.ZIP, result.format)
        assertEquals("wine", File(target, "bin/wine").readText())
        assertEquals("so", File(target, "lib/libwine.so").readText())
    }

    @Test
    fun `empty directories in the zip are created`() {
        val file = temporary.newFile("dirs.zip")
        ZipOutputStream(file.outputStream().buffered()).use {
            it.putNextEntry(ZipEntry("prefix/"))
            it.closeEntry()
        }
        val target = target("zip-dirs")

        extractor.extract(file, target)

        assertTrue(File(target, "prefix").isDirectory)
    }

    @Test
    fun `unpacks a tar into the target directory`() {
        val archive = tar("bin/wine" to "wine", "share/wine/wine.inf" to "[Version]")
        val target = target("tar-ok")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Success)
        assertEquals(2, (result as ArchiveExtractor.Result.Success).fileCount)
        assertEquals(ArchiveFormat.TAR, result.format)
        assertEquals("wine", File(target, "bin/wine").readText())
        assertEquals("[Version]", File(target, "share/wine/wine.inf").readText())
    }

    @Test
    fun `a tar entry of zero bytes is created as an empty file`() {
        val archive = tar("lib/.keep" to "")
        val target = target("tar-empty")

        extractor.extract(archive, target)

        assertTrue(File(target, "lib/.keep").isFile)
        assertEquals(0L, File(target, "lib/.keep").length())
    }

    @Test
    fun `a tar directory entry is created`() {
        val file = temporary.newFile("tardir.tar")
        file.outputStream().buffered().use { out ->
            out.write(tarHeader("drive_c/", 0, typeflag = '5'.code.toByte()))
            out.write(ByteArray(1024))
        }
        val target = target("tar-dir")

        extractor.extract(file, target)

        assertTrue(File(target, "drive_c").isDirectory)
    }

    @Test
    fun `a tar symlink is skipped rather than followed`() {
        // A package linking outside its own directory is the exact thing the
        // traversal check exists to stop, so the entry is recorded and dropped
        // instead of being created.
        val file = temporary.newFile("tarlink.tar")
        file.outputStream().buffered().use { out ->
            out.write(tarHeader("link", 9, typeflag = '2'.code.toByte()))
            out.write("../../../x".toByteArray())
            out.write(ByteArray(503))
            out.write(ByteArray(1024))
        }
        val target = target("tar-link")

        val result = extractor.extract(file, target)

        assertTrue(result is ArchiveExtractor.Result.Success)
        assertTrue(TarExtractor.skippedEntries().any { it.startsWith("link") })
    }

    @Test
    fun `a tar symlink that stays inside the archive is created`() {
        // Wine's bin/ is built from links: `wine64` and `wineboot` point at
        // `wine`. Skipping them leaves four-byte files containing a path where
        // a loader should be, and the failure surfaces much later as
        // "No such file or directory".
        val file = temporary.newFile("tar-safe-link.tar")
        file.outputStream().buffered().use { out ->
            out.write(tarHeader("bin/wine", 0, typeflag = '0'.code.toByte()))
            // A zero-byte entry needs no data block, so none is written here:
            // an extra block of zeros would be read as the end of the archive
            // and the link after it would never be seen.
            out.write(tarHeader("bin/wineboot", 4, typeflag = '2'.code.toByte(), linkName = "wine"))
            out.write("wine".toByteArray())
            out.write(ByteArray(508))
            out.write(ByteArray(1024))
        }
        val target = target("tar-symlink")

        val result = extractor.extract(file, target)

        assertTrue(result is ArchiveExtractor.Result.Success)
        val link = File(target, "bin/wineboot")
        assertTrue("the link should exist", link.exists() || isSymbolicLink(link))
        assertTrue(isSymbolicLink(link))
        assertEquals("wine", java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
        assertFalse(
            "an in-archive link is not a skipped entry",
            TarExtractor.skippedEntries().any { it.startsWith("bin/wineboot") },
        )
    }

    private fun isSymbolicLink(file: File): Boolean =
        java.nio.file.Files.isSymbolicLink(file.toPath())

    // ---------------------------------------------------------- path traversal

    @Test
    fun `refuses a zip entry that escapes the target directory`() {
        val archive = zip("../../../../data/data/com.other/evil" to "x")
        val target = target("zip-escape")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Failure)
        // The refusal names the entry, so a report says which archive and which
        // name, not merely that something was "unsafe".
        assertTrue((result as ArchiveExtractor.Result.Failure).reason.contains("refused"))
    }

    @Test
    fun `refuses an absolute zip entry name`() {
        val result = extractor.extract(zip("/system/bin/sh" to "x"), target("zip-abs"))

        assertTrue(result is ArchiveExtractor.Result.Failure)
    }

    @Test
    fun `refuses a tar entry that escapes the target directory`() {
        val archive = tar("../../../evil" to "x")
        val target = target("tar-escape")

        val result = extractor.extract(archive, target)

        assertTrue(result is ArchiveExtractor.Result.Failure)
        // The refusal names the entry, so a report says which archive and which
        // name, not merely that something was "unsafe".
        assertTrue((result as ArchiveExtractor.Result.Failure).reason.contains("refused"))
    }

    @Test
    fun `nothing is left behind when an entry is refused`() {
        // A partial extraction is worse than none: the runtime would look
        // installed and fail somewhere unrecognisable.
        val archive = zip("bin/wine" to "wine", "../escaped" to "no")
        val target = target("partial")

        extractor.extract(archive, target)

        assertFalse(File(target.parentFile, "escaped").exists())
    }

    // ----------------------------------------------------------- format sniff

    @Test
    fun `detects a zip from its magic bytes`() {
        assertEquals(ArchiveFormat.ZIP, extractor.detectFormat(zip("a" to "b")))
    }

    @Test
    fun `detects zstd`() {
        val file = temporary.newFile("package.wcp")
        file.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64))

        assertEquals(ArchiveFormat.TAR_ZST, extractor.detectFormat(file))
    }

    @Test
    fun `detects xz`() {
        val file = temporary.newFile("package.wcp")
        file.writeBytes(
            byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00) + ByteArray(64),
        )

        assertEquals(ArchiveFormat.TAR_XZ, extractor.detectFormat(file))
    }

    @Test
    fun `detects gzip`() {
        val file = temporary.newFile("package.wcp")
        file.writeBytes(byteArrayOf(0x1F, 0x8B.toByte()) + ByteArray(64))

        assertEquals(ArchiveFormat.TAR_GZ, extractor.detectFormat(file))
    }

    @Test
    fun `an unrecognised file is reported, not guessed at`() {
        // `.wcp` is a private extension. If a package turns out not to be one
        // of the containers we can read, the user is told rather than handed a
        // runtime that looks installed and is not.
        val file = temporary.newFile("package.wcp")
        file.writeBytes(ByteArray(512))

        assertEquals(ArchiveFormat.UNKNOWN, extractor.detectFormat(file))
        assertTrue(
            (extractor.extract(file, target("unknown")) as ArchiveExtractor.Result.Failure)
                .reason.contains("not recognised"),
        )
    }

    // ------------------------------------------------------------ gzip wiring

    @Test
    fun `unpacks a gzipped tar`() {
        // gzip needs no third-party decoder — java.util.zip has it — so this
        // exercises the whole detect → decompress → tar path for real.
        val plain = tar("bin/wine" to "wine")
        val gzipped = temporary.newFile("package.tar.gz")
        java.util.zip.GZIPOutputStream(gzipped.outputStream().buffered()).use { out ->
            out.write(plain.readBytes())
        }
        val target = target("tar-gz")

        val result = extractor.extract(gzipped, target)

        assertTrue(result.toString(), result is ArchiveExtractor.Result.Success)
        assertEquals(ArchiveFormat.TAR_GZ, (result as ArchiveExtractor.Result.Success).format)
        assertEquals("wine", File(target, "bin/wine").readText())
    }

    @Test
    fun `unpacks an xz-compressed tar`() {
        // The format upstream ships Wine in. `wine-11.3-arm64ec.wcp.xz` is a
        // tar behind XZ; the extension says nothing, so this goes through the
        // magic-byte sniff as well as the decompression.
        val plain = tar("bin/wine64" to "wine", "share/wine/wine.inf" to "[Version]")
        val compressed = temporary.newFile("package.wcp")
        org.tukaani.xz.XZOutputStream(
            compressed.outputStream().buffered(),
            org.tukaani.xz.LZMA2Options(),
        ).use { out ->
            out.write(plain.readBytes())
        }
        val target = target("tar-xz")

        val result = extractor.extract(compressed, target)

        assertTrue(result.toString(), result is ArchiveExtractor.Result.Success)
        assertEquals(ArchiveFormat.TAR_XZ, (result as ArchiveExtractor.Result.Success).format)
        assertEquals("wine", File(target, "bin/wine64").readText())
    }

    // ------------------------------------------------------------------- misc

    @Test
    fun `a missing archive fails with a reason rather than throwing`() {
        val result = extractor.extract(File(temporary.root, "nope.wcp"), target("missing"))

        assertTrue(result is ArchiveExtractor.Result.Failure)
        assertTrue((result as ArchiveExtractor.Result.Failure).reason.contains("no file"))
    }

    @Test
    fun `the resolver refuses the same names for zip and for tar`() {
        // The two paths share one resolver on purpose: a traversal check
        // implemented twice is a traversal check fixed in one place.
        val root = temporary.root.canonicalFile
        val resolver = PathResolver(root)

        assertFalse(root.path, resolver.resolve("../../../etc/passwd") != null)
        assertFalse(resolver.resolve("/etc/passwd") != null)
        assertFalse(resolver.resolve("") != null)
        assertFalse(resolver.resolve("   ") != null)
        assertTrue(resolver.resolve("bin/wine") != null)
    }

    // ------------------------------------------------- structural validation

    @Test
    fun `a good archive is accepted before anything is written`() {
        val accepted = extractor.inspect(tar("bin/wine" to "wine"))

        assertTrue(accepted is ArchiveExtractor.Inspection.Accepted)
    }

    @Test
    fun `an archive whose tar header fails its checksum is refused before extraction`() {
        // The failure this exists for: a truncated transfer and an HTML error
        // page both still have first bytes. Reading them as a header produces
        // a nonsense filename and size rather than a refusal, and the user is
        // told only that "unpacking failed" much later.
        val corrupt = tar("bin/wine" to "wine")
        val bytes = corrupt.readBytes()
        bytes[0] = 'X'.code.toByte() // the name changes, so the checksum must not match
        corrupt.writeBytes(bytes)
        val target = target("corrupt")

        val inspection = extractor.inspect(corrupt)
        val result = extractor.extract(corrupt, target)

        assertTrue(inspection is ArchiveExtractor.Inspection.Refused)
        assertTrue(
            (inspection as ArchiveExtractor.Inspection.Refused).reason.contains("tar header"),
        )
        assertTrue(result is ArchiveExtractor.Result.Failure)
        assertFalse(File(target, "bin").exists())
    }

    @Test
    fun `an empty download and HTML in place of a package are refused`() {
        val empty = temporary.newFile("empty.wcp")
        assertTrue(extractor.inspect(empty) is ArchiveExtractor.Inspection.Refused)

        val html = temporary.newFile("error.wcp")
        html.writeBytes("<!DOCTYPE html><html>".toByteArray() + ByteArray(600))
        val inspection = extractor.inspect(html)
        assertTrue(inspection is ArchiveExtractor.Inspection.Refused)
        assertTrue(
            (inspection as ArchiveExtractor.Inspection.Refused).reason.contains("not recognised"),
        )
    }

    @Test
    fun `a tar truncated to nothing is refused rather than unpacked to nothing`() {
        // A download cut off after the magic bytes still identifies as the
        // right format, so the format check alone would wave it through.
        val plain = tar("bin/wine" to "wine")
        val truncated = temporary.newFile("truncated.wcp")
        org.tukaani.xz.XZOutputStream(
            truncated.outputStream().buffered(),
            org.tukaani.xz.LZMA2Options(),
        ).use { out -> out.write(plain.readBytes()) }
        val kept = truncated.readBytes().copyOf(64)
        truncated.writeBytes(kept)

        val inspection = extractor.inspect(truncated)
        assertTrue(inspection is ArchiveExtractor.Inspection.Refused)
    }

    // ------------------------------------------------------------ permissions

    @Test
    fun `a binary that unpacks from a zip is made executable`() {
        // ZIP carries no Unix mode that Java exposes, so the decision has to
        // come from the file's own bytes. Without it, `wine` unpacks with no
        // execute bit and the runtime fails with a bare "Permission denied".
        val archive = zip("bin/wine" to ELF_STUB)
        val target = target("zip-exec")

        assertTrue(extractor.extract(archive, target) is ArchiveExtractor.Result.Success)

        val wine = File(target, "bin/wine")
        assertTrue("an ELF file must be executable", wine.canExecute())
    }

    @Test
    fun `a data file is not made executable`() {
        val archive = zip("share/wine/wine.inf" to "[Version]\r\n")
        val target = target("zip-noexec")

        assertTrue(extractor.extract(archive, target) is ArchiveExtractor.Result.Success)

        assertFalse(File(target, "share/wine/wine.inf").canExecute())
    }

    /** Enough of an ELF header for the content sniff: the magic, nothing more. */
    private companion object {
        const val ELF_STUB = "\u007FELF"
    }
}
