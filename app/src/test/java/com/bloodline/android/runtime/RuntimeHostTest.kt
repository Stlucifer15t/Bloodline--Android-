package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the launch lifecycle.
 *
 * The property that matters most is the one that is easy to get wrong: **the
 * screen is always handed back**. Forcing landscape and then failing to restore
 * it leaves the app stuck sideways with no game running, which is the kind of
 * bug that only shows up on the failure paths — so the failure paths are what
 * these tests drive.
 */
class RuntimeHostTest {

    /** Records everything the host was told, so a test can assert the sequence. */
    private class RecordingHost : RuntimeHost.Host {
        val events = mutableListOf<String>()

        override fun enterGameplay() { events += "enterGameplay" }
        override fun exitGameplay() { events += "exitGameplay" }
        override fun onPhaseChanged(phase: RuntimeManager.Phase) { events += "phase:${phase::class.simpleName}" }
        override fun onFinished(message: String) { events += "finished:$message" }
        override fun onError(message: String) { events += "error:$message" }
    }

    private fun host() = RecordingHost()

    private fun validInstall() = InstallValidation.Valid(
        install = ValidatedInstall(
            rootPath = "/storage/game",
            executablePath = "/storage/game/Vampire.exe",
            machine = PeMachine.I386,
            sizeBytes = 4L * 1024 * 1024,
            subsystem = PeSubsystem.WINDOWS_GUI,
        ),
    )

    private val glesDevice = RuntimeCapabilities(
        abis = listOf("arm64-v8a"),
        glesVersion = "OpenGL ES 3.2",
        glRenderer = "Mali-G52 MC2",
        gpuNodes = setOf("mali0"),
        measured = true,
    )

    // ------------------------------------------------------------- the block

    @Test
    fun `refuses to launch when the runtime is not integrated`() {
        val recording = host()
        val runtime = RuntimeHost(recording).apply {
            prepare(glesDevice)
            setValidation(validInstall())
        }

        val phase = runtime.launch()

        assertTrue(phase is RuntimeManager.Phase.Restored)
        assertTrue(recording.events.any { it.startsWith("error:") })
    }

    @Test
    fun `does not hand the screen over when the launch did not happen`() {
        // The whole point of the finally block: no game, no forced landscape.
        val recording = host()
        RuntimeHost(recording).apply {
            prepare(glesDevice)
            setValidation(validInstall())
        }.launch()

        assertFalse(recording.events.contains("enterGameplay"))
        assertFalse(recording.events.contains("exitGameplay"))
    }

    @Test
    fun `refuses to launch when the install was rejected`() {
        val recording = host()
        val runtime = RuntimeHost(recording).apply {
            prepare(glesDevice)
            setValidation(
                InstallValidation.Invalid("/storage/game", "This is a 64-bit x86 executable."),
            )
        }

        assertTrue(runtime.launch() is RuntimeManager.Phase.Restored)
    }

    @Test
    fun `refuses to launch when nothing has been validated`() {
        val recording = host()
        val runtime = RuntimeHost(recording).apply { prepare(glesDevice) }

        assertTrue(runtime.launch() is RuntimeManager.Phase.Restored)
        assertFalse(recording.events.contains("enterGameplay"))
    }

    // ------------------------------------------------------------ the report

    @Test
    fun `says which components are missing rather than just refusing`() {
        val runtime = RuntimeHost(host()).apply { prepare(glesDevice) }
        val decision = RuntimeManager().decide(validInstall())

        val blocked = decision as RuntimeManager.LaunchDecision.No
        val checks = blocked.checks.associate { it.name to it.satisfied }

        assertEquals(false, checks["Wine present"])
        assertEquals(false, checks["CPU translation present"])
        assertEquals(false, checks["Container present"])
        assertEquals(false, checks["Graphics backend present"])
        assertEquals(true, checks["Installation validated"])
    }

    @Test
    fun `the block reason names the first missing component`() {
        val decision = RuntimeManager().decide(validInstall()) as RuntimeManager.LaunchDecision.No

        assertTrue(decision.reason.contains("Wine"))
    }

    // --------------------------------------------------------- the selection

    @Test
    fun `reports the backend it would use, even though it cannot launch`() {
        val runtime = RuntimeHost(host()).apply { prepare(glesDevice) }
        val selection = runtime.selection()

        assertEquals(GraphicsBackend.VIRGL_WINE_D3D, selection.graphics.backend)
        assertTrue(selection.translator == CpuTranslator.BOX86 || !selection.translator.needs32BitUserland)
    }

    // ------------------------------------------------------------- stopping

    @Test
    fun `stopping reports why the run ended`() {
        val recording = host()
        val runtime = RuntimeHost(recording).apply { prepare(glesDevice) }

        runtime.stop(RuntimeManager.StopReason.Vanished)

        assertTrue(recording.events.any { it.startsWith("finished:") })
        assertTrue(recording.events.first { it.startsWith("finished:") }.contains("Android"))
    }

    @Test
    fun `a clean exit says so`() {
        val recording = host()
        val runtime = RuntimeHost(recording).apply { prepare(glesDevice) }

        runtime.stop(RuntimeManager.StopReason.Exited(exitCode = 0))

        assertTrue(recording.events.any { it.contains("finished:The game closed") })
    }

    @Test
    fun `acknowledging returns to idle`() {
        val runtime = RuntimeHost(host()).apply {
            prepare(glesDevice)
            stop(RuntimeManager.StopReason.Exited(exitCode = 0))
            acknowledge()
        }

        assertTrue(runtime.phase() is RuntimeManager.Phase.Idle)
    }

    // ----------------------------------------------------------- diagnostics

    @Test
    fun `the diagnostics section records what is absent, not just what works`() {
        val runtime = RuntimeHost(host()).apply {
            prepare(glesDevice)
            setValidation(validInstall())
        }

        val section = runtime.diagnosticsSection(capabilities = glesDevice)

        assertEquals("not present", section["Wine version"])
        assertEquals("validated", section["Install"])
        assertEquals("Mali-G52 MC2", section["GPU renderer"])
        assertTrue(section["Graphics backend"]!!.contains("VirGL"))
    }

    @Test
    fun `the diagnostics section states the graphics reason`() {
        val runtime = RuntimeHost(host()).apply { prepare(glesDevice) }
        val section = runtime.diagnosticsSection(capabilities = glesDevice)

        assertTrue(section["Graphics reason"]!!.isNotBlank())
        assertEquals("no", section["Graphics fell back"])
    }
}
