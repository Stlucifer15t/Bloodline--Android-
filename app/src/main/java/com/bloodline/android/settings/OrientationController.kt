package com.bloodline.android.settings

/**
 * Decides which orientation the app asks Android for, and remembers what to
 * put back.
 *
 * Why this exists at all: the launcher used to carry a "screen orientation"
 * setting, and that setting was wrong in two ways. It only ever affected the
 * launcher's own screens, so it controlled nothing the user cared about; and
 * it was a single global preference, which meant it could contradict the one
 * orientation requirement that is not a preference — the game is a landscape
 * Windows title and has to run landscape regardless.
 *
 * This controller separates the two. The launcher phase follows the device.
 * The gameplay phase forces landscape and records what the launcher was using,
 * so leaving the game restores it instead of stranding the user sideways.
 *
 * It touches no Android class: the mapping to `ActivityInfo` constants lives in
 * [OrientationApplier], which is why the behaviour here is unit testable.
 */
class OrientationController(
    /** Receives the request; in an Activity this sets `requestedOrientation`. */
    private val apply: (OrientationRequest) -> Unit,
) {

    private var current: OrientationRequest? = null
    private var launcherRequest: OrientationRequest = OrientationRequest.FOLLOW_DEVICE
    private var phase: OrientationPhase = OrientationPhase.LAUNCHER

    val currentPhase: OrientationPhase get() = phase

    val currentRequest: OrientationRequest? get() = current

    /**
     * Called by the launcher and the settings screen when they take the screen.
     *
     * The default is [OrientationRequest.FOLLOW_DEVICE]: the launcher is usable
     * either way up, so rotating is left to the device rather than pinned by a
     * preference.
     */
    fun applyLauncher(request: OrientationRequest = OrientationRequest.FOLLOW_DEVICE) {
        launcherRequest = request
        phase = OrientationPhase.LAUNCHER
        commit(request)
    }

    /**
     * The game is about to take the screen.
     *
     * Landscape here is a requirement, not a stored preference: it overrides
     * whatever the launcher was doing, and nothing the user chose in settings
     * can weaken it.
     */
    fun enterGameplay() {
        phase = OrientationPhase.GAMEPLAY
        commit(OrientationRequest.SENSOR_LANDSCAPE)
    }

    /** Leaving the game: hand the orientation back to the launcher phase. */
    fun exitGameplay() {
        phase = OrientationPhase.LAUNCHER
        commit(launcherRequest)
    }

    private fun commit(request: OrientationRequest) {
        // Asking Android for the orientation it is already in can force a
        // needless configuration change, so only real changes are sent.
        if (current == request) return
        current = request
        apply(request)
    }
}
