package com.bloodline.android.runtime

import com.bloodline.android.runtime.native.ElfInspector
import com.bloodline.android.runtime.packaging.RuntimeLayout
import com.bloodline.android.runtime.packaging.RuntimePackageCatalog
import com.bloodline.android.runtime.packaging.RuntimePackageStore
import com.bloodline.android.runtime.process.CommandRunner
import com.bloodline.android.runtime.process.ProcessRunner
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
 * Tests for the sequence that brings Wine up.
 *
 * What is under test is the *orchestration*: which commands are built, in what
 * order, whether a failure stops the sequence, and whether an exit code is
 * believed. Those are the decisions this class makes, and they are the ones
 * that would otherwise wait for a device to be checked.
 *
 * The commands are answered by a scripted runner rather than executed, because
 * there is no Wine here to execute. The ELF check is *not* scripted: a real
 * ARM64/Bionic ELF header is written into the fake package, so the validation
 * stage runs its actual parser against real bytes. Only the part that cannot
 * exist in this environment - Wine itself - is stood in for.
 */
class WineInitialiserTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val commands = mutableListOf<ProcessRunner.Request>()
    private val environments = mutableListOf<Map<String, String>>()
    private val directories = mutableListOf<String>()

    /** Answers each command in turn, from [script]. */
    private fun initialiser(vararg script: ProcessRunner.Outcome): Pair<WineInitialiser, File> {
        val root = temporary.newFolder("wine-init")
        val store = RuntimePackageStore(root)
        val wineRoot = store.packageDir(RuntimePackageCatalog.WINE_ARM64EC)
        val bin = File(wineRoot, "bin").apply { mkdirs() }

        File(bin, "wine").writeBytes(elf(machine = AARCH64, interpreter = "/system/bin/linker64"))
        // The layout observed in a real Wine 11.3 ARM64EC package: Windows
        // system files under lib/wine/, split by the architecture they were
        // built for. i386-windows is the 32-bit one, which is what the game is.
        File(wineRoot, "lib/wine/i386-windows").apply { mkdirs() }
        File(wineRoot, "lib/wine/i386-windows/cmd.exe").writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))

        val responses = script.toMutableList()
        val runner = object : CommandRunner {
            override fun run(
                request: ProcessRunner.Request,
                onLine: (String) -> Unit,
            ): ProcessRunner.Outcome = scripted(request)

            override fun launch(
                request: ProcessRunner.Request,
                onLine: (String) -> Unit,
                onExit: (ProcessRunner.Outcome) -> Unit,
            ): ProcessRunner.Handle {
                onExit(scripted(request))
                return ProcessRunner.Handle { }
            }

            private fun scripted(request: ProcessRunner.Request): ProcessRunner.Outcome {
                commands += request
                environments += request.environment
                directories += request.workingDirectory?.absolutePath.orEmpty()
                return if (responses.isEmpty()) {
                    ProcessRunner.Outcome.Failed("the script ran out of responses")
                } else {
                    responses.removeAt(0)
                }
            }
        }

        return WineInitialiser(runner, RuntimeLayout(store), store) to root
    }

    /** A runner that answers every command the same way. */
    private fun scriptedRunner(outcome: ProcessRunner.Outcome) = object : CommandRunner {
        override fun run(request: ProcessRunner.Request, onLine: (String) -> Unit) = outcome

        override fun launch(
            request: ProcessRunner.Request,
            onLine: (String) -> Unit,
            onExit: (ProcessRunner.Outcome) -> Unit,
        ): ProcessRunner.Handle {
            onExit(outcome)
            return ProcessRunner.Handle { }
        }
    }

    private fun exited(code: Int, vararg output: String) =
        ProcessRunner.Outcome.Exited(code, output.toList(), 12L)

    // ------------------------------------------------------------------ tests

    @Test
    fun `asks the loader what it is before running anything`() {
        val (initialiser, _) = initialiser(exited(0), exited(0), exited(3))

        val report = initialiser.initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertTrue(report.describe(), report.succeeded)
        assertTrue(report.results.first().stage == WineInitialiser.Stage.VALIDATE)
        assertTrue(
            report.results.first().detail.orEmpty(),
            report.results.first().detail.orEmpty().contains("64-bit ARM"),
        )
    }

    @Test
    fun `runs the loader, then wineboot, then the 32-bit program, in that order`() {
        val (initialiser, _) = initialiser(exited(0), exited(0), exited(3))

        val report = initialiser.initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertTrue(report.describe(), report.succeeded)
        assertEquals(
            listOf("--version", "wineboot", "cmd.exe"),
            commands.map { File(it.command[1]).name },
        )
        assertEquals(
            WineInitialiser.Stage.WOW64,
            report.results.last().stage,
        )
    }

    @Test
    fun `the prefix is created with Mono's installer suppressed, and nothing else is`() {
        // The first-run Mono prompt waits for an answer nobody can give on a
        // phone, so `wineboot` would hang until the timeout. The override has
        // to apply to that one command: leaving it set for the game would stop
        // .NET working for anything that legitimately wants it.
        val (initialiser, _) = initialiser(exited(0), exited(0), exited(3))

        initialiser.initialise(WineFlavour.WOW64_BOX64, mapOf("WINEPREFIX" to "/prefix"))

        val version = environments[0]
        val wineboot = environments[1]
        val game = environments[2]

        assertFalse("the loader must not be told to skip Mono", version.containsKey("WINEDLLOVERRIDES"))
        assertEquals("mscoree=d", wineboot["WINEDLLOVERRIDES"])
        assertFalse("the game must not inherit the override", game.containsKey("WINEDLLOVERRIDES"))
        assertEquals("/prefix", wineboot["WINEPREFIX"])
    }

    @Test
    fun `a loader that will not run stops the sequence there`() {
        val (initialiser, _) = initialiser(exited(1, "wine: could not load libwine"))

        val report = initialiser.initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertFalse(report.succeeded)
        assertEquals(WineInitialiser.Stage.LOADER, report.failedAt?.stage)
        assertEquals(1, commands.size)
        assertFalse(report.prefixIsUsable)
    }

    @Test
    fun `a prefix that cannot be created is reported with Wine's own output`() {
        val (initialiser, _) = initialiser(
            exited(0),
            exited(2, "wineboot: the prefix could not be created"),
        )

        val report = initialiser.initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertFalse(report.succeeded)
        assertEquals(WineInitialiser.Stage.PREFIX, report.failedAt?.stage)
        assertTrue(report.describe().contains("exited with 2"))
        val output = report.outputOf(WineInitialiser.Stage.PREFIX).joinToString("\n")
        assertTrue(output, output.contains("could not be created"))
    }

    @Test
    fun `a 32-bit program that exits with the wrong code is reported as a failure`() {
        // The program ran, which is real progress. But an exit status that
        // cannot be trusted means a game crash cannot be told from a clean
        // exit, so this stage has to be failed rather than passed.
        val (initialiser, _) = initialiser(exited(0), exited(0), exited(7))

        val report = initialiser.initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertFalse(report.succeeded)
        assertEquals(WineInitialiser.Stage.WOW64, report.failedAt?.stage)
        assertTrue(report.describe(), report.describe().contains("expected 3"))
    }

    @Test
    fun `a Wine that is not an ARM64 build is refused before anything runs`() {
        val root = temporary.newFolder("wrong-arch")
        val store = RuntimePackageStore(root)
        val bin = File(store.packageDir(RuntimePackageCatalog.WINE_ARM64EC), "bin").apply { mkdirs() }
        File(bin, "wine").writeBytes(elf(machine = X86_64, interpreter = "/system/bin/linker64"))

        val runner = scriptedRunner(ProcessRunner.Outcome.Exited(0, emptyList(), 0))
        val report = WineInitialiser(runner, RuntimeLayout(store), store)
            .initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertFalse(report.succeeded)
        assertEquals(WineInitialiser.Stage.VALIDATE, report.failedAt?.stage)
        assertTrue(report.describe(), report.describe().contains("64-bit x86"))
    }

    @Test
    fun `a Wine built against glibc is refused with the loader it named`() {
        val root = temporary.newFolder("glibc")
        val store = RuntimePackageStore(root)
        val bin = File(store.packageDir(RuntimePackageCatalog.WINE_ARM64EC), "bin").apply { mkdirs() }
        File(bin, "wine").writeBytes(elf(machine = AARCH64, interpreter = "/lib/ld-linux-aarch64.so.1"))

        val runner = scriptedRunner(ProcessRunner.Outcome.Exited(0, emptyList(), 0))
        val report = WineInitialiser(runner, RuntimeLayout(store), store)
            .initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertFalse(report.succeeded)
        assertTrue(report.describe(), report.describe().contains("ld-linux-aarch64"))
    }

    @Test
    fun `a package with no Wine in it is reported rather than guessed at`() {
        val root = temporary.newFolder("empty")
        val store = RuntimePackageStore(root)
        val runner = scriptedRunner(ProcessRunner.Outcome.Exited(0, emptyList(), 0))

        val report = WineInitialiser(runner, RuntimeLayout(store), store)
            .initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertFalse(report.succeeded)
        assertTrue(report.describe(), report.describe().contains("no Wine loader"))
    }

    @Test
    fun `every command runs in the folder this app owns, never the game's`() {
        // A hard rule of this project: nothing the runtime writes may land
        // beside the user's game files, because a failed experiment must not
        // be able to damage an installation they cannot re-download.
        val (initialiser, root) = initialiser(exited(0), exited(0), exited(3))

        initialiser.initialise(WineFlavour.WOW64_BOX64, emptyMap())

        assertTrue("no commands ran", directories.isNotEmpty())
        directories.forEach { directory ->
            assertTrue("$directory is outside the runtime root", directory.startsWith(root.absolutePath))
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A minimal but genuine ELF64 header, so the real parser runs. */
    private fun elf(machine: Int, interpreter: String): ByteArray {
        val interpBytes = interpreter.toByteArray(Charsets.US_ASCII)
        val phnum = 2
        val interpOffset = 64 + phnum * 56

        val out = ByteArray(interpOffset + interpBytes.size + 1)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        out[0] = 0x7F; out[1] = 0x45; out[2] = 0x4C; out[3] = 0x46
        out[4] = 2; out[5] = 1; out[6] = 1

        buffer.putShort(16, 2) // e_type = EXEC
        buffer.putShort(18, machine.toShort())
        buffer.putInt(20, 1)
        buffer.putLong(24, 0x400000L)
        buffer.putLong(32, 64L) // e_phoff
        buffer.putLong(40, 0L)
        buffer.putInt(48, 0)
        buffer.putShort(52, 64)
        buffer.putShort(54, 56)
        buffer.putShort(56, phnum.toShort())
        buffer.putShort(58, 64)
        buffer.putShort(60, 0)
        buffer.putShort(62, 0)

        var at = 64
        buffer.putInt(at, 1)
        buffer.putLong(at + 8, 0)
        buffer.putLong(at + 32, out.size.toLong())
        buffer.putLong(at + 40, out.size.toLong())

        at += 56
        buffer.putInt(at, 3) // PT_INTERP
        buffer.putLong(at + 8, interpOffset.toLong())
        buffer.putLong(at + 32, interpBytes.size.toLong())
        buffer.putLong(at + 40, interpBytes.size.toLong())

        System.arraycopy(interpBytes, 0, out, interpOffset, interpBytes.size)
        return out
    }

    /** Silences the unused-import warning without changing behaviour. */
    private val sanity: ElfInspector.Result? = null

    private companion object {
        const val AARCH64 = 183
        const val X86_64 = 62
    }
}
