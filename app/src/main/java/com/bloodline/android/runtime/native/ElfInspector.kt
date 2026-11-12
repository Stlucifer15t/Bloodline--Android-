package com.bloodline.android.runtime.native

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads what an executable file actually *is*, from its first bytes.
 *
 * Why this exists: the packages the runtime downloads are binaries for a
 * specific ABI linked against a specific C library, and getting either wrong
 * produces a failure that names neither. An x86_64 Wine on an ARM64 phone, or
 * a glibc Wine on an Android that has no glibc, both fail the same way — the
 * kernel refuses to run them and `exec` returns a bare "Permission denied" or
 * "No such file or directory", which reads as a missing file rather than a
 * wrong one.
 *
 * Reading the ELF header turns that into "this is an x86_64 build linked
 * against /lib64/ld-linux-x86-64.so.2, and this device needs an ARM64 build
 * linked against /system/bin/linker64". That is a sentence a user can act on,
 * and it is checked before anything is executed rather than after.
 *
 * This is pure Kotlin and needs no native code, which is what makes it
 * testable on the JVM — the only kind of testing available for a runtime that
 * cannot be run here.
 */
object ElfInspector {

    /** The CPU an ELF file was built for. */
    enum class Machine(val code: Int, val title: String) {
        I386(3, "32-bit x86"),
        ARM(40, "32-bit ARM"),
        X86_64(62, "64-bit x86"),
        AARCH64(183, "64-bit ARM"),
        UNKNOWN(-1, "unknown"),
        ;

        companion object {
            fun of(code: Int): Machine = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /** Whether the file is a program, a shared library, or neither. */
    enum class Kind(val code: Int, val title: String) {
        NONE(0, "not an object"),
        EXECUTABLE(2, "executable"),
        SHARED(3, "shared library"),
        OTHER(-1, "other"),
        ;

        companion object {
            fun of(code: Int): Kind = entries.firstOrNull { it.code == code } ?: OTHER
        }
    }

    /** What one file turned out to be. */
    data class ElfInfo(
        val bits: Int,
        val machine: Machine,
        val kind: Kind,
        /** `PT_INTERP`: the dynamic loader this binary expects to be run by. */
        val interpreter: String?,
    ) {

        /** True when this is an ARM64 build, 32- or 64-bit ARM of any flavour. */
        val isArm64: Boolean get() = machine == Machine.AARCH64

        /**
         * True when the loader it names is Android's.
         *
         * This is the check that separates a Bionic build, which runs here,
         * from a glibc build, which does not — and it is the single most
         * likely way to end up with a Wine that unpacks perfectly and never
         * starts.
         */
        val isBionic: Boolean get() =
            interpreter != null && BIONIC_LOADERS.any { interpreter.startsWith(it) }

        /** A sentence for a diagnostics report. */
        override fun toString(): String = buildString {
            append(machine.title)
            append(", ").append(bits).append("-bit, ").append(kind.title)
            append(
                when {
                    interpreter == null -> ", statically linked"
                    isBionic -> ", uses Android's loader ($interpreter)"
                    else -> ", uses $interpreter"
                },
            )
        }
    }

    /** What went wrong when a file could not be read as an ELF. */
    sealed interface Result {
        data class Is(val info: ElfInfo) : Result
        data class NotAnElf(val reason: String) : Result
    }

    /**
     * Reads [file]'s header.
     *
     * @return [Result.Is] with what it found, or [Result.NotAnElf] with why.
     *         Never throws for a file that simply is not an ELF; a PE file, a
     *         text file and a truncated download are all expected inputs.
     */
    fun inspect(file: File): Result {
        if (!file.isFile) return Result.NotAnElf("there is no file at ${file.path}")
        if (file.length() < HEADER_64_SIZE) {
            return Result.NotAnElf("${file.name} is only ${file.length()} bytes, too short to be a program")
        }

        val header = try {
            file.readBytes().copyOf(HEADER_64_SIZE)
        } catch (error: IOException) {
            return Result.NotAnElf("${file.name} could not be read: ${error.message}")
        }

        if (!header.startsWith(ELF_MAGIC)) {
            return Result.NotAnElf("${file.name} does not begin with the ELF magic, so it is not a Linux program")
        }

        val bits = when (header[EI_CLASS].toInt()) {
            ELFCLASS32 -> 32
            ELFCLASS64 -> 64
            else -> return Result.NotAnElf("${file.name} declares an unknown ELF class ${header[EI_CLASS]}")
        }

        val buffer = ByteBuffer.wrap(header).apply {
            order(
                if (header[EI_DATA].toInt() == ELFDATA2MSB) ByteOrder.BIG_ENDIAN
                else ByteOrder.LITTLE_ENDIAN,
            )
        }

        val type = buffer.getShort(E_TYPE_OFFSET).toInt()
        val machine = Machine.of(buffer.getShort(E_MACHINE_OFFSET).toInt())
        val interpreter = readInterpreter(file, bits, buffer)

        return Result.Is(ElfInfo(bits = bits, machine = machine, kind = Kind.of(type), interpreter = interpreter))
    }

    /**
     * Reads `PT_INTERP` out of the program header table.
     *
     * Only the first few entries are read, and only the bytes actually needed:
     * `PT_INTERP` is by convention early in the table, and the files this is
     * pointed at are hundreds of megabytes, so reading one whole into memory
     * to find a 28-character string would be indefensible.
     *
     * @return the loader path, or null when the file names none.
     */
    private fun readInterpreter(file: File, bits: Int, header: ByteBuffer): String? {
        val entrySize = if (bits == 64) PROGRAM_HEADER_64_SIZE else PROGRAM_HEADER_32_SIZE
        val phoff = if (bits == 64) header.getLong(E_PHOFF_64_OFFSET) else header.getInt(E_PHOFF_32_OFFSET).toLong()
        val phnum = header.getShort(if (bits == 64) E_PHNUM_64_OFFSET else E_PHNUM_32_OFFSET).toInt()

        if (phoff <= 0 || phnum <= 0) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                for (index in 0 until minOf(phnum, MAX_PROGRAM_HEADERS)) {
                    val at = phoff + (index.toLong() * entrySize)
                    if (at + entrySize > raf.length()) return null

                    val entry = ByteArray(entrySize)
                    raf.seek(at)
                    raf.readFully(entry)
                    val buffer = ByteBuffer.wrap(entry).order(header.order())
                    if (buffer.getInt(0) != PT_INTERP) continue

                    val offset: Long
                    val size: Long
                    if (bits == 64) {
                        offset = buffer.getLong(P_OFFSET_64)
                        size = buffer.getLong(P_FILESZ_64)
                    } else {
                        offset = buffer.getInt(P_OFFSET_32).toLong()
                        size = buffer.getInt(P_FILESZ_32).toLong()
                    }

                    if (offset <= 0 || size <= 0 || size > MAX_INTERPRETER || offset + size > raf.length()) {
                        return null
                    }
                    val text = ByteArray(size.toInt())
                    raf.seek(offset)
                    raf.readFully(text)
                    return String(text, Charsets.US_ASCII).trimEnd('\u0000')
                }
                null
            }
        } catch (error: IOException) {
            null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }


        val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

        const val EI_CLASS = 4
        const val EI_DATA = 5

        const val ELFCLASS32 = 1
        const val ELFCLASS64 = 2
        const val ELFDATA2MSB = 2

        const val E_TYPE_OFFSET = 16
        const val E_MACHINE_OFFSET = 18
        const val E_PHOFF_32_OFFSET = 28
        const val E_PHOFF_64_OFFSET = 32
        const val E_PHNUM_32_OFFSET = 44
        const val E_PHNUM_64_OFFSET = 56

        const val PROGRAM_HEADER_32_SIZE = 32
        const val PROGRAM_HEADER_64_SIZE = 56

        const val P_OFFSET_32 = 4
        const val P_FILESZ_32 = 16
        const val P_OFFSET_64 = 8
        const val P_FILESZ_64 = 32

        const val PT_INTERP = 3
        const val MAX_PROGRAM_HEADERS = 16

        /** No real loader path is longer than this; a larger claim is a corrupt file. */
        const val MAX_INTERPRETER = 4096

        const val HEADER_64_SIZE = 64

        /** Android's dynamic loaders. A Wine built for Android names one of these. */
        val BIONIC_LOADERS = listOf("/system/bin/linker", "/apex/com.android.runtime/bin/linker")
}
