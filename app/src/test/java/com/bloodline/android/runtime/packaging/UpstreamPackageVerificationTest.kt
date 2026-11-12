package com.bloodline.android.runtime.packaging

import com.bloodline.android.runtime.native.ElfInspector
import com.bloodline.android.runtime.InstallationValidator
import com.bloodline.android.runtime.WineFlavour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Downloads and installs the real upstream packages, and reports what is
 * actually inside them.
 *
 * Everything else in this project's test suite is honest about its limits: it
 * proves our own logic against data we wrote. This one reaches out to the
 * pinned URLs and finds out whether the pipeline works on the bytes upstream
 * really serves — because the format of `.wcp`, the internal layout of these
 * packages and the name and location of the WoW64 DLL were all *inferred* from
 * reading other people's source. Inferences are what this test exists to
 * replace with observations.
 *
 * It is skipped unless `BLOODLINE_VERIFY_PACKAGES=1`, for two reasons. It
 * fetches hundreds of megabytes from the network, and it should not make an
 * ordinary build depend on GitHub being reachable. CI runs it as a step of its
 * own, and prints the report.
 *
 * What it prints matters as much as what it asserts: the checksums and the
 * layout are the answers to questions the rest of the code currently guesses.
 */
class UpstreamPackageVerificationTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val enabled: Boolean get() = System.getenv("BLOODLINE_VERIFY_PACKAGES") == "1"
    private val includeWine: Boolean get() = System.getenv("BLOODLINE_VERIFY_WINE") == "1"

    @Test
    fun `downloads, verifies and installs every pinned package`() {
        if (!enabled) return

        val packages = buildList {
            add(RuntimePackageCatalog.WOWBOX64)
            if (includeWine) add(RuntimePackageCatalog.WINE_ARM64EC)
        }

        val report = StringBuilder("\n=== upstream package verification ===\n")
        var failures = 0

        packages.forEach { pack ->
            val store = RuntimePackageStore(temporary.newFolder(pack.id))
            val manager = RuntimePackageManager(store)

            val startedAt = System.currentTimeMillis()
            val state = manager.install(pack, "arm64-v8a")
            val seconds = (System.currentTimeMillis() - startedAt) / 1000

            report.append("\n--- ${pack.name} ---\n")
            report.append("url           : ${pack.url}\n")
            report.append("published size: ${pack.sizeBytes}\n")
            report.append("state         : $state\n")
            report.append("took          : ${seconds}s\n")

            val target = store.packageDir(pack)
            if (state is RuntimePackageManager.State.Installed) {
                val staged = File(store.stagingDir(), "${pack.id}-${pack.version}.download")
                report.append("sha256        : ${sha256Of(staged.takeIf { it.isFile } ?: archiveOf(target))}\n")
                report.append("format        : ${ArchiveExtractor().detectFormat(staged.takeIf { it.isFile } ?: File("/dev/null")).title}\n")
                report.append("files         : ${target.walkTopDown().count { it.isFile }}\n")
                report.append("top level     : ${target.listFiles()?.map { it.name }?.sorted() ?: emptyList<String>()}\n")
                report.append(layoutOf(target))
            } else {
                failures++
            }
        }

        println(report)
        writeReport(report.toString())
        assertEquals("every pinned package should install:\n$report", 0, failures)
    }

    @Test
    fun `reports what the Wine package actually contains`() {
        if (!enabled || !includeWine) return
        installWine() ?: return

        val store = wineStore()
        val layout = RuntimeLayout(store)

        val report = StringBuilder("\n=== Wine package layout ===\n")
        report.append("loader        : ${layout.wineLoader(WineFlavour.WOW64_BOX64)}\n")
        report.append("bin dir       : ${layout.wineBinDir(WineFlavour.WOW64_BOX64)}\n")
        report.append("lib dirs      : ${layout.libDirs(WineFlavour.WOW64_BOX64)}\n")
        report.append("windows dir   : ${layout.windowsDirectory(WineFlavour.WOW64_BOX64)}\n")
        report.append("cmd.exe (32)  : ${layout.windowsExecutable("cmd.exe", WineFlavour.WOW64_BOX64, prefer32Bit = true)}\n")
        report.append("cmd.exe (any) : ${layout.windowsExecutable("cmd.exe", WineFlavour.WOW64_BOX64, prefer32Bit = false)}\n")
        report.append("alsa plugin   : ${layout.alsaPlugin(WineFlavour.WOW64_BOX64)}\n")
        report.append("missing       : ${layout.missingFiles(WineFlavour.WOW64_BOX64)}\n")
        report.append("complete      : ${layout.isComplete(WineFlavour.WOW64_BOX64)}\n")

        val loader = layout.wineLoader(WineFlavour.WOW64_BOX64)
        if (loader != null) report.append("loader is     : ${ElfInspector.inspect(loader)}\n")

        val command = layout.windowsExecutable("cmd.exe", WineFlavour.WOW64_BOX64, prefer32Bit = true)
        if (command != null) {
            val header = InstallationValidator.readPeHeader(command.inputStream())
            report.append("cmd.exe is    : ${header.describe()}\n")
        }

        println(report)
        writeReport(report.toString())
        assertTrue("the Wine package should contain a loader:\n$report", loader != null)
    }

    @Test
    fun `reports what the WoW64 package actually contains`() {
        if (!enabled) return
        install(RuntimePackageCatalog.WOWBOX64) ?: return

        val store = RuntimePackageStore(File(temporary.root, RuntimePackageCatalog.WOWBOX64.id))
        val layout = RuntimeLayout(store)
        val target = store.packageDir(RuntimePackageCatalog.WOWBOX64)

        val report = StringBuilder("\n=== WoW64 package layout ===\n")
        report.append("wow64 dll     : ${layout.wow64Dll(WineFlavour.WOW64_BOX64)}\n")
        report.append("files         : ${target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).path }.sorted()}\n")

        val dll = layout.wow64Dll(WineFlavour.WOW64_BOX64)
        if (dll != null) {
            report.append("dll is        : ${InstallationValidator.readPeHeader(dll.inputStream()).describe()}\n")
        }
        println(report)
        writeReport(report.toString())

        assertTrue(
            "the WoW64 package should contain ${WineFlavour.WOW64_BOX64.wow64DllName}:\n$report",
            dll != null,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun install(pack: RuntimePackage): RuntimePackageManager.State.Installed? {
        val store = RuntimePackageStore(temporary.newFolder(pack.id))
        val state = RuntimePackageManager(store).install(pack, "arm64-v8a")
        return state as? RuntimePackageManager.State.Installed
    }

    private fun installWine(): RuntimePackageManager.State.Installed? =
        install(RuntimePackageCatalog.WINE_ARM64EC)

    private fun wineStore(): RuntimePackageStore =
        RuntimePackageStore(File(temporary.root, RuntimePackageCatalog.WINE_ARM64EC.id))

    /** A readable sketch of where things live inside a package. */
    private fun layoutOf(root: File): String {
        val interesting = listOf("wine", "wine64", "wowbox64.dll", "cmd.exe", "wineboot.exe", "wine.inf")
        return buildString {
            append("interesting   :\n")
            interesting.forEach { name ->
                val found = root.walkTopDown()
                    .maxDepth(6)
                    .filter { it.isFile && it.name.equals(name, ignoreCase = true) }
                    .map { it.relativeTo(root).path }
                    .take(3)
                    .toList()
                append("    $name -> ${if (found.isEmpty()) "not found" else found}\n")
            }
        }
    }

    /** Best-effort archive for a checksum: the staging file if it survived. */
    private fun archiveOf(directory: File): File = directory

    /** Writes [text] where CI can pick it up, since stdout is not captured. */
    private fun writeReport(text: String) {
        val out = File("build/upstream-packages.txt")
        out.parentFile?.mkdirs()
        out.appendText(text)
    }

    private fun sha256Of(file: File): String {
        if (!file.isFile) return "(not a file: ${file.name})"
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
