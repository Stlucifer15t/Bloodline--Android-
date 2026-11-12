package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import com.bloodline.android.runtime.backends.GraphicsSelection
import com.bloodline.android.runtime.backends.GraphicsBackendSelector
import com.bloodline.android.runtime.process.CommandRunner
import com.bloodline.android.runtime.process.ProcessRunner

/**
 * The one object the launcher talks to.
 *
 * It owns the launch lifecycle and nothing else. It does not know what an
 * Activity is, it does not touch a View, and it holds no Android types: every
 * decision it makes is a function of [RuntimeCapabilities] (measured device
 * facts), [RuntimeConfiguration] (what the user chose), and the validation
 * result for the installation. That is what "decoupled from `MainActivity`"
 * has to mean to be testable — and it is why all of it runs on the JVM in a
 * unit test rather than needing an emulator.
 *
 * The lifecycle, which is the sequence the brief asks for:
 *
 * ```
 * Idle ─▶ Validating ─▶ Preparing ─▶ Initialising ─▶ Launching ─▶ InGame
 *   ▲                        │             │              │          │
 *   └──────── Restored ◀─────┴─────────────┴──────────────┴──────────┘
 *                     (always, on success or failure)
 * ```
 *
 * [Phase.Restored] is reached from every failure too: the launcher is put back
 * the way it was found, including the screen orientation, before the user is
 * told what went wrong.
 */
class RuntimeManager(
    private val configuration: RuntimeConfiguration = RuntimeConfiguration(),

    /**
     * Where the runtime's binaries come from, or null when the launcher has
     * not been given one.
     *
     * Null means "no runtime is available", which is the honest starting
     * state: the gate below must not claim otherwise just because nothing has
     * contradicted it.
     */
    private val provider: RuntimeProvider? = null,

    /**
     * Builds the command that starts the game, or null when the launcher has
     * not been given a runtime to build one from.
     */
    private val launcher: RuntimeLauncher? = null,

    /**
     * Brings Wine up, or null when there is no runtime to bring up.
     *
     * Separate from [launcher] because it is a different question. The launcher
     * works out *what to run*; this runs it and finds out whether it worked.
     */
    private val initialiser: WineInitialiser? = null,

    /** Starts processes. Null when the manager is being used without one. */
    private val runner: CommandRunner? = null,

    /** Called with each line the guest prints, for the diagnostics log. */
    private val onOutput: (String) -> Unit = {},
) {

    /**
     * Called when the game process ends, from the runner's thread.
     *
     * The game is not waited for — it runs for hours — so its end has to be an
     * event rather than a return value. Without this there is no way to give
     * the screen back at the right moment; with only a return value, the
     * launcher would either hand the screen back while the game is still
     * running or never hand it back at all.
     */
    var onProcessExit: ((GameProcessManager.ExitResult) -> Unit)? = null

    private var capabilities: RuntimeCapabilities = RuntimeCapabilities.unknown()

    private var processManager = GameProcessManager()

    private var phase: Phase = Phase.Idle

    private var lastError: String? = null

    /** The result of the last Wine initialisation, for the diagnostics report. */
    private var lastInitialisation: WineInitialiser.Report? = null

    /** The handle on a running game process, so it can be stopped. */
    private var gameHandle: ProcessRunner.Handle? = null

    /** The plan the last [start] would have executed, for the report. */
    private var lastPlan: RuntimeLauncher.LaunchPlan? = null

    /** Where the lifecycle has got to. */
    fun phase(): Phase = phase

    /** The capabilities the last [prepare] used. */
    fun capabilities(): RuntimeCapabilities = capabilities

    /** Feeds in device measurements. Cheap, idempotent, no side effects. */
    fun prepare(measured: RuntimeCapabilities) {
        capabilities = measured
    }

    /** The graphics backend this device would use, and whether it was a fallback. */
    fun graphicsSelection(): GraphicsSelection = GraphicsBackendSelector.select(
        capabilities = capabilities,
        preferred = configuration.graphicsBackend,
    )

    /** The CPU translator this device would use. */
    fun cpuTranslator(): CpuTranslator = configuration.cpuTranslator
        ?: CpuTranslator.recommendedFor(capabilities)

    /** The Wine flavour the launch would use. */
    fun flavour(): WineFlavour = WineFlavour.DEFAULT

    /** The audio bridge this device would use. */
    fun audioBridge(): AudioBridge = configuration.audioBackend
        ?: AudioBridge.recommendedFor(capabilities)

    /**
     * Decides whether a launch may even be attempted.
     *
     * This is the honest half of the milestone: it returns a [LaunchDecision.No]
     * for every state the runtime cannot yet handle, with a reason written for a
     * person to read. It does not throw, and it does not silently substitute a
     * different backend.
     */
    fun decide(validation: InstallValidation?): LaunchDecision {
        val checks = buildList {
            add(Check("Installation validated", validation is InstallValidation.Valid))
            // The runtime is acquired, not bundled, so these are questions
            // about what is installed on this device rather than statements
            // about what the APK contains.
            add(Check("Runtime provider", satisfied = provider != null))
            if (provider != null) {
                val missing = provider.missing()
                add(
                    Check(
                        "Runtime packages installed",
                        satisfied = missing.isEmpty(),
                        detail = missing.firstOrNull(),
                    ),
                )
                // Recorded as installed is not the same as usable: a marker
                // file is a record, not a binary. This asks whether the files
                // the launch needs actually resolve.
                val flavour = flavour()
                add(
                    Check(
                        "Runtime files found",
                        satisfied = provider.isUsable(flavour),
                        detail = if (provider.isUsable(flavour)) {
                            null
                        } else {
                            "The runtime packages are installed but their files could not be found."
                        },
                    ),
                )
            }
        }

        val missing = checks.filter { !it.satisfied }
        if (missing.isNotEmpty()) {
            return LaunchDecision.No(
                reason = missing.first().detail ?: missing.first().name,
                checks = checks,
            )
        }

        val valid = validation as? InstallValidation.Valid
            ?: return LaunchDecision.No(
                reason = "The game installation has not been validated.",
                checks = checks,
            )

        return LaunchDecision.Yes(executablePath = valid.install.executablePath)
    }

    /**
     * Walks the lifecycle as far as it can.
     *
     * @return the phase it stopped at. A caller compares against [Phase.InGame]
     *         and never assumes success.
     */
    fun start(validation: InstallValidation?): Phase {
        phase = Phase.Validating
        when (val decision = decide(validation)) {
            is LaunchDecision.No -> {
                lastError = decision.reason
                phase = Phase.Restored(decision.reason)
                return phase
            }
            is LaunchDecision.Yes -> Unit
        }

        phase = Phase.Preparing

        // The step that makes a 32-bit executable runnable: the WoW64 DLL has
        // to be inside the prefix, in system32, or Wine finds nothing to host
        // the game with.
        val flavour = flavour()
        when (val prepared = launcher?.installWow64Layer(flavour)) {
            null -> Unit
            is RuntimeLauncher.Preparation.Ready -> Unit
            is RuntimeLauncher.Preparation.Failed -> {
                lastError = prepared.reason
                phase = Phase.Restored(prepared.reason)
                return phase
            }
        }

        val graphics = graphicsSelection()
        val translator = cpuTranslator()

        phase = Phase.Initialising(graphics, translator)

        // The command is empty until binaries exist, and onLaunch refuses an
        // empty command rather than pretending to start something. That refusal
        // is the mechanism which keeps the Launch button honest.
        val command = commandFor(validation)
        if (command.isEmpty()) {
            val reason = lastPlan?.missing?.firstOrNull()
                ?: "The runtime could not build a command to start the game with."
            lastError = reason
            phase = Phase.Restored(reason)
            return phase
        }

        // Wine is brought up before the game is asked for, and its failure is
        // reported as the stage that failed rather than as "the runtime is not
        // ready". Files on disk are not an initialised runtime.
        when (val broughtUp = bringUpWine()) {
            null -> Unit // No initialiser: nothing can be checked, so nothing is claimed.
            else -> {
                lastInitialisation = broughtUp
                val failed = broughtUp.failedAt
                if (failed != null) {
                    lastError = failed.describe()
                    phase = Phase.Restored(failed.describe())
                    return phase
                }
            }
        }

        if (!processManager.onLaunch(command)) {
            val state = processManager.state()
            val reason = (state as? GameProcessManager.State.Failed)?.reason
                ?: "The game process could not be started."
            lastError = reason
            phase = Phase.Restored(reason)
            return phase
        }

        phase = Phase.Launching

        // Started, not waited for: the game runs for hours. The callback is how
        // its exit reaches the process manager from the runner's thread.
        gameHandle = runner?.launch(
            ProcessRunner.Request(
                command = command,
                environment = lastPlan?.environment.orEmpty(),
                workingDirectory = lastPlan?.workingDirectory?.let { java.io.File(it) },
            ),
            onLine = onOutput,
            onExit = { outcome ->
                val result = when (outcome) {
                    is ProcessRunner.Outcome.Exited -> processManager.onExit(outcome.exitCode)

                    // No exit status exists in either case, and saying so is
                    // the point: a process that was killed or never started is
                    // "vanished", not "exited cleanly with code 0".
                    is ProcessRunner.Outcome.TimedOut -> {
                        lastError = "the game was killed after ${outcome.timeoutMs}ms"
                        processManager.onReaped()
                    }

                    is ProcessRunner.Outcome.Failed -> {
                        lastError = outcome.reason
                        processManager.onMissing(outcome.reason)
                        processManager.onReaped()
                    }
                }
                onProcessExit?.invoke(result)
            },
        )

        phase = Phase.InGame
        return phase
    }

    /**
     * Runs the Wine initialisation sequence.
     *
     * @return null when there is no [initialiser], so that "nothing was
     *         checked" is never reported as "everything was fine".
     */
    private fun bringUpWine(): WineInitialiser.Report? =
        initialiser?.initialise(
            flavour = flavour(),
            environment = lastPlan?.environment.orEmpty(),
            plan = lastPlan,
            onLine = onOutput,
        )

    /**
     * Records that the game ended and returns the launcher to rest.
     *
     * Always ends at [Phase.Restored], whether the game closed cleanly, crashed
     * or was reaped by Android — which is what guarantees orientation and
     * launcher state are put back.
     */
    fun stop(reason: StopReason): GameProcessManager.ExitResult {
        // The process is told to stop first: a launcher that has handed back
        // the screen while the game is still running has not shut down at all.
        gameHandle?.cancel()
        gameHandle = null

        val result = when (reason) {
            StopReason.UserRequested -> processManager.onStopped()
            StopReason.Vanished -> processManager.onReaped()
            is StopReason.Exited -> processManager.onExit(reason.exitCode, reason.signal)
        }
        phase = Phase.Restored(result.reason.name)
        return result
    }

    /** Empties the manager back to [Phase.Idle] after the launcher is restored. */
    fun acknowledgeRestored() {
        processManager.reset()
        lastError = null
        phase = Phase.Idle
    }

    /** The last failure, for the UI to show. Null once acknowledged. */
    fun error(): String? = lastError

    /**
     * The command that would start the game.
     *
     * Empty, always, until there is a Wine loader to put in it. Returning empty
     * rather than a placeholder is deliberate: a placeholder command would fail
     * somewhere far away from its real cause, whereas an empty one is refused
     * immediately by [GameProcessManager.onLaunch] with the reason attached.
     */
    private fun commandFor(validation: InstallValidation?): List<String> {
        if (validation !is InstallValidation.Valid) return emptyList()

        val builder = launcher ?: return emptyList()
        val plan = builder.plan(
            validation = validation,
            flavour = flavour(),
            translator = cpuTranslator(),
            backend = graphicsSelection().backend ?: return emptyList(),
            capabilities = capabilities,
            audio = audioBridge(),
        )
        lastPlan = plan

        // An unviable plan produces no command at all. That is the mechanism
        // which keeps the Launch button honest: the refusal happens here, next
        // to its cause, rather than somewhere downstream where the reason has
        // been lost.
        return if (plan.isViable) plan.command else emptyList()
    }

    /** The plan the last [start] built, or null. Includes unviable ones. */
    fun plan(): RuntimeLauncher.LaunchPlan? = lastPlan

    /** How far Wine got the last time it was brought up, or null. */
    fun initialisation(): WineInitialiser.Report? = lastInitialisation

    /** True while a game process is still running. */
    fun isRunning(): Boolean = processManager.isRunning

    /** One precondition, with the detail shown to the user when it fails. */
    data class Check(
        val name: String,
        val satisfied: Boolean,
        val detail: String? = null,
    )

    /** The outcome of [decide]. */
    sealed interface LaunchDecision {
        data class Yes(val executablePath: String) : LaunchDecision
        data class No(val reason: String, val checks: List<Check>) : LaunchDecision
    }

    /** Why a run ended, from the caller's point of view. */
    sealed interface StopReason {
        data object UserRequested : StopReason
        data object Vanished : StopReason
        data class Exited(val exitCode: Int, val signal: Int? = null) : StopReason
    }

    /** Where the lifecycle is. Every failure path returns to the launcher. */
    sealed interface Phase {

        data object Idle : Phase

        data object Validating : Phase

        data object Preparing : Phase

        /** Backends and translators have been chosen. */
        data class Initialising(
            val graphics: GraphicsSelection,
            val translator: CpuTranslator,
        ) : Phase

        data object Launching : Phase

        data object InGame : Phase

        /** The launcher is back in control. Carries why, including on success. */
        data class Restored(val cause: String) : Phase
    }
}
