package com.bloodline.android.runtime

import com.bloodline.android.game.GameInstall
import com.bloodline.android.game.GameLocation
import com.bloodline.android.game.LocationKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the rule that matters most: the game can only be launched when a
 * complete installation exists AND every runtime component is ready.
 */
class LaunchReadinessTest {

    private fun install(complete: Boolean) = GameInstall(
        location = GameLocation(
            kind = LocationKind.APP_STORAGE,
            reference = "/storage/Bloodlines",
            displayPath = "/storage/Bloodlines",
        ),
        hasDataDirectory = complete,
    )

    private fun component(status: IntegrationStatus) =
        RuntimeComponent(id = "test", title = "", detail = "", status = status)


    /** An installation whose executable passed the PE header check. */
    private fun validatedInstall() = InstallValidation.Valid(
        install = ValidatedInstall(
            rootPath = "/storage/Bloodlines",
            executablePath = "/storage/Bloodlines/Vampire.exe",
            machine = PeMachine.I386,
            sizeBytes = 4L * 1024 * 1024,
            subsystem = PeSubsystem.WINDOWS_GUI,
        ),
    )

    /**
     * The end state this milestone is building towards: a complete
     * installation, a validated executable, and every runtime component
     * genuinely present.
     *
     * The gate opens here and nowhere else — and it only opens because
     * [RuntimeRegistry] derives its statuses from what is actually installed.
     */
    @Test
    fun `opens when the runtime is installed and everything else checks out`() {
        val provider = StaticRuntimeProvider(
            installed = setOf("wine-11.3-arm64ec", "wowbox64"),
        )
        val components = RuntimeRegistry.forProvider(
            provider = provider,
            capabilities = RuntimeCapabilities(
                abis = listOf("arm64-v8a"),
                glesVersion = "OpenGL ES 3.2",
                measured = true,
            ),
            nativeHosting = IntegrationStatus.READY,
            storage = IntegrationStatus.READY,
        )

        val state = LaunchReadiness.evaluate(
            install = install(complete = true),
            components = components,
            validation = validatedInstall(),
        )

        assertEquals(
            components.filter { it.status != IntegrationStatus.READY }
                .joinToString { it.id },
            LaunchState.Ready,
            state,
        )
    }

    @Test
    fun `stays closed when the runtime is installed but its files are gone`() {
        // A marker file is a record, not a binary.
        val provider = StaticRuntimeProvider(
            installed = setOf("wine-11.3-arm64ec", "wowbox64"),
            missingFiles = listOf("The WoW64 layer was not found."),
        )
        val components = RuntimeRegistry.forProvider(
            provider = provider,
            capabilities = RuntimeCapabilities(measured = true),
            nativeHosting = IntegrationStatus.READY,
            storage = IntegrationStatus.READY,
        )

        val state = LaunchReadiness.evaluate(
            install = install(complete = true),
            components = components,
            validation = validatedInstall(),
        )

        assertEquals(LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED), state)
    }

    @Test
    fun `blocks when no game files are present`() {
        val state = LaunchReadiness.evaluate(
            install = null,
            components = listOf(component(IntegrationStatus.READY)),
        )

        assertEquals(LaunchState.Blocked(BlockReason.NO_GAME_FILES), state)
    }

    @Test
    fun `blocks when the game data folder is missing`() {
        val state = LaunchReadiness.evaluate(
            install = install(complete = false),
            components = listOf(component(IntegrationStatus.READY)),
        )

        assertEquals(LaunchState.Blocked(BlockReason.INCOMPLETE_GAME_FILES), state)
    }

    @Test
    fun `blocks while the runtime is not integrated`() {
        val state = LaunchReadiness.evaluate(
            install = install(complete = true),
            components = RuntimeRegistry.components(),
        )

        assertEquals(LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED), state)
    }

    @Test
    fun `blocks when only some components are ready`() {
        val state = LaunchReadiness.evaluate(
            install = install(complete = true),
            components = listOf(
                component(IntegrationStatus.READY),
                component(IntegrationStatus.PARTIAL),
                component(IntegrationStatus.NOT_INTEGRATED),
            ),
        )

        assertEquals(LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED), state)
    }

    @Test
    fun `blocks when there are no components at all`() {
        val state = LaunchReadiness.evaluate(install = install(complete = true), components = emptyList())

        assertEquals(LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED), state)
    }

    @Test
    fun `allows launch when the install is complete and every component is ready`() {
        val state = LaunchReadiness.evaluate(
            install = install(complete = true),
            components = listOf(component(IntegrationStatus.READY), component(IntegrationStatus.READY)),
            validation = validatedInstall(),
        )

        assertEquals(LaunchState.Ready, state)
    }

    @Test
    fun `blocks when the executable has not been validated, even with every component ready`() {
        // Added with the PE header check: a complete-looking folder whose
        // executable was never read is not a launchable install.
        val state = LaunchReadiness.evaluate(
            install = install(complete = true),
            components = listOf(component(IntegrationStatus.READY), component(IntegrationStatus.READY)),
            validation = null,
        )

        assertEquals(LaunchState.Blocked(BlockReason.INSTALL_NOT_VALIDATED), state)
    }

    @Test
    fun `stays blocked while native hosting has not been verified`() {
        // Everything the registry can vouch for is ready, except the one thing
        // that is measured on the device.
        val components = RuntimeRegistry
            .components(nativeHosting = IntegrationStatus.READY)
            .map { it.copy(status = IntegrationStatus.READY) }
            .map { if (it.id == ComponentIds.NATIVE_HOSTING) it.copy(status = IntegrationStatus.NOT_INTEGRATED) else it }

        val state = LaunchReadiness.evaluate(install = install(complete = true), components = components)

        assertEquals(LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED), state)
    }

    @Test
    fun `allows launch once the whole shipped stack reports ready`() {
        val components = RuntimeRegistry
            .components(nativeHosting = IntegrationStatus.READY)
            .map { it.copy(status = IntegrationStatus.READY) }

        assertEquals(
            LaunchState.Ready,
            LaunchReadiness.evaluate(
                install = install(complete = true),
                components = components,
                validation = validatedInstall(),
            ),
        )
    }
}
