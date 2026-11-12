package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import com.bloodline.android.runtime.packaging.RuntimeLayout
import com.bloodline.android.runtime.packaging.RuntimePackage
import com.bloodline.android.runtime.packaging.RuntimePackageCatalog
import com.bloodline.android.runtime.packaging.RuntimePackageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for turning the runtime's decisions into a command line.
 *
 * The property under test is the one that keeps the Launch button honest:
 * **a plan is only viable when the files it names actually exist.** An empty
 * command with a list of reasons is the correct output when they do not —
 * not a plausible-looking command that fails somewhere far from its cause.
 *
 * The second property is the ARM64EC distinction. With an ARM64EC Wine the
 * command is `wine <exe>` and `HODLL` names the WoW64 layer; with an x86_64
 * Wine the command is `box64 wine <exe>` and no `HODLL` is set at all. Getting
 * that backwards is the difference between a 32-bit game starting and nothing
 * happening.
 */
class RuntimeLauncherTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val capabilities = RuntimeCapabilities(
        abis = listOf("arm64-v8a"),
        glesVersion = "OpenGL ES 3.2 build 1.9@1234",
        glRenderer = "Mali-G52 MC2",
        glExtensions = "GL_OES_EGL_image GL_ARM_shader_framebuffer_fetch",
        gpuNodes = setOf("mali0"),
        measured = true,
    )

    private fun store(): RuntimePackageStore {
        val store = RuntimePackageStore(temporary.newFolder("runtime"))
        // A Wine package laid out the way the upstream build is: bin/wine, and
        // a share/wine/wine.inf the prefix update looks for.
        write(store, RuntimePackageCatalog.WINE_ARM64EC, "bin/wine", "wine")
        write(store, RuntimePackageCatalog.WINE_ARM64EC, "share/wine/wine.inf", "[Version]")
        write(store, RuntimePackageCatalog.WOWBOX64, "wowbox64.dll", "dll")
        write(store, RuntimePackageCatalog.BOX64_BIONIC, "box64", "box64")
        return store
    }

    private fun write(store: RuntimePackageStore, pack: RuntimePackage, name: String, body: String) {
        File(store.packageDir(pack), name).apply {
            parentFile?.mkdirs()
            writeText(body)
        }
    }

    private fun storeWithoutWow64(): RuntimePackageStore {
        val store = store()
        File(store.packageDir(RuntimePackageCatalog.WOWBOX64), "wowbox64.dll").delete()
        return store
    }

    private fun install() = InstallValidation.Valid(
        install = ValidatedInstall(
            rootPath = "/storage/game",
            executablePath = "/storage/game/Vampire.exe",
            machine = PeMachine.I386,
            sizeBytes = 4L * 1024 * 1024,
            subsystem = PeSubsystem.WINDOWS_GUI,
        ),
    )

    private fun plan(
        store: RuntimePackageStore = store(),
        flavour: WineFlavour = WineFlavour.WOW64_BOX64,
    ) = RuntimeLauncher(store).plan(
        validation = install(),
        flavour = flavour,
        backend = GraphicsBackend.GLES_WRAPPER_WINE_D3D,
        capabilities = capabilities,
        audio = AudioBridge.AAUDIO,
    )

    // ---------------------------------------------------------- the ARM64EC path

    @Test
    fun `an ARM64EC Wine is started directly, with no emulator in front`() {
        val plan = plan()

        assertTrue(plan.missing.toString(), plan.isViable)
        assertEquals(2, plan.command.size)
        assertTrue(plan.command[0].endsWith("bin/wine"))
        assertEquals("D:\\Vampire.exe", plan.command[1])
    }

    @Test
    fun `the WoW64 layer is named in the environment`() {
        // The whole mechanism that lets a 32-bit executable run.
        val environment = plan().environment

        assertEquals("wowbox64.dll", environment["HODLL"])
        assertEquals("win64", environment["WINEARCH"])
    }

    @Test
    fun `FEX names a different WoW64 layer`() {
        assertEquals("libwow64fex.dll", plan(flavour = WineFlavour.WOW64_FEX).environment["HODLL"])
    }

    @Test
    fun `an x86_64 Wine is run through Box64 and sets no HODLL`() {
        // Box64 is already emulating the whole Wine process, so a WoW64 layer
        // would be redundant — and setting one would be cargo cult.
        val plan = plan(flavour = WineFlavour.X86_64_BOX64)

        assertTrue(plan.missing.toString(), plan.isViable)
        assertEquals(3, plan.command.size)
        assertTrue(plan.command[0].endsWith("box64"))
        assertTrue(plan.command[1].endsWith("bin/wine"))
        assertEquals("D:\\Vampire.exe", plan.command[2])
        assertNull(plan.environment["HODLL"])
    }

    // ---------------------------------------------------------- what is missing

    @Test
    fun `a missing WoW64 layer makes the plan unviable rather than hopeful`() {
        val plan = plan(store = storeWithoutWow64())

        assertFalse(plan.isViable)
        assertTrue(plan.command.isEmpty())
        assertTrue(plan.missing.any { it.contains("wowbox64.dll") })
    }

    @Test
    fun `the reason names the 32-bit consequence, not just the file`() {
        // "wowbox64.dll not found" is a file error. What the user needs to
        // know is that the game cannot start without it.
        val reason = plan(store = storeWithoutWow64()).missing
            .first { it.contains("wowbox64.dll") }

        assertTrue(reason.contains("32-bit"))
    }

    @Test
    fun `a Wine package with no loader in it is reported`() {
        val store = store()
        File(store.packageDir(RuntimePackageCatalog.WINE_ARM64EC), "bin/wine").delete()

        val plan = plan(store = store)

        assertFalse(plan.isViable)
        assertTrue(plan.missing.any { it.contains("loader") })
    }

    // ------------------------------------------------ installing the WoW64 layer

    @Test
    fun `the WoW64 layer is copied into the prefix's system32`() {
        val store = store()
        val launcher = RuntimeLauncher(store)

        val result = launcher.installWow64Layer(WineFlavour.WOW64_BOX64)

        assertTrue(result is RuntimeLauncher.Preparation.Ready)
        assertTrue(
            File(layout(store).wow64TargetDirectory(), "wowbox64.dll").isFile,
        )
    }

    @Test
    fun `a missing WoW64 package is a failed preparation, not a silent one`() {
        val result = RuntimeLauncher(storeWithoutWow64()).installWow64Layer(WineFlavour.WOW64_BOX64)

        assertTrue(result is RuntimeLauncher.Preparation.Failed)
        assertTrue((result as RuntimeLauncher.Preparation.Failed).reason.contains("wowbox64.dll"))
    }

    @Test
    fun `an x86_64 Wine needs no WoW64 installation step`() {
        // Nothing to copy: Box64 provides 32-bit support by translating the
        // entire process.
        assertTrue(
            RuntimeLauncher(store()).installWow64Layer(WineFlavour.X86_64_BOX64)
                is RuntimeLauncher.Preparation.Ready,
        )
    }

    // ------------------------------------------------------- registry and audio

    @Test
    fun `registry settings become wine reg add commands`() {
        val store = store()
        val launcher = RuntimeLauncher(store)
        val commands = launcher.registryCommands(plan(store))

        assertTrue(commands.isNotEmpty())
        commands.forEach { command ->
            assertEquals("wine", File(command.first()).name)
            assertEquals("reg", command[1])
            assertEquals("add", command[2])
        }
        assertTrue(commands.any { it.contains("HKEY_CURRENT_USER\\Software\\Wine\\Direct3D") })
        assertTrue(commands.any { it.contains("HKEY_CURRENT_USER\\Software\\Wine\\Drivers") })
    }

    @Test
    fun `the Mali workaround is applied without changing the translator`() {
        // Upstream disables Box64's 32-bit memory mapping on Mali; it does not
        // switch to FEX. The setting and the choice of emulator are separate.
        val plan = plan()

        assertEquals("0", plan.environment["BOX64_MMAP32"])
        assertEquals(CpuTranslator.WOWBOX64, CpuTranslator.recommendedFor(capabilities))
    }

    @Test
    fun `the audio path is configured through ALSA, the way upstream does`() {
        val plan = plan()

        assertTrue(plan.environment["ALSA_CONFIG_PATH"]!!.endsWith("asound.conf"))
        assertTrue(plan.environment["ALSA_PLUGIN_DIR"]!!.isNotBlank())
        assertTrue(File(plan.environment["ALSA_CONFIG_PATH"]).isFile)
    }

    @Test
    fun `the game starts in its own directory, not in the runtime's`() {
        assertEquals("/storage/game", plan().workingDirectory)
    }

    @Test
    fun `the graphics decision is recorded on the plan`() {
        val plan = plan()

        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, plan.graphics.backend)
        assertTrue(plan.graphics.summary().contains("GLES wrapper"))
    }

    private fun layout(store: RuntimePackageStore) = RuntimeLayout(store)
}
