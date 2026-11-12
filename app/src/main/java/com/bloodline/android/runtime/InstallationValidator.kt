package com.bloodline.android.runtime

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decides whether a folder really holds a Windows copy of Bloodlines.
 *
 * The existing `GameInstallScanner` stops at the filename: it finds a file
 * called `Vampire.exe` and checks that a `Vampire` folder sits beside it. That
 * is enough to populate a folder picker and not much else. It cannot tell the
 * difference between the real executable and:
 *
 * * a **64-bit** `Vampire.exe`, which no Box86-only path can run;
 * * a **non-Windows** file of the same name — a Linux ELF binary, a text file,
 *   a zero-byte placeholder left by a failed download;
 * * a **truncated** download that has the right name and the right first two
 *   bytes but is missing most of itself.
 *
 * So this reads the **PE header**: the `MZ` marker, the `e_lfanew` offset, the
 * `PE\0\0` signature and the COFF machine type. That is the same check Wine
 * itself performs before it will load an image, which is the point — if Wine
 * would refuse the file, the launcher should say so before the user presses
 * Launch, not after.
 *
 * Read-only, and only ever read-only: it never writes to the user's game
 * folder. It takes an [InputStream] rather than a `File` precisely so that the
 * caller controls what gets opened (a SAF document, a test fixture), and so
 * that this class cannot reach for a path and modify it.
 */
object InstallationValidator {

    /** Files that must exist beside the executable for the game to be playable. */
    private val REQUIRED_ENTRIES = listOf(
        RequiredEntry("Vampire", isDirectory = true, "the game's data folder"),
        RequiredEntry("Vampire.exe", isDirectory = false, "the game executable"),
    )

    /** The executable we are looking for. */
    const val EXECUTABLE_NAME = "Vampire.exe"

    /** Bloodlines is a 32-bit game; this is the machine type we expect. */
    val EXPECTED_MACHINE: PeMachine = PeMachine.I386

    /**
     * The smallest `Vampire.exe` that could possibly be real. A full install is
     * orders of magnitude larger, so anything under this is a truncated or
     * placeholder file rather than a game.
     */
    const val MIN_EXECUTABLE_BYTES = 1L * 1024 * 1024

    /**
     * Validates one installation.
     *
     * @param entries   the names in the folder, as `name → isDirectory`.
     * @param openExecutable opens the executable for reading. Called at most
     *                       once, always closed by this method.
     */
    fun validate(
        path: String,
        entries: Map<String, Boolean>,
        openExecutable: () -> InputStream,
    ): InstallValidation {
        val missing = REQUIRED_ENTRIES.filter { required -> !hasEntry(entries, required) }
        if (missing.isNotEmpty()) {
            return InstallValidation.Invalid(
                path = path,
                reason = "This folder is missing ${missing.joinToString(", ") { it.description }}.",
                detail = missing.joinToString(", ") { it.name },
            )
        }

        val header = try {
            openExecutable().use { readPeHeader(it) }
        } catch (error: Exception) {
            return InstallValidation.Invalid(
                path = path,
                reason = "The executable could not be read (${error.message}).",
                detail = EXECUTABLE_NAME,
            )
        }

        if (!header.isPortableExecutable) {
            return InstallValidation.Invalid(
                path = path,
                reason = header.problem
                    ?: "This file is not a Windows executable, so Wine cannot load it.",
                detail = header.describe(),
            )
        }

        if (header.sizeBytes < MIN_EXECUTABLE_BYTES) {
            return InstallValidation.Invalid(
                path = path,
                reason = "The executable is only ${header.sizeBytes.formatBytes()}, which " +
                    "is far too small to be the real game. This usually means a " +
                    "truncated or partially downloaded file.",
                detail = header.describe(),
            )
        }

        if (header.machine != EXPECTED_MACHINE) {
            // Shown under the Launch button, so it says what to do rather than
            // only what went wrong.
            return InstallValidation.Invalid(
                path = path,
                reason = "This executable is built for " +
                    "${header.machine?.description ?: "an unsupported CPU"}. " +
                    "Bloodlines is a 32-bit x86 game, and this build needs the " +
                    "32-bit Windows version.",
                detail = header.describe(),
            )
        }

        return InstallValidation.Valid(
            install = ValidatedInstall(
                rootPath = path,
                executablePath = if (path.isEmpty()) EXECUTABLE_NAME else "$path/$EXECUTABLE_NAME",
                machine = header.machine,
                sizeBytes = header.sizeBytes,
                subsystem = header.subsystem,
            ),
        )
    }

    /**
     * Reads the parts of the PE header that decide whether Wine would load it.
     *
     * Only the first [MAX_HEADER_BYTES] are kept — every field of interest is a
     * fixed offset from the start and the whole interesting region is well
     * inside that — but the stream is drained to the end so that [PeHeader
     * .sizeBytes] is the real file size. A truncated download needs to be
     * recognised by its size, and a size measured from a 4 KB window cannot do
     * that.
     *
     * Never throws: a malformed file is a *result*, not an exception.
     */
    fun readPeHeader(input: InputStream): PeHeader {
        val head = ByteArray(MAX_HEADER_BYTES)
        var read = 0
        var total = 0L
        val tail = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = if (read < head.size) {
                input.read(head, read, head.size - read)
            } else {
                input.read(tail)
            }
            if (count < 0) break
            total += count
            if (read < head.size) read += count
        }
        return parse(head.copyOf(read), sizeBytes = total)
    }

    fun parse(bytes: ByteArray, sizeBytes: Long = bytes.size.toLong()): PeHeader {
        if (bytes.size < 2 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) {
            return PeHeader(
                isPortableExecutable = false,
                problem = "The file does not begin with the MZ marker, so it is not " +
                    "a Windows executable.",
                sizeBytes = sizeBytes,
            )
        }

        if (bytes.size < E_LFANEW_OFFSET + 4) {
            return PeHeader(
                isPortableExecutable = false,
                problem = "The DOS header is truncated.",
                sizeBytes = sizeBytes,
            )
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val peOffset = buffer.getInt(E_LFANEW_OFFSET)
        if (peOffset <= 0 || peOffset + PE_SIGNATURE_SIZE > bytes.size) {
            return PeHeader(
                isPortableExecutable = false,
                problem = "The file has no PE header, so it is not a 32- or 64-bit " +
                    "Windows program.",
                sizeBytes = sizeBytes,
            )
        }

        if (bytes[peOffset] != 'P'.code.toByte() ||
            bytes[peOffset + 1] != 'E'.code.toByte() ||
            bytes[peOffset + 2] != 0.toByte() ||
            bytes[peOffset + 3] != 0.toByte()
        ) {
            return PeHeader(
                isPortableExecutable = false,
                problem = "The PE signature is missing.",
                sizeBytes = sizeBytes,
            )
        }

        val coffOffset = peOffset + PE_SIGNATURE_SIZE
        if (coffOffset + COFF_HEADER_SIZE > bytes.size) {
            return PeHeader(
                isPortableExecutable = false,
                problem = "The COFF header is truncated.",
                sizeBytes = sizeBytes,
            )
        }

        buffer.position(coffOffset)
        val machineCode = buffer.short.toInt() and 0xFFFF
        val numberOfSections = buffer.short.toInt() and 0xFFFF
        buffer.int // timestamp
        buffer.int // pointer to symbol table
        buffer.int // number of symbols
        val optionalHeaderSize = buffer.short.toInt() and 0xFFFF
        val characteristics = buffer.short.toInt() and 0xFFFF

        val optionalOffset = coffOffset + COFF_HEADER_SIZE
        val subsystem = if (
            optionalHeaderSize >= SUBSYSTEM_OFFSET + 2 &&
            optionalOffset + SUBSYSTEM_OFFSET + 2 <= bytes.size
        ) {
            buffer.position(optionalOffset + SUBSYSTEM_OFFSET)
            PeSubsystem.fromCode(buffer.short.toInt() and 0xFFFF)
        } else {
            null
        }

        return PeHeader(
            isPortableExecutable = true,
            machine = PeMachine.fromCode(machineCode),
            numberOfSections = numberOfSections,
            characteristics = characteristics,
            subsystem = subsystem,
            sizeBytes = sizeBytes,
            problem = null,
        )
    }

    private const val E_LFANEW_OFFSET = 0x3C
    private const val PE_SIGNATURE_SIZE = 4
    private const val COFF_HEADER_SIZE = 20
    private const val SUBSYSTEM_OFFSET = 68
    private const val MAX_HEADER_BYTES = 4096

    /**
     * Whether [entries] holds [required], comparing the name without case.
     *
     * Case is ignored because the scanner that chose this folder already
     * ignores it: [com.bloodline.android.game.GameInstallScanner] matches
     * `Vampire.exe` and `Vampire` with `ignoreCase = true`. Windows does not
     * care about case either, and a copy made through MTP or off a FAT-formatted
     * card routinely arrives as `VAMPIRE.EXE` / `VAMPIRE`.
     *
     * That mismatch was not cosmetic. The scanner happily reported a complete
     * installation for such a folder, and this check then rejected the very
     * same one for missing both entries — so the launcher sat there saying the
     * files were found and, one card lower, that they had not been verified,
     * with nothing on screen naming case as the reason.
     */
    private fun hasEntry(entries: Map<String, Boolean>, required: RequiredEntry): Boolean =
        entries.any { (name, isDirectory) ->
            name.equals(required.name, ignoreCase = true) && isDirectory == required.isDirectory
        }

    /** Something that should be in the folder. */
    private data class RequiredEntry(
        val name: String,
        val isDirectory: Boolean,
        private val descriptionText: String,
    ) {
        val description: String get() = "$descriptionText ($name)"
    }
}

/** The CPU a Windows executable was built for. */
enum class PeMachine(val code: Int, val description: String) {
    UNKNOWN(0x0000, "unknown"),
    I386(0x014C, "32-bit x86"),
    ARM(0x01C0, "32-bit ARM"),
    ARM64(0xAA64, "64-bit ARM"),
    AMD64(0x8664, "64-bit x86"),
    ;

    companion object {
        fun fromCode(code: Int): PeMachine? = entries.firstOrNull { it.code == code }
    }
}

/** Which Windows subsystem an executable targets. */
enum class PeSubsystem(val code: Int, val description: String) {
    UNKNOWN(0, "unknown"),
    NATIVE(1, "native"),
    WINDOWS_GUI(2, "Windows GUI"),
    WINDOWS_CUI(3, "Windows console"),
    ;

    companion object {
        fun fromCode(code: Int): PeSubsystem? = entries.firstOrNull { it.code == code }
    }
}

/** A readable size, so a report does not print raw byte counts. */
private fun Long.formatBytes(): String = when {
    this >= 1024 * 1024 * 1024 -> "%.2f GB".format(this.toDouble() / (1024 * 1024 * 1024))
    this >= 1024 * 1024 -> "%.1f MB".format(this.toDouble() / (1024 * 1024))
    this >= 1024 -> "%.0f KB".format(this.toDouble() / 1024)
    else -> "$this bytes"
}

/** What the header said. */
data class PeHeader(
    val isPortableExecutable: Boolean,
    val machine: PeMachine? = null,
    val numberOfSections: Int = 0,
    val characteristics: Int = 0,
    val subsystem: PeSubsystem? = null,
    val sizeBytes: Long = 0,
    val problem: String? = null,
) {
    /** One line for a report. */
    fun describe(): String = when {
        !isPortableExecutable -> "not a PE file: ${problem.orEmpty()}"
        else -> buildString {
            append("PE ${machine?.description ?: "unknown machine"}")
            subsystem?.let { append(", ${it.description}") }
            append(", $numberOfSections sections")
            if (sizeBytes > 0) append(", ${sizeBytes.formatBytes()}")
        }
    }
}

/** An installation that passed every check. */
data class ValidatedInstall(
    val rootPath: String,
    val executablePath: String,
    val machine: PeMachine,
    val sizeBytes: Long,
    val subsystem: PeSubsystem?,
)

/** The result of validating a folder. */
sealed interface InstallValidation {

    data class Valid(val install: ValidatedInstall) : InstallValidation

    data class Invalid(
        val path: String,
        val reason: String,
        val detail: String = "",
    ) : InstallValidation
}
