package com.bloodline.android.runtime

import com.bloodline.android.runtime.InstallationValidator
import com.bloodline.android.runtime.PeHeader
import com.bloodline.android.runtime.PeMachine
import com.bloodline.android.runtime.native.ElfInspector
import com.bloodline.android.runtime.packaging.RuntimeLayout
import com.bloodline.android.runtime.packaging.RuntimePackageStore
import com.bloodline.android.runtime.process.CommandRunner
import com.bloodline.android.runtime.process.ProcessRunner
import java.io.File

/**
 * Brings Wine up, one recorded stage at a time.
 *
 * The rule this class exists to enforce: **files on disk are not an
 * initialised runtime.** Unpacking Wine proves that an archive was readable.
 * It says nothing about whether the binary executes, whether its libraries
 * resolve, whether a prefix can be created, or whether the WoW64 layer can
 * host a 32-bit guest. Those are four different questions with four different
 * answers, and collapsing them into one green tick is how a project ends up
 * reporting a working runtime that has never run.
 *
 * So each stage is run separately and recorded separately, and the first one
 * that fails stops the sequence and is reported with its own output. A
 * diagnostics report built from this says which stage failed and shows what
 * Wine printed, rather than saying "the runtime is not ready".
 *
 * The stages, in the order they must happen:
 *
 * 1. **[Stage.VALIDATE]** — read the loader's ELF header. Cheapest check first,
 *    and it needs no process: the wrong architecture or the wrong C library is
 *    the most likely way to have a Wine that unpacks and never starts.
 * 2. **[Stage.LOADER]** — `wine --version`. Proves the loader executes and
 *    every library it depends on resolves.
 * 3. **[Stage.PREFIX]** — `wine wineboot -u`. Creates the prefix, which is the
 *    virtual Windows installation the game will run inside.
 * 4. **[Stage.REGISTRY]** — apply the graphics and audio settings the launch
 *    plan decided on.
 * 5. **[Stage.WOW64]** — run a 32-bit Windows program that ships with Wine
 *    itself and check its exit code. This is the stage that proves the 32-bit
 *    path works, which is the one this runtime is for.
 *
 * Nothing here touches the user's game files. The prefix and everything Wine
 * writes go under the directory the app owns.
 */
class WineInitialiser(
    private val runner: CommandRunner,
    private val layout: RuntimeLayout,
    private val store: RuntimePackageStore,
) {

    /** One step of bringing the runtime up. */
    enum class Stage(val title: String) {
        VALIDATE("Checking the Wine binary"),
        LOADER("Starting the Wine loader"),
        PREFIX("Creating the Windows prefix"),
        REGISTRY("Applying the launch settings"),
        WOW64("Running a 32-bit Windows program"),
    }

    /**
     * What one stage did.
     *
     * A stage that was skipped is not the same as one that failed, and
     * conflating them is how a report ends up either crying wolf or staying
     * quiet. "This Wine build carries no cmd.exe" means the 32-bit path is
     * *unverified*; "wineboot exited 2" means it is *broken*. Both matter,
     * and only one of them should stop the launch.
     */
    data class StageResult(
        val stage: Stage,
        /** Empty for [Stage.VALIDATE], which runs no process. */
        val command: List<String> = emptyList(),
        val outcome: ProcessRunner.Outcome? = null,
        /** Set when the stage could not even be attempted. */
        val skippedBecause: String? = null,
        /** For [Stage.WOW64]: what the program we ran turned out to be. */
        val detail: String? = null,
        /**
         * True when a skipped stage means the runtime cannot carry on.
         *
         * False for a skip that merely leaves something unverified, such as a
         * Wine build that carries no 32-bit program to test with.
         */
        val blocking: Boolean = true,

        /**
         * The exit status that counts as success here. Zero, except for the
         * 32-bit check.
         *
         * That stage asks its program to exit 3, on purpose: a program that
         * exits 0 proves much less, because 0 is also what you get from a
         * process that never really ran. Demanding a specific non-zero code
         * proves the status was carried back across the process boundary
         * rather than defaulted — which is the same thing a game crash has to
         * be reported through.
         */
        val successExitCode: Int = 0,
    ) {

        /** A process ran and finished with the status this stage asked for. */
        val succeeded: Boolean get() =
            outcome is ProcessRunner.Outcome.Exited && outcome.exitCode == successExitCode

        /** Something went wrong, and the runtime should stop here. */
        val failed: Boolean get() = when (outcome) {
            null -> skippedBecause != null && blocking
            is ProcessRunner.Outcome.Exited -> outcome.exitCode != successExitCode
            is ProcessRunner.Outcome.Failed -> true
            is ProcessRunner.Outcome.TimedOut -> true
        }

        /** Nothing was measured: the stage was skipped rather than run. */
        val performed: Boolean get() = outcome != null

        /** One line for a report. */
        fun describe(): String = when {
            skippedBecause != null -> "${stage.title}: skipped, $skippedBecause"
            outcome == null -> "${stage.title}: ok"
            outcome is ProcessRunner.Outcome.Exited && outcome.exitCode != successExitCode ->
                "${stage.title}: ${outcome.describe()} (expected $successExitCode)"
            else -> "${stage.title}: ${outcome.describe()}"
        }
    }

    /** The whole sequence. */
    data class Report(val results: List<StageResult>) {

        /** Nothing failed. Skipped stages do not count against this. */
        val succeeded: Boolean get() = results.none { it.failed }

        /** The first stage that went wrong, if any. */
        val failedAt: StageResult? get() = results.firstOrNull { it.failed }

        /**
         * The failure that means the game cannot be attempted at all.
         *
         * Nearly every stage earns its veto: a loader that will not execute,
         * or a prefix that will not initialise, leaves nothing to launch
         * into. [Stage.WOW64] is the exception, and it is an exception on
         * principle rather than by convenience.
         *
         * Every other stage tests Wine itself. This one tests a **stand-in
         * for the game** — a 32-bit program that ships with Wine — and a
         * stand-in that dies is evidence, not proof. Worse, this particular
         * stand-in is `cmd.exe`, a console program, so Wine also has to host
         * `conhost.exe` to give it a console. The game is a graphical
         * program and never touches any of that. The check can therefore
         * fail for a reason that has nothing to do with whether the game
         * would have started.
         *
         * Refusing to launch on the strength of it is the one move that
         * guarantees we learn nothing: the real program never runs, so its
         * own output — the only evidence that actually settles the question
         * — is never produced.
         */
        val blockingFailure: StageResult? get() =
            results.firstOrNull { it.failed && it.stage != Stage.WOW64 }

        /**
         * A failure worth shouting about but not worth stopping for.
         *
         * Only [Stage.WOW64] can appear here. It is reported to the user and
         * then the game is attempted anyway, on the reasoning in
         * [blockingFailure].
         */
        val warning: StageResult? get() =
            results.firstOrNull { it.failed && it.stage == Stage.WOW64 }

        /**
         * Stages that were skipped, so nothing was actually measured.
         *
         * Reported separately rather than folded into success or failure:
         * "Wine is up but the 32-bit path was never exercised" is a sentence
         * someone needs to be able to read.
         */
        val unverified: List<Stage> get() = results.filter { !it.performed }.map { it.stage }

        /** True when Wine is up far enough to be worth launching anything. */
        val prefixIsUsable: Boolean get() =
            results.filter { it.stage == Stage.VALIDATE || it.stage == Stage.LOADER || it.stage == Stage.PREFIX }
                .takeIf { it.size == 3 }
                ?.none { it.failed } == true

        fun describe(): String = results.joinToString("\n") { it.describe() }

        /** The output of a stage that ran, for a diagnostics report. */
        fun outputOf(stage: Stage): List<String> =
            results.firstOrNull { it.stage == stage }?.outcome
                ?.let { if (it is ProcessRunner.Outcome.Exited) it.output else emptyList() }
                ?: emptyList()
    }

    /**
     * Runs the sequence.
     *
     * @param flavour which Wine build to bring up.
     * @param environment the environment the game will run with. Used as-is,
     *        because the prefix has to be created with the same settings the
     *        game will later run under — creating it with different ones is a
     *        reliable way to get a prefix that behaves differently on launch.
     * @param plan the launch plan, whose registry settings are applied.
     * @param onLine called with each line Wine prints.
     * @param onStage called with each stage before it runs, and where it sits
     *        in the sequence. The stages are the slow part — the three that run
     *        a process carry timeouts of 10s, 180s and 120s — so a caller that
     *        only hears at the end leaves the screen blank for minutes.
     */
    fun initialise(
        flavour: WineFlavour,
        environment: Map<String, String>,
        plan: RuntimeLauncher.LaunchPlan? = null,
        onLine: (String) -> Unit = {},
        onStage: (Stage, Int, Int) -> Unit = { _, _, _ -> },
    ): Report {
        val results = mutableListOf<StageResult>()
        val total = Stage.entries.size
        fun starting(stage: Stage) = onStage(stage, stage.ordinal + 1, total)

        // ---- 1. what the binary actually is --------------------------------
        starting(Stage.VALIDATE)
        val loader = layout.wineLoader(flavour)
        if (loader == null) {
            results += StageResult(
                Stage.VALIDATE,
                skippedBecause = "no Wine loader was found inside the Wine package",
            )
            return Report(results)
        }

        when (val inspected = ElfInspector.inspect(loader)) {
            is ElfInspector.Result.NotAnElf ->
                results += StageResult(Stage.VALIDATE, skippedBecause = inspected.reason)
            is ElfInspector.Result.Is -> {
                val info = inspected.info
                val problem = when {
                    !info.isArm64 ->
                        "${loader.name} is a ${info.machine.title} build, and this device needs an ARM64 one."
                    !info.isBionic ->
                        "${loader.name} was linked against ${info.interpreter}, which this device does not have. " +
                            "A Wine built for Android names /system/bin/linker64."
                    else -> null
                }
                if (problem != null) {
                    results += StageResult(Stage.VALIDATE, skippedBecause = problem)
                    return Report(results)
                }
                results += StageResult(Stage.VALIDATE, detail = info.toString())
            }
        }

        // ---- 2. does the loader run at all ---------------------------------
        starting(Stage.LOADER)
        val version = run(
            Stage.LOADER,
            listOf(loader.absolutePath, "--version"),
            environment,
            timeoutMs = LOADER_TIMEOUT_MS,
            onLine = onLine,
        )
        results += version
        if (!version.succeeded) return Report(results)

        // ---- 3. create the prefix ------------------------------------------
        starting(Stage.PREFIX)
        // `mscoree` is disabled for this one command and no other. Wine's first
        // run offers to download Mono, and .NET's installer waits for an answer
        // nobody can give on a phone: the prefix creation hangs until the
        // timeout. The game's own overrides are untouched.
        val prefix = run(
            Stage.PREFIX,
            listOf(loader.absolutePath, "wineboot", "-u"),
            environment + (WINEDLLOVERRIDES to "mscoree=d"),
            timeoutMs = PREFIX_TIMEOUT_MS,
            onLine = onLine,
        )
        results += prefix
        if (!prefix.succeeded) return Report(results)

        // ---- 4. the settings the game will run under -----------------------
        starting(Stage.REGISTRY)
        val settings = plan?.registrySettings.orEmpty()
        if (settings.isEmpty()) {
            results += StageResult(
                Stage.REGISTRY,
                skippedBecause = "the launch plan carries no settings to apply",
                blocking = false,
            )
        } else {
            var failed: StageResult? = null
            settings.forEach { setting ->
                val applied = run(
                    Stage.REGISTRY,
                    setting.toCommand(loader.absolutePath),
                    environment,
                    timeoutMs = REGISTRY_TIMEOUT_MS,
                    onLine = onLine,
                )
                if (!applied.succeeded && failed == null) failed = applied
            }
            results += failed ?: StageResult(Stage.REGISTRY)
            if (failed != null) return Report(results)
        }

        // ---- 5. a 32-bit Windows program -----------------------------------
        starting(Stage.WOW64)
        results += runWow64Test(flavour, loader, environment, onLine)

        return Report(results)
    }

    /**
     * Runs a 32-bit Windows program that ships with Wine and checks the exit
     * code that comes back.
     *
     * `cmd.exe /c exit 3` is used because it is a program, not a library; it
     * terminates on its own; and the exit code is ours to choose, so a 3 that
     * comes back proves the code was carried across the process boundary
     * rather than being a default. A command that exits 0 proves much less,
     * because 0 is also what you get from a process that never really ran.
     */
    private fun runWow64Test(
        flavour: WineFlavour,
        loader: File,
        environment: Map<String, String>,
        onLine: (String) -> Unit,
    ): StageResult {
        val candidate = layout.windowsExecutable("cmd.exe", flavour, prefer32Bit = true)
            ?: return StageResult(
                Stage.WOW64,
                skippedBecause = "this Wine build does not carry cmd.exe, so no 32-bit program could be run",
                blocking = false,
            )

        val architecture = describeWindowsProgram(candidate)

        // Where the 32-bit support is supposed to come from, stated as a fact
        // in the same breath as the crash. A tombstone from a real device showed
        // a null dereference inside ntdll.so with not one frame of the WoW64
        // host DLL in it — which is what "Wine was told to load something that
        // was not there" looks like. Naming whether it is there turns that from
        // a guess into a reading.
        val hostInPrefix = layout.wow64DllInPrefix(flavour)

        val result = run(
            Stage.WOW64,
            listOf(loader.absolutePath, candidate.absolutePath, "/c", "exit", WOW64_EXIT_CODE.toString()),
            environment,
            timeoutMs = WOW64_TIMEOUT_MS,
            onLine = onLine,
        )

        val exited = result.outcome as? ProcessRunner.Outcome.Exited
        val detail = buildString {
            append(architecture)
            append(" · ")
            append(
                if (hostInPrefix != null) {
                    "${flavour.wow64DllName} in system32"
                } else {
                    "${flavour.wow64DllName} MISSING from system32"
                },
            )
            if (exited != null) {
                append(" · exit ")
                append(exited.exitCode)
                if (exited.exitCode != WOW64_EXIT_CODE) {
                    append(" (expected ").append(WOW64_EXIT_CODE).append(")")
                }
            }
        }

        // Judged against the code we asked for, not against zero. The program
        // ran either way, which is real progress, but an exit status that does
        // not come back as asked cannot be trusted to report a game crash
        // later, so this stage fails rather than passing on "it ran".
        return result.copy(detail = detail, successExitCode = WOW64_EXIT_CODE)
    }

    /**
     * Reads a Windows program's own header, so the report says what it is
     * rather than what we hope it is.
     *
     * The distinction matters for the 32-bit stage in particular: a `cmd.exe`
     * found under `syswow64` is *probably* 32-bit, and "probably" is not a
     * thing to put in a report about whether the 32-bit path works.
     */
    private fun describeWindowsProgram(file: File): String {
        val header: PeHeader = runCatching {
            file.inputStream().use { InstallationValidator.readPeHeader(it) }
        }.getOrNull() ?: return "${file.name} (its Windows header could not be read)"

        if (!header.isPortableExecutable) {
            return "${file.name} is not a Windows program: ${header.problem.orEmpty()}"
        }
        val bits = when (header.machine) {
            PeMachine.I386 -> "32-bit"
            PeMachine.AMD64 -> "64-bit"
            PeMachine.ARM64 -> "ARM64"
            else -> header.machine?.description ?: "unknown"
        }
        return "${file.name} · Windows $bits · ${header.subsystem?.description ?: "unknown subsystem"}"
    }

    private fun run(
        stage: Stage,
        command: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onLine: (String) -> Unit,
    ): StageResult = StageResult(
        stage = stage,
        command = command,
        outcome = runner.run(
            ProcessRunner.Request(
                command = command,
                environment = environment,
                // Run in the runtime root, never in the game folder: the
                // prefix and every file Wine writes belong to this app.
                workingDirectory = store.root,
                timeoutMs = timeoutMs,
            ),
            onLine = onLine,
        ),
    )

    private companion object {
        const val WINEDLLOVERRIDES = "WINEDLLOVERRIDES"

        const val WOW64_EXIT_CODE = 3

        /** `wine --version` should be instant. Ten seconds is generous. */
        const val LOADER_TIMEOUT_MS = 10_000L

        /** A first prefix build on a mid-range phone takes a minute or two. */
        const val PREFIX_TIMEOUT_MS = 180_000L

        const val REGISTRY_TIMEOUT_MS = 30_000L

        /**
         * The 32-bit check starts Wine, loads a WoW64 layer and runs a program.
         * Slower than `wine --version`, and the first time it runs Wine may
         * still be settling the prefix.
         */
        const val WOW64_TIMEOUT_MS = 120_000L
    }
}
