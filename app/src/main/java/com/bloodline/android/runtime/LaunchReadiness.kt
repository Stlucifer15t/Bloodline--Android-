package com.bloodline.android.runtime

import com.bloodline.android.game.GameInstall

/** Why the launch button is currently locked. */
enum class BlockReason {
    /** No Vampire.exe anywhere in the searched folders. */
    NO_GAME_FILES,

    /** Executable found but the game data folder is missing. */
    INCOMPLETE_GAME_FILES,

    /** The Windows compatibility runtime is not integrated yet. */
    RUNTIME_NOT_INTEGRATED,
}

/** Result of asking "can the game be launched right now?". */
sealed interface LaunchState {
    /** Every prerequisite is met. */
    data object Ready : LaunchState

    /** Launching is impossible; [reason] says why. */
    data class Blocked(val reason: BlockReason) : LaunchState
}

/**
 * The single source of truth for the launch button.
 *
 * The rule is deliberately strict: launching needs a complete installation AND
 * every runtime component reporting [IntegrationStatus.READY]. Because the
 * registry currently reports NOT_INTEGRATED for Wine, Box86, the container,
 * graphics and audio, this can only ever return [LaunchState.Blocked] today.
 */
object LaunchReadiness {

    fun evaluate(
        install: GameInstall?,
        components: List<RuntimeComponent> = RuntimeRegistry.components(),
    ): LaunchState {
        // An empty registry means "nothing is integrated", not "everything is
        // ready": all {} would return true for an empty list and open the gate.
        val runtimeReady = components.isNotEmpty() &&
            components.all { it.status == IntegrationStatus.READY }

        return when {
            install == null -> LaunchState.Blocked(BlockReason.NO_GAME_FILES)
            !install.isComplete -> LaunchState.Blocked(BlockReason.INCOMPLETE_GAME_FILES)
            !runtimeReady -> LaunchState.Blocked(BlockReason.RUNTIME_NOT_INTEGRATED)
            else -> LaunchState.Ready
        }
    }
}
