package com.bloodline.android.runtime.native

import com.bloodline.android.runtime.native.ElfInspector.Machine
import com.bloodline.android.runtime.native.ElfInspector.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for reading an executable's identity out of its header.
 *
 * The fixtures here are built byte by byte rather than copied from disk, so
 * the test says exactly what the parser should find and fails loudly if an
 * offset is wrong. One test then cross-checks the parser against a real
 * executable on whichever machine is running the suite — which is the only
 * honest way to catch a parser that agrees with its own fixtures and nothing
 * else.
 */
class ElfInspectorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `reads an ARM64 executable linked against Android's loader`() {
        val file = write("wine", machine = Machine.AARCH64.code, interpreter = "/system/bin/linker64")

        val info = info(file)

        assertEquals(Machine.AARCH64, info.machine)
        assertEquals(64, info.bits)
        assertEquals(ElfInspector.Kind.EXECUTABLE, info.kind)
        assertEquals("/system/bin/linker64", info.interpreter)
        assertTrue(info.isArm64)
        assertTrue("a Bionic build must be recognised as one", info.isBionic)
    }

    @Test
    fun `a glibc build is recognised as not runnable here`() {
        // The failure this exists for. A Wine built against glibc unpacks
        // perfectly, is found by the layout, and then never starts, because
        // Android has no such loader. Saying so beats "Permission denied".
        val file = write("wine", machine = Machine.AARCH64.code, interpreter = "/lib/ld-linux-aarch64.so.1")

        val info = info(file)

        assertTrue(info.isArm64)
        assertFalse("a glibc build must not pass as a Bionic one", info.isBionic)
        assertTrue(info.toString().contains("ld-linux-aarch64"))
    }

    @Test
    fun `an x86_64 build is not mistaken for an ARM64 one`() {
        val file = write("wine", machine = Machine.X86_64.code, interpreter = "/system/bin/linker64")

        val info = info(file)

        assertEquals(Machine.X86_64, info.machine)
        assertFalse(info.isArm64)
        assertTrue(info.toString().contains("64-bit x86"))
    }

    @Test
    fun `a shared library is reported as one`() {
        val file = write("libwine.so", kind = 3, machine = Machine.AARCH64.code, interpreter = null)

        assertEquals(ElfInspector.Kind.SHARED, info(file).kind)
    }

    @Test
    fun `a Windows executable is reported as not an ELF, without throwing`() {
        // `wowbox64.dll` is a PE file, and it sits in the same package. The
        // inspector is asked about it, so it has to answer rather than fail.
        val file = temporary.newFile("wowbox64.dll")
        file.writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()) + ByteArray(200))

        val result = ElfInspector.inspect(file)

        assertTrue(result is Result.NotAnElf)
        assertTrue((result as Result.NotAnElf).reason.contains("not a Linux program"))
    }

    @Test
    fun `a truncated download is reported rather than read as a header`() {
        val file = temporary.newFile("short.wcp")
        file.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))

        val result = ElfInspector.inspect(file)

        assertTrue(result is Result.NotAnElf)
        assertTrue((result as Result.NotAnElf).reason.contains("too short"))
    }

    @Test
    fun `agrees with a real executable on this machine`() {
        // The cross-check. Every fixture above was written by the same hand
        // that wrote the parser, so all of them could be wrong together.
        // /proc/self/exe is a genuine ELF produced by a real toolchain: if the
        // parser cannot read it, the fixtures prove nothing.
        val self = File("/proc/self/exe")
        if (!self.isFile) return
        val result = ElfInspector.inspect(self)

        assertTrue("$result", result is Result.Is)
        val info = (result as Result.Is).info
        assertTrue(info.toString(), info.bits == 64)
        assertTrue(
            "a dynamically linked executable names its loader: $info",
            info.interpreter != null,
        )
        assertFalse(
            "this machine's own binaries are not Bionic builds: $info",
            info.isBionic,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun info(file: File): ElfInspector.ElfInfo {
        val result = ElfInspector.inspect(file)
        if (result is Result.NotAnElf) throw AssertionError(result.reason)
        return (result as Result.Is).info
    }

    /** Writes a minimal but genuinely structured ELF64 file. */
    private fun write(
        name: String,
        machine: Int,
        interpreter: String?,
        kind: Int = 2,
    ): File {
        val interpBytes = interpreter?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
        val phnum = if (interpreter == null) 1 else 2
        val phoff = 64L
        val interpOffset = (phoff + phnum * 56L).toInt()

        val out = ByteArray(interpOffset + interpBytes.size + 1)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        // e_ident
        out[0] = 0x7F; out[1] = 0x45; out[2] = 0x4C; out[3] = 0x46
        out[4] = 2 // ELFCLASS64
        out[5] = 1 // ELFDATA2LSB
        out[6] = 1 // EV_CURRENT

        buffer.putShort(16, kind.toShort()) // e_type
        buffer.putShort(18, machine.toShort()) // e_machine
        buffer.putInt(20, 1) // e_version
        buffer.putLong(24, 0x400000L) // e_entry
        buffer.putLong(32, phoff) // e_phoff
        buffer.putLong(40, 0L) // e_shoff
        buffer.putInt(48, 0) // e_flags
        buffer.putShort(52, 64) // e_ehsize
        buffer.putShort(54, 56) // e_phentsize
        buffer.putShort(56, phnum.toShort()) // e_phnum
        buffer.putShort(58, 64) // e_shentsize
        buffer.putShort(60, 0) // e_shnum
        buffer.putShort(62, 0) // e_shstrndx

        // PT_LOAD, so the table is not degenerate.
        var at = phoff.toInt()
        buffer.putInt(at, 1) // p_type
        buffer.putInt(at + 4, 5) // p_flags
        buffer.putLong(at + 8, 0) // p_offset
        buffer.putLong(at + 16, 0x400000L) // p_vaddr
        buffer.putLong(at + 24, 0x400000L) // p_paddr
        buffer.putLong(at + 32, out.size.toLong()) // p_filesz
        buffer.putLong(at + 40, out.size.toLong()) // p_memsz
        buffer.putLong(at + 48, 0x1000L) // p_align

        if (interpreter != null) {
            at += 56
            buffer.putInt(at, 3) // p_type = PT_INTERP
            buffer.putInt(at + 4, 4) // p_flags
            buffer.putLong(at + 8, interpOffset.toLong()) // p_offset
            buffer.putLong(at + 16, 0) // p_vaddr
            buffer.putLong(at + 24, 0) // p_paddr
            buffer.putLong(at + 32, interpBytes.size.toLong()) // p_filesz
            buffer.putLong(at + 40, interpBytes.size.toLong()) // p_memsz
            buffer.putLong(at + 48, 1) // p_align

            System.arraycopy(interpBytes, 0, out, interpOffset, interpBytes.size)
        }

        val file = temporary.newFile(name)
        file.writeBytes(out)
        return file
    }
}
