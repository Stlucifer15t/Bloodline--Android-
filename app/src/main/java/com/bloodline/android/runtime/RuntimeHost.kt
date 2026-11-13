package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.process.CommandRunner
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsSelection

/**
 * Drives a launch from the Launch button to the launcher coming back.
 *
 * Why this class exists rather than putting the sequence in `MainActivity`: the
 * brief asks for the runtime to be decoupled from the activity. It is also the
 * only way the lifecycle can be tested — everything here is a transition, and
 * every one of them can be driven from a unit test with a [Host] that records
 * what it was told.
 *
 * The sequence it guarantees, which is the one the brief asks for:
 *
 * 1. validate the installation;
 * 2. check the runtime dependencies;
 * 3. initialise the chosen graphics backend;
 * 4. launch the executable;
 * 5. **hand the screen to the game** — landscape, through [Host.enterGameplay];
 * 6. watch for the process ending;
 * 7. **give the screen back**, through [Host.exitGameplay];
 * 8. return to the launcher, with the reason recorded.
 *
 * Steps 5 and 7 are the ones that matter structurally: they are on the *same*
 * path as the failure paths. [Host.exitGameplay] is called from a `finally`
 * block, so an orientation forced for a game that never started cannot survive
 * into the launcher — which is the failure mode that leaves an app stuck in
 * landscape with no game running.
 *
 * Holds no Android types. Everything Android-shaped arrives through [Host].
 */
class RuntimeHost(
    private val host: Host,
    private val manager: RuntimeManager = RuntimeManager(),
    private val configuration: RuntimeConfiguration = RuntimeConfiguration(),
) {

    companion object {

        /**
         * Builds a host around a manager that can actually run something.
         *
         * Kept here rather than in the [Android][android.content.Context]-only
         * provider object so that a test can build a host with a real manager
         * without an Android context.
         */
        fun wired(
            host: Host,
            configuration: RuntimeConfiguration = RuntimeConfiguration(),
            provider: RuntimeProvider,
            launcher: RuntimeLauncher,
            initialiser: WineInitialiser,
            runner: CommandRunner,
            onOutput: (String) -> Unit = {},
        ): RuntimeHost = RuntimeHost(
            host = host,
            configuration = configuration,
            manager = RuntimeManager(
                configuration = configuration,
                provider = provider,
                launcher = launcher,
                initialiser = initialiser,
                runner = runner,
                onOutput = onOutput,
            ),
        )
    }

    /** Everything the host needs from Android, and nothing else. */
    interface Host {

        /** Hand the screen to the game: gameplay landscape. */
        fun enterGameplay()

        /** Take the screen back: restore whatever the launcher was using. */
        fun exitGameplay()

        /** Report a state change. Always called on the main thread. */
        fun onPhaseChanged(phase: RuntimeManager.Phase)

        /**
         * What the launch is doing right now, in words for a person.
         *
         * Separate from [onPhaseChanged] because the phases are too coarse to
         * explain a wait: bringing Wine up spends up to three minutes in one
         * phase. Defaults to nothing so a host that only cares about the
         * outcome does not have to implement it.
         */
        fun onLaunchProgress(message: String) {}

        /**
         * The run ended. [message] is written for a person to read.
         *
         * [exit] is why, when a process actually ended. It is passed rather
         * than left to the caller to infer from the wording, because a game
         * that crashed and a game that closed are both "the run ended" and
         * only one of them needs its output put on screen.
         */
        fun onFinished(message: String, exit: GameProcessManager.ExitResult? = null)

        /** Something went wrong before a process existed. */
        fun onError(message: String)

        /**
         * The 32-bit check failed, and the game is being started anyway.
         *
         * Not an error, because nothing has failed yet: the check ran a
         * stand-in for the game, and a stand-in that crashed is a reason to
         * expect a crash, not a result. Delivered as the stage result rather
         * than as a sentence so the host can phrase it — this layer has no
         * [android.content.Context] and so no strings.
         */
        fun onWineWarning(failed: WineInitialiser.StageResult) {}
    }

    private var validation: InstallValidation? = null

    init {
        // The game ends on its own schedule, hours from now, so the screen is
        // given back by that event rather than by [launch] returning. [launch]
        // starts the process and comes straight back.
        manager.onProcessExit = { result ->
            host.exitGameplay()
            host.onFinished(messageFor(result), result)
        }

        // Every phase as it is entered, not just the one it stopped at. The
        // manager is the only thing that knows when it moves, so this is what
        // lets the screen follow a launch that takes minutes.
        manager.onPhase = { phase -> host.onPhaseChanged(phase) }

        // Named rather than counted: "creating the Windows prefix (3 of 5)" is
        // something a user can wait through, where "preparing the runtime"
        // is not. The stage is also the thing a failure gets blamed on, so
        // seeing it arrive is seeing how far the launch actually got.
        manager.onWineStage = { stage, index, total ->
            host.onLaunchProgress("${stage.title} ($index of $total)…")
        }

        // The 32-bit check crashed, and we are launching the game anyway.
        // Said out loud because otherwise the launch looks like it ignored a
        // failure rather than deciding that a stand-in's crash is not proof.
        manager.onWineWarning = { failed -> host.onWineWarning(failed) }
    }

    /** The current lifecycle phase. */
    fun phase(): RuntimeManager.Phase = manager.phase()

    /** Feeds in device measurements. Call once the probes have reported. */
    fun prepare(capabilities: RuntimeCapabilities) = manager.prepare(capabilities)

    /**
     * Remembers the validation result so a launch can be gated on it.
     *
     * Held rather than passed to [launch] so the Launch button and the launch
     * path can never disagree about which installation they are talking about.
     */
    fun setValidation(result: InstallValidation?) {
        validation = result
        if (manager.phase() is RuntimeManager.Phase.Idle) host.onPhaseChanged(manager.phase())
    }

    fun validation(): InstallValidation? = validation

    /**
     * How far Wine got the last time it was brought up, or null.
     *
     * Exposed because a launch failure is a Wine failure, and the only
     * evidence for why is the report this holds: every stage, and the output
     * of the one that broke. A toast can carry one line; a crash inside Wine
     * cannot be understood from one line.
     */
    fun initialisation(): WineInitialiser.Report? = manager.initialisation()

    /**
     * The environment the game would be started with, for diagnostics.
     *
     * A pasted failure that says "Wine printed nothing" is only interpretable
     * next to what Wine was asked to print, and that lives in here.
     */
    fun plannedEnvironment(): Map<String, String> = manager.plannedEnvironment()

    /**
     * What the runtime would use right now, for the UI to show.
     *
     * Reported even though nothing can launch, because "which backend would this
     * device get?" is a device question with a real answer today.
     */
    fun selection(): Selection = Selection(
        graphics = manager.graphicsSelection(),
        translator = manager.cpuTranslator(),
        audio = manager.audioBridge(),
    )

    /**
     * Attempts a launch.
     *
     * Returns the phase it reached. Never throws: every failure is reported to
     * [Host.onError] and ends with the launcher restored.
     */
    fun launch(): RuntimeManager.Phase {
        var handedOver = false
        return try {
            // The screen is only handed over once there is actually something to
            // hand it to, and it is always handed back in the finally block.
            val phase = manager.start(validation)
            publish(phase)

            if (phase is RuntimeManager.Phase.InGame) {
                host.enterGameplay()
                handedOver = true
            } else {
                val reason = manager.error() ?: "The runtime is not ready."
                host.onError(reason)
            }
            phase
        } catch (error: Exception) {
            // An exception here is a bug, not a device limitation, so it is
            // reported with the exception's own words rather than a paraphrase.
            val message = error.message ?: "The runtime failed to start."
            manager.stop(RuntimeManager.StopReason.Vanished)
            publish(manager.phase())
            host.onError(message)
            manager.phase()
        } finally {
            // Only when the game is not actually running. Once it is, the
            // screen comes back from [manager]'s exit callback, and calling
            // exitGameplay here would take it back the moment it was given —
            // which is exactly what would have happened the instant this
            // stopped being a synchronous, fake launch.
            if (handedOver && !manager.isRunning()) {
                host.exitGameplay()
            }
        }
    }

    /**
     * Records that the game ended and restores the launcher.
     *
     * @param reason why, from the caller's point of view. A process that
     *               vanished with no exit status is [RuntimeManager.StopReason
     *               .Vanished], not "exited cleanly".
     */
    fun stop(reason: RuntimeManager.StopReason): GameProcessManager.ExitResult {
        val result = manager.stop(reason)
        publish(manager.phase())
        host.onFinished(messageFor(result), result)
        return result
    }

    /** Back to idle once the launcher has been restored. */
    fun acknowledge() {
        manager.acknowledgeRestored()
        publish(manager.phase())
    }

    /** The runtime section of a diagnostics report. */
    fun diagnosticsSection(
        capabilities: RuntimeCapabilities = RuntimeCapabilities.unknown(),
        errors: List<String> = emptyList(),
        exit: GameProcessManager.ExitResult? = null,
    ): Map<String, String> = RuntimeDiagnostics.section(
        capabilities = capabilities,
        graphics = manager.graphicsSelection(),
        translator = manager.cpuTranslator(),
        audio = manager.audioBridge(),
        configuration = configuration,
        validation = validation,
        phase = manager.phase(),
        exit = exit,
        errors = errors,
        initialisation = manager.initialisation(),
    )

    private fun publish(phase: RuntimeManager.Phase) = host.onPhaseChanged(phase)

    private fun messageFor(result: GameProcessManager.ExitResult): String = when (result.reason) {
        GameProcessManager.ExitReason.CLEAN -> "The game closed."
        GameProcessManager.ExitReason.ERROR_CODE ->
            "The game closed with an error (exit ${result.exitCode})."
        GameProcessManager.ExitReason.SIGNAL ->
            "The game was killed by the system (signal ${result.signal})."
        GameProcessManager.ExitReason.REAPED ->
            // Worth spelling out: this is Android's process cap, not a game bug,
            // and the two need different fixes.
            "The game process was stopped by Android. It ran for " +
                "${result.durationMillis / 1000}s."
        GameProcessManager.ExitReason.STOPPED -> "Stopped."
        GameProcessManager.ExitReason.MISSING -> "The game process was never started."
    }

    /** The three choices the runtime would make right now. */
    data class Selection(
        val graphics: GraphicsSelection,
        val translator: CpuTranslator,
        val audio: AudioBridge,
    )
}
