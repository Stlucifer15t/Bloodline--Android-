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

    /**
     * A monotonic milliseconds source for session timing.
     *
     * Injected rather than assumed, for the reason given on
     * [GameProcessManager]: wall-clock time can go backwards, and a run that
     * measures as 0s when it ran for minutes sends everyone looking for a
     * cause that is really a broken clock.
     */
    private val clock: () -> Long = System::currentTimeMillis,

    /**
     * Called with each line the **game** prints, kept apart from [onOutput].
     *
     * Measured, not guessed. The first log off a real device held 2055 lines
     * and every one of them came from `wineboot` during prefix creation —
     * `wineboot.exe`, `explorer.exe`, `iexplore.exe`, `rundll32.exe`. Nothing
     * the game said survived to the end of the buffer, because the buffer had
     * already been spent by the prefix before the game existed.
     *
     * Keeping two streams is what makes the tail meaningful: the prefix's
     * output is read when a *stage* fails, the game's when the *game* fails.
     */
    private val onGameOutput: (String) -> Unit = {},
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

    /**
     * Called every time the lifecycle moves, not only when it stops.
     *
     * Bringing Wine up runs `wine --version`, `wineboot` and a 32-bit program,
     * and the timeouts alone add up to over five minutes. Reporting only the
     * phase it finally reached meant the launcher sat completely silent for
     * that whole time, which is indistinguishable from a button that does
     * nothing. Called on whatever thread the launch runs on.
     */
    var onPhase: ((Phase) -> Unit)? = null

    /**
     * Called before each Wine stage runs, with the stage and where it sits.
     *
     * The three slow stages have timeouts of 10s, 180s and 120s, so "preparing
     * the runtime" on its own can cover five minutes of silence. This names the
     * stage being worked on, which is also the stage a failure will be blamed
     * on. Called on whatever thread the launch runs on.
     */
    var onWineStage: ((WineInitialiser.Stage, Int, Int) -> Unit)? = null

    /**
     * Called when the 32-bit check failed but the launch is going ahead.
     *
     * A warning, not an error: the check runs a stand-in for the game, and
     * the stand-in crashed, so the game is expected to crash too — but
     * "expected" is a prediction, not a result. The launch continues and
     * this is what lets the screen say why the attempt is being made anyway.
     */
    var onWineWarning: ((WineInitialiser.StageResult) -> Unit)? = null

    private var capabilities: RuntimeCapabilities = RuntimeCapabilities.unknown()

    private var processManager = GameProcessManager(clock = clock)

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

    /** Moves the lifecycle on and tells anyone watching. */
    private fun publish(next: Phase) {
        phase = next
        onPhase?.invoke(next)
    }

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
        publish(Phase.Validating)
        when (val decision = decide(validation)) {
            is LaunchDecision.No -> {
                lastError = decision.reason
                publish(Phase.Restored(decision.reason))
                return phase
            }
            is LaunchDecision.Yes -> Unit
        }

        publish(Phase.Preparing)

        // The step that makes a 32-bit executable runnable: the WoW64 DLL has
        // to be inside the prefix, in system32, or Wine finds nothing to host
        // the game with.
        val flavour = flavour()
        when (val prepared = launcher?.installWow64Layer(flavour)) {
            null -> Unit
            is RuntimeLauncher.Preparation.Ready -> Unit
            is RuntimeLauncher.Preparation.Failed -> {
                lastError = prepared.reason
                publish(Phase.Restored(prepared.reason))
                return phase
            }
        }

        val graphics = graphicsSelection()
        val translator = cpuTranslator()

        publish(Phase.Initialising(graphics, translator))

        // The command is empty until binaries exist, and onLaunch refuses an
        // empty command rather than pretending to start something. That refusal
        // is the mechanism which keeps the Launch button honest.
        val command = commandFor(validation)
        if (command.isEmpty()) {
            val reason = lastPlan?.missing?.firstOrNull()
                ?: "The runtime could not build a command to start the game with."
            lastError = reason
            publish(Phase.Restored(reason))
            return phase
        }

        // Wine is brought up before the game is asked for, and its failure is
        // reported as the stage that failed rather than as "the runtime is not
        // ready". Files on disk are not an initialised runtime.
        when (val broughtUp = bringUpWine()) {
            null -> Unit // No initialiser: nothing can be checked, so nothing is claimed.
            else -> {
                lastInitialisation = broughtUp
                val failed = broughtUp.blockingFailure
                if (failed != null) {
                    lastError = failed.describe()
                    publish(Phase.Restored(failed.describe()))
                    return phase
                }

                // A failed 32-bit check does not stop us. It ran a stand-in
                // for the game, not the game, and the stand-in carries a
                // console host the game never touches — so it can fail while
                // the game would have started. Say so loudly, then try the
                // real thing, because the real thing is the evidence.
                broughtUp.warning?.let { onWineWarning?.invoke(it) }
            }
        }

        // The game is launched as `D:\Vampire.exe`, so the prefix must map a
        // `D:` drive to the game's folder. This is a filesystem step (a symlink
        // in `dosdevices`), not a Wine command, so it does not need a display.
        val gameDirectory = java.io.File(
            (validation as InstallValidation.Valid).install.executablePath,
        ).parentFile ?: java.io.File((validation as InstallValidation.Valid).install.rootPath)
        when (val drive = launcher?.prepareGameDrive(gameDirectory)) {
            null -> Unit
            is RuntimeLauncher.Preparation.Ready -> Unit
            is RuntimeLauncher.Preparation.Failed -> {
                lastError = drive.reason
                publish(Phase.Restored(drive.reason))
                return phase
            }
        }

        if (!processManager.onLaunch(command)) {
            val state = processManager.state()
            val reason = (state as? GameProcessManager.State.Failed)?.reason
                ?: "The game process could not be started."
            lastError = reason
            publish(Phase.Restored(reason))
            return phase
        }

        publish(Phase.Launching)

        // Started, not waited for: the game runs for hours. The callback is how
        // its exit reaches the process manager from the runner's thread.
        gameHandle = runner?.launch(
            ProcessRunner.Request(
                command = command,
                environment = lastPlan?.environment.orEmpty(),
                workingDirectory = lastPlan?.workingDirectory?.let { java.io.File(it) },
            ),
            // Two streams from here on: everything still goes to the
            // diagnostics log, and the game's own words go somewhere they
            // cannot be drowned out by what the prefix already printed.
            onLine = { line ->
                onOutput(line)
                onGameOutput(line)
            },
            onExit = { outcome ->
                val result = when (outcome) {
                    // The signal is passed, not dropped. Without it a process
                    // killed by SIGSEGV reports as "exited with 139", and the
                    // one fact that identifies a crash becomes a number that
                    // reads like a program error.
                    is ProcessRunner.Outcome.Exited ->
                        processManager.onExit(outcome.exitCode, outcome.signal)

                    // Three ways to have no exit status, and they are not the
                    // same thing: we gave up, the binary never started, or
                    // Android took the child away. Reported separately.
                    is ProcessRunner.Outcome.TimedOut -> {
                        lastError = "the game was still running after ${outcome.timeoutMs}ms"
                        processManager.onTimedOut(outcome.timeoutMs)
                    }

                    is ProcessRunner.Outcome.Failed -> {
                        lastError = outcome.reason
                        processManager.onLaunchFailed(outcome.reason)
                    }
                }
                onProcessExit?.invoke(result)
            },
        )

        // The pid exists now, and only now. Recorded so that whatever the
        // process went on to do can be tied back to this run in logcat.
        processManager.attachPid(gameHandle?.pid)

        // A game that dies immediately is the common case while the runtime is
        // incomplete, and it is exactly the case this used to get wrong: the
        // exit callback runs on the runner's thread and can fire before this
        // line, so an unconditional publish here overwrote a real exit with
        // "in game". The screen then claimed the game was running while its
        // own crash report had already been written.
        //
        // Publishing InGame only while a process is genuinely alive keeps the
        // two in agreement; when it has already exited, the exit callback is
        // what the UI hears, and it is the accurate one.
        if (processManager.isRunning) {
            publish(Phase.InGame)
        }
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
            onStage = { stage, index, total -> onWineStage?.invoke(stage, index, total) },
            // A prefix that has already been created and verified is not
            // rebuilt: wineboot, every registry write and the 32-bit probe
            // would otherwise run again on every launch, which is minutes of
            // a phone's time spent re-learning a fact.
            reuseInitialisedPrefix = true,
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
        publish(Phase.Restored(result.reason.name))
        return result
    }

    /** Empties the manager back to [Phase.Idle] after the launcher is restored. */
    fun acknowledgeRestored() {
        processManager.reset()
        lastError = null
        publish(Phase.Idle)
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
            wineDebug = configuration.wineDebug,
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

    /**
     * The environment the game would be started with.
     *
     * For diagnostics only, and worth having because it settles the argument
     * that cannot be settled from a pasted failure message: what Wine was
     * actually told to print. A report that says Wine said nothing is only
     * meaningful next to the value of `WINEDEBUG` it was given.
     */
    fun plannedEnvironment(): Map<String, String> = lastPlan?.environment.orEmpty()

    /**
     * Whether the WoW64 host DLL is where Wine will look for it.
     *
     * A sentence rather than a boolean, because a report is read by a person:
     * "ABSENT" sitting next to a null-dereference crash inside ntdll.so is
     * the whole diagnosis in two lines.
     */
    fun wow64HostDll(): String {
        val flavour = flavour()
        if (!flavour.usesWow64Layer) return "not used by ${flavour.title}"
        val file = launcher?.wow64DllInPrefix(flavour)
        return if (file != null) {
            "present at ${file.absolutePath}"
        } else {
            "ABSENT: ${flavour.wow64DllName} is not in the prefix's system32"
        }
    }

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
