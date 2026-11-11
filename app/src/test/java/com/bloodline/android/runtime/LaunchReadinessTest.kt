package com.bloodline.android.runtime

import com.bloodline.android.game.GameInstall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the rule that matters most: the game can only be launched when a
 * complete installation exists AND every runtime component is ready.
 */
class LaunchReadinessTest {

    private fun install(complete: Boolean) = GameInstall(
        executable = File("/storage/Bloodlines/Vampire.exe"),
        rootDirectory = File("/storage/Bloodlines"),
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
}
