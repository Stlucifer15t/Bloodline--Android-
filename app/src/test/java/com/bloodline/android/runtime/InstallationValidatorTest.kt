package com.bloodline.android.runtime

import com.bloodline.android.runtime.InstallationValidator.EXECUTABLE_NAME
import com.bloodline.android.runtime.InstallationValidator.MIN_EXECUTABLE_BYTES
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the PE header checks.
 *
 * These matter because the check is the only thing standing between the
 * launcher and a folder that merely *looks* like the game: a 64-bit binary, a
 * Linux ELF, a truncated download. Each of those is built by hand below so the
 * failure the user would see is the failure that is asserted.
 */
class InstallationValidatorTest {

    // ---------------------------------------------------------------- fixtures

    private val complete = mapOf(
        "Vampire" to true,
        EXECUTABLE_NAME to false,
    )

    /** Builds a minimal but structurally real PE header. */
    private fun pe(
        machine: Int,
        subsystem: Int? = 3,
        size: Long = MIN_EXECUTABLE_BYTES + 1024,
        magic: String = "MZ",
        signature: String = "PE\u0000\u0000",
    ): ByteArray {
        val peOffset = 0x80
        val bytes = ByteArray(size.coerceAtLeast(peOffset + 24L + 70L).toInt())

        // DOS header, with e_lfanew pointing at the PE signature.
        bytes[0] = magic[0].code.toByte()
        bytes[1] = magic[1].code.toByte()
        putInt(bytes, 0x3C, peOffset)

        // PE signature.
        for (i in signature.indices) bytes[peOffset + i] = signature[i].code.toByte()

        // COFF header: machine, then section count, then the optional header size
        // at offset 16 — which is what tells us whether a subsystem field exists.
        val coff = peOffset + 4
        putShort(bytes, coff, machine)
        putShort(bytes, coff + 2, 3) // number of sections
        putShort(bytes, coff + 16, if (subsystem == null) 0 else 240) // optional header size
        putShort(bytes, coff + 18, 0x0102) // characteristics

        if (subsystem != null) {
            // Windows GUI/CUI live at offset 68 of the optional header.
            putShort(bytes, coff + 20 + 68, subsystem)
        }

        return bytes
    }

    private fun putShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        putShort(bytes, offset, value and 0xFFFF)
        putShort(bytes, offset + 2, (value shr 16) and 0xFFFF)
    }

    private fun validate(bytes: ByteArray, entries: Map<String, Boolean> = complete) =
        InstallationValidator.validate(
            path = "/storage/game",
            entries = entries,
            openExecutable = { ByteArrayInputStream(bytes) },
        )

    // ------------------------------------------------------------ happy path

    @Test
    fun `accepts a real 32-bit Windows executable`() {
        val result = validate(pe(machine = 0x014C, subsystem = 2))

        assertTrue(result is InstallValidation.Valid)
        val valid = result as InstallValidation.Valid
        assertEquals("/storage/game/$EXECUTABLE_NAME", valid.install.executablePath)
        assertEquals(PeMachine.I386, valid.install.machine)
        assertEquals(PeSubsystem.WINDOWS_GUI, valid.install.subsystem)
    }

    @Test
    fun `reports the real file size, not the size of the window it read`() {
        // The header only needs the first kilobyte, but a truncated download is
        // recognised by its size — so the size must be measured from the whole
        // stream rather than from the buffer the header was parsed out of.
        val size = 5L * 1024 * 1024
        val result = validate(pe(machine = 0x014C, size = size))

        assertEquals(size, (result as InstallValidation.Valid).install.sizeBytes)
    }

    // --------------------------------------------------------- wrong binaries

    @Test
    fun `rejects a 64-bit executable`() {
        // The case the brief calls out: Bloodlines is 32-bit, and a 64-bit
        // Vampire.exe would not run on the Box86 path at all.
        val result = validate(pe(machine = 0x8664))

        assertTrue(result is InstallValidation.Invalid)
        val invalid = result as InstallValidation.Invalid
        assertTrue(invalid.reason.contains("64-bit x86"))
        assertTrue(invalid.reason.contains("32-bit"))
    }

    @Test
    fun `rejects an ARM64 Windows executable`() {
        val result = validate(pe(machine = 0xAA64))

        assertTrue((result as InstallValidation.Invalid).reason.contains("64-bit ARM"))
    }

    @Test
    fun `rejects a file that is not a Windows executable at all`() {
        val result = validate(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))

        assertTrue((result as InstallValidation.Invalid).reason.contains("not a Windows executable"))
    }

    @Test
    fun `rejects an empty file`() {
        val result = validate(ByteArray(0))

        assertTrue(result is InstallValidation.Invalid)
    }

    @Test
    fun `rejects an MZ file with no PE header`() {
        // A DOS executable: right first two bytes, wrong everything else.
        val bytes = ByteArray(1024)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()

        val result = validate(bytes)

        assertTrue((result as InstallValidation.Invalid).reason.contains("no PE header"))
    }

    @Test
    fun `rejects a file whose PE signature is wrong`() {
        val result = validate(pe(machine = 0x014C, signature = "PX\u0000\u0000"))

        assertTrue((result as InstallValidation.Invalid).reason.contains("PE signature is missing"))
    }

    @Test
    fun `rejects an executable that is far too small to be the game`() {
        // A 2 KB file with a perfectly valid header is a placeholder or a
        // truncated download, not a game.
        val result = validate(pe(machine = 0x014C, size = 2048))

        assertTrue((result as InstallValidation.Invalid).reason.contains("too small"))
    }

    // ---------------------------------------------------------- missing files

    @Test
    fun `rejects a folder with no executable`() {
        val result = InstallationValidator.validate(
            path = "/storage/game",
            entries = mapOf("Vampire" to true),
            openExecutable = { ByteArrayInputStream(pe(0x014C)) },
        )

        assertTrue((result as InstallValidation.Invalid).reason.contains("executable"))
    }

    @Test
    fun `rejects a folder where the data folder is actually a file`() {
        val result = InstallationValidator.validate(
            path = "/storage/game",
            entries = mapOf("Vampire" to false, EXECUTABLE_NAME to false),
            openExecutable = { ByteArrayInputStream(pe(0x014C)) },
        )

        assertTrue((result as InstallValidation.Invalid).reason.contains("data folder"))
    }

    @Test
    fun `reports an executable that cannot be opened`() {
        val result = InstallationValidator.validate(
            path = "/storage/game",
            entries = complete,
            openExecutable = { throw java.io.IOException("permission denied") },
        )

        assertTrue((result as InstallValidation.Invalid).reason.contains("could not be read"))
    }

    // ------------------------------------------------------------- the parser

    @Test
    fun `parses a header without throwing when the file is tiny`() {
        // A malformed file is a result, never an exception: the launcher has to
        // survive being pointed at something that is not a game.
        val header = InstallationValidator.parse(byteArrayOf('M'.code.toByte()))

        assertTrue(!header.isPortableExecutable)
        assertTrue(header.problem!!.contains("truncated") || header.problem!!.contains("MZ"))
    }

    @Test
    fun `describes an unknown machine rather than crashing`() {
        val header = InstallationValidator.parse(pe(machine = 0x1234))

        assertTrue(header.isPortableExecutable)
        assertEquals(null, header.machine)
        assertTrue(header.describe().contains("unknown machine"))
    }

    @Test
    fun `handles a header with no optional header at all`() {
        val header = InstallationValidator.parse(pe(machine = 0x014C, subsystem = null))

        assertTrue(header.isPortableExecutable)
        assertEquals(null, header.subsystem)
    }
}
