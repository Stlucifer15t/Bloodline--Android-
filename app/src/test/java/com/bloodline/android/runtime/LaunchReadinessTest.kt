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
        )

        assertEquals(LaunchState.Ready, state)
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
            LaunchReadiness.evaluate(install = install(complete = true), components = components),
        )
    }
}
