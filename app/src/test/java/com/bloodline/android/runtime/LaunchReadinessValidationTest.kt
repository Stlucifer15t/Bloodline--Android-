package com.bloodline.android.runtime

import com.bloodline.android.game.GameInstall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the install-validation gate in the launch decision.
 *
 * The existing [LaunchReadinessTest] covers the component gate; this covers the
 * newer half: a complete-looking folder whose executable has not been checked
 * (or failed the check) must not open the gate, even if every runtime component
 * one day reports ready.
 */
class LaunchReadinessValidationTest {

    private fun install(complete: Boolean = true) = GameInstall(
        location = com.bloodline.android.game.GameLocation(
            kind = com.bloodline.android.game.LocationKind.EXTERNAL_FOLDER,
            reference = "game",
            displayPath = "/storage/game",
        ),
        hasDataDirectory = complete,
    )

    private val allReady = RuntimeRegistry
        .components(nativeHosting = IntegrationStatus.READY)
        .map { it.copy(status = IntegrationStatus.READY) }

    private fun validation() = InstallValidation.Valid(
        install = ValidatedInstall(
            rootPath = "/storage/game",
            executablePath = "/storage/game/Vampire.exe",
            machine = PeMachine.I386,
            sizeBytes = 4L * 1024 * 1024,
            subsystem = PeSubsystem.WINDOWS_GUI,
        ),
    )

    @Test
    fun `blocks when the executable has not been checked at all`() {
        val state = LaunchReadiness.evaluate(
            install = install(),
            components = allReady,
            validation = null,
        )

        assertEquals(LaunchState.Blocked(BlockReason.INSTALL_NOT_VALIDATED), state)
    }

    @Test
    fun `blocks when the executable failed its check`() {
        val state = LaunchReadiness.evaluate(
            install = install(),
            components = allReady,
            validation = InstallValidation.Invalid("/storage/game", "64-bit x86"),
        )

        assertEquals(LaunchState.Blocked(BlockReason.INSTALL_NOT_VALIDATED), state)
    }

    @Test
    fun `allows launch only when the executable is validated`() {
        val state = LaunchReadiness.evaluate(
            install = install(),
            components = allReady,
            validation = validation(),
        )

        assertEquals(LaunchState.Ready, state)
    }

    @Test
    fun `the runtime gate is still reported first, because it is the real one`() {
        // Order decides which message the user sees. The runtime being unfinished
        // is the actual state of this build, so it wins over the unchecked
        // executable, and it wins as an offer to download rather than as a wall.
        val state = LaunchReadiness.evaluate(
            install = install(),
            components = RuntimeRegistry.components(),
            validation = null,
        )

        assertEquals(LaunchState.NeedsRuntime, state)
    }

    @Test
    fun `the runtime gate becomes a wall again on a device that cannot run the packages`() {
        // Offering a 227 MB download to a device that cannot execute it is worse
        // than saying why it cannot happen, so the two are separate states.
        val state = LaunchReadiness.evaluate(
            install = install(),
            components = RuntimeRegistry.components(),
            validation = null,
            runtimeAcquirable = false,
        )

        assertEquals(LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED), state)
    }

    @Test
    fun `a missing install outranks both other gates`() {
        val state = LaunchReadiness.evaluate(
            install = null,
            components = allReady,
            validation = validation(),
        )

        assertEquals(LaunchState.Blocked(BlockReason.NO_GAME_FILES), state)
    }
}
