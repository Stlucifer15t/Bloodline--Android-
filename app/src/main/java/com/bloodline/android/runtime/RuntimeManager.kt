package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import com.bloodline.android.runtime.backends.GraphicsSelection
import com.bloodline.android.runtime.backends.GraphicsBackendSelector

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
) {

    private var capabilities: RuntimeCapabilities = RuntimeCapabilities.unknown()

    private var processManager = GameProcessManager()

    private var phase: Phase = Phase.Idle

    private var lastError: String? = null

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
        if (!processManager.onLaunch(command)) {
            val state = processManager.state()
            val reason = (state as? GameProcessManager.State.Failed)?.reason
                ?: "The game process could not be started."
            lastError = reason
            phase = Phase.Restored(reason)
            return phase
        }

        phase = Phase.Launching
        phase = Phase.InGame
        return phase
    }

    /**
     * Records that the game ended and returns the launcher to rest.
     *
     * Always ends at [Phase.Restored], whether the game closed cleanly, crashed
     * or was reaped by Android — which is what guarantees orientation and
     * launcher state are put back.
     */
    fun stop(reason: StopReason): GameProcessManager.ExitResult {
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
