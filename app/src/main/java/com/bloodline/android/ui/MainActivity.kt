package com.bloodline.android.ui

import android.content.ActivityNotFoundException
import android.graphics.Rect
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.content.FileProvider
import com.bloodline.android.BuildConfig
import com.bloodline.android.R
import com.bloodline.android.databinding.ActivityMainBinding
import com.bloodline.android.databinding.ViewConfigRowBinding
import com.bloodline.android.databinding.ViewStatusRowBinding
import com.bloodline.android.diagnostics.CrashReport
import com.bloodline.android.diagnostics.DiagnosticsManager
import com.bloodline.android.diagnostics.DiagnosticsProvider
import com.bloodline.android.diagnostics.GpuInfo
import com.bloodline.android.diagnostics.ReportBuilder
import com.bloodline.android.diagnostics.ReportInputs
import com.bloodline.android.diagnostics.SessionEnd
import com.bloodline.android.diagnostics.UnfinishedSession
import com.bloodline.android.game.GameInstall
import com.bloodline.android.game.GameInstallRepository
import com.bloodline.android.game.GameInstallScanner
import com.bloodline.android.game.InstallScanReport
import com.bloodline.android.game.LocationKind
import com.bloodline.android.game.ScanProgress
import com.bloodline.android.game.ScanSource
import com.bloodline.android.game.ScanStage
import com.bloodline.android.display.DisplayCapabilities
import com.bloodline.android.display.DisplayInfo
import com.bloodline.android.display.ResolutionCalculator
import com.bloodline.android.display.describe
import com.bloodline.android.runtime.BlockReason
import com.bloodline.android.runtime.ComponentIds
import com.bloodline.android.runtime.GameProcessManager
import com.bloodline.android.runtime.GraphicsProbe
import com.bloodline.android.runtime.InstallValidation
import com.bloodline.android.runtime.InstallationValidator
import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.LaunchReadiness
import com.bloodline.android.runtime.LaunchState
import com.bloodline.android.runtime.ProbeCache
import com.bloodline.android.runtime.NativeProbe
import com.bloodline.android.runtime.ProcessProbe
import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.RuntimeCapabilitiesReader
import com.bloodline.android.runtime.RuntimeComponent
import com.bloodline.android.runtime.RuntimeDiagnostics
import com.bloodline.android.runtime.LastRunStore
import com.bloodline.android.runtime.RuntimeHost
import com.bloodline.android.runtime.WineInitialiser
import com.bloodline.android.runtime.RuntimeManager
import com.bloodline.android.runtime.RuntimeProbe
import com.bloodline.android.runtime.PackagedRuntimeProvider
import com.bloodline.android.runtime.RuntimeProviders
import com.bloodline.android.runtime.RuntimeRegistry
import com.bloodline.android.runtime.packaging.RuntimeAcquisition
import com.bloodline.android.settings.FpsMeter
import com.bloodline.android.settings.OrientationApplier
import com.bloodline.android.settings.OrientationController
import com.bloodline.android.settings.SettingsApplier
import com.bloodline.android.settings.SettingsProvider
import com.bloodline.android.settings.SettingsRepository
import com.bloodline.android.settings.UiScaleApplier
import com.bloodline.android.storage.DocumentFolder
import com.bloodline.android.storage.GameFolderStore
import com.bloodline.android.storage.SharedPreferencesInstallMetadataStore
import com.bloodline.android.storage.StorageLocations
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The launcher screen.
 *
 * Its job is to report, honestly, what the project can and cannot do today:
 * whether the user's own game files were found, and how far each piece of the
 * Windows compatibility runtime has got. The launch button is driven entirely by
 * [LaunchReadiness] and stays disabled until that says otherwise.
 */
class MainActivity : AppCompatActivity(), RuntimeHost.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameFolderStore: GameFolderStore
    private lateinit var settings: SettingsRepository
    private lateinit var installRepository: GameInstallRepository

    /** Measured on this device, never assumed. Read once when the screen opens. */
    private lateinit var display: DisplayInfo

    /**
     * Keeps the launcher's orientation and the game's apart.
     *
     * The launcher phase follows the device; the gameplay phase forces
     * landscape because the game is a landscape Windows title. Nothing calls
     * [enterGameplay] yet — the runtime host will, and this is the seam it will
     * use rather than inventing its own.
     */
    private val orientation = OrientationController { request ->
        OrientationApplier.apply(this, request)
    }

    /** Background thread for one-off work that must never block the UI. */
    private val background: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A launch has its own thread, and not [background].
     *
     * [background] is a single thread shared by the validation, the device
     * probes, the runtime download and report generation. Bringing Wine up runs
     * `wine --version`, `wineboot` and a 32-bit program, whose timeouts alone
     * add up to more than five minutes — so a launch on that thread froze every
     * one of those jobs, and froze the screen with them.
     */
    private val launchExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Guards against two Wine bring-ups racing each other. */
    @Volatile
    private var launching = false

    private lateinit var diagnostics: DiagnosticsManager

    private var install: GameInstall? = null

    /** Live frame-rate overlay, created only when the user turns it on. */
    private var fpsOverlay: TextView? = null
    private val fpsMeter = FpsMeter { framesPerSecond ->
        fpsOverlay?.text = getString(R.string.fps_overlay_prefix, framesPerSecond.toInt())
    }

    /**
     * Result of the bundled native probe, and the status it implies.
     *
     * Loaded from the cache when the device has already been checked, so a
     * start does not pay for work whose answer cannot have changed.
     */
    private var nativeProbeResult: NativeProbe.Result? = null

    /** Measured capabilities of this device, from the runtime probe. */
    private var runtimeProbeResult: RuntimeProbe.Result? = null

    /** Which route to the GPU this device has, measured rather than assumed. */
    private var graphicsProbeResult: GraphicsProbe.Result? = null

    /** Whether a Wine process tree could survive here, and under what limit. */
    private var processProbeResult: ProcessProbe.Result? = null

    /** Everything the runtime knows about this device, assembled from probes. */
    private var capabilities: RuntimeCapabilities = RuntimeCapabilities.unknown()

    /** GL renderer and GLES version, read from an EGL context off the main thread. */
    private var glRenderer: String = ""
    private var glesVersion: String = ""

    /** How the last game run ended, for the diagnostics report. */
    private var lastExit: GameProcessManager.ExitResult? = null

    /** Survives the app being killed, so a later report still has the run. */
    private val lastRunStore by lazy { LastRunStore(File(filesDir, "runtime/last-run.txt")) }

    /**
     * Owns the launch lifecycle.
     *
     * The activity's whole contribution is the four callbacks below; every
     * decision is made inside the runtime, which is what keeps this class a
     * screen rather than a launcher.
     *
     * The runtime is wired to a real provider here, so the Launch button is
     * governed by what is actually installed on this device rather than by
     * what the APK happens to contain.
     */
    /**
     * Everything the runtime has printed since the last launch attempt.
     *
     * RuntimeManager takes an onOutput, and this activity was not passing one,
     * so it defaulted to a no-op and every line Wine wrote went nowhere. That
     * costs nothing while Wine works and everything the moment it does not,
     * which is precisely when the output is the only useful thing there is.
     *
     * StringBuffer for its lock: lines arrive on the background thread the
     * launch runs on, and are read from whatever thread builds a report.
     */
    private val runtimeOutput = StringBuffer()

    private fun recordRuntimeOutput(line: String) {
        // Bounded rather than unbounded: a program that writes without stopping
        // must not be able to fill the heap.
        if (runtimeOutput.length >= RUNTIME_OUTPUT_LIMIT) return
        runtimeOutput.append(line).append('\n')
    }

    /**
     * What the game itself printed, and nothing else.
     *
     * Everything Wine says still goes to [runtimeOutput]. This one is separate
     * because the prefix talks far more than the game does: the first log off
     * a real device was 2055 lines of `wineboot` and not one line from the
     * game, because the buffer had already been spent before the game started.
     */
    private val gameOutput = StringBuffer()

    private fun recordGameOutput(line: String) {
        if (gameOutput.length >= RUNTIME_OUTPUT_LIMIT) return
        gameOutput.append(line).append('\n')
    }

    private val runtimeHost by lazy {
        RuntimeHost(
            host = this,
            manager = RuntimeProviders.manager(
                this,
                onOutput = ::recordRuntimeOutput,
                onGameOutput = ::recordGameOutput,
            ),
        )
    }

    /** What reading the executable's header said, or null when not yet read. */
    private var installValidation: InstallValidation? = null

    /** The last launch attempt in full, or null when the last one did not fail. */
    private var launchFailure: String? = null

    private val probeCache: ProbeCache by lazy { ProbeCache(this) }
    private var nativeHostingStatus: IntegrationStatus = IntegrationStatus.NOT_INTEGRATED

    /** The last device measurement the runtime was given, or unknown. */
    private var runtimeCapabilities: RuntimeCapabilities = RuntimeCapabilities.unknown()

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    /** Copies the latest report wherever the user wants it. */
    private val saveReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveReportTo(uri)
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val extras = result.data?.extras ?: return@registerForActivityResult

        if (extras.getBoolean(SettingsActivity.EXTRA_CLEAR_CACHE, false)) {
            installRepository.clearCache()
        }
        if (extras.getBoolean(SettingsActivity.EXTRA_CLEAR_CACHE, false) ||
            extras.getBoolean(SettingsActivity.EXTRA_RESCAN, false)
        ) {
            ensureInstall(force = true)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // The interface scale has to be applied before any view exists.
        super.attachBaseContext(
            UiScaleApplier.wrap(newBase, SettingsProvider.get(newBase).uiScale),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameFolderStore = GameFolderStore(this)
        settings = SettingsProvider.get(this)
        installRepository = GameInstallRepository(
            roots = { StorageLocations.searchRoots(this, gameFolderStore) },
            metadataStore = SharedPreferencesInstallMetadataStore(this),
            appVersion = BuildConfig.VERSION_NAME,
        )

        display = DisplayCapabilities.detect(this)
        diagnostics = DiagnosticsProvider.get(this, BuildConfig.VERSION_NAME)
        val unfinishedSession = diagnostics.startSession()

        bindStaticContent()
        bindRuntimeRows()
        refreshDiagnosticsCard()

        // Renders the remembered result straight away, and only scans when that
        // result is missing or stale. This is what makes reopening fast.
        ensureInstall()

        val cachedProbe = probeCache.load(PROBE_NATIVE, BuildConfig.VERSION_NAME)
        if (cachedProbe != null) {
            onNativeProbeFinished(
                NativeProbe.Result(cachedProbe.exitCode, cachedProbe.output),
            )
        } else {
            runNativeProbe()
        }

        val cachedRuntime = probeCache.load(PROBE_RUNTIME, BuildConfig.VERSION_NAME)
        if (cachedRuntime != null) {
            onRuntimeProbeFinished(
                RuntimeProbe.Result(cachedRuntime.exitCode, cachedRuntime.output),
            )
        } else {
            runRuntimeProbe()
        }

        val cachedGraphics = probeCache.load(PROBE_GRAPHICS, BuildConfig.VERSION_NAME)
        if (cachedGraphics != null) {
            graphicsProbeResult =
                GraphicsProbe.Result(cachedGraphics.exitCode, cachedGraphics.output)
        } else {
            runGraphicsProbe()
        }

        // The GL strings are read on every start, cached probe or not. See
        // [measureGpuStrings]: reading them only inside the probe meant the
        // second and every later launch believed this device had no GPU.
        measureGpuStrings()

        val cachedProcess = probeCache.load(PROBE_PROCESS, BuildConfig.VERSION_NAME)
        if (cachedProcess != null) {
            processProbeResult =
                ProcessProbe.Result(cachedProcess.exitCode, cachedProcess.output)
        } else {
            runProcessProbe()
        }

        // Whatever came from cache still has to reach the runtime.
        refreshCapabilities()

        binding.importButton.setOnClickListener { folderPicker.launch(null) }
        binding.launchButton.setOnClickListener { onLaunchClicked() }
        // The hint is the only thing on screen that knows why the button above
        // it is off, so it opens the full reason on a tap.
        binding.launchHint.setOnClickListener { showLaunchBlockDetail() }
        binding.runtimeInstallButton.setOnClickListener { offerRuntimeInstall() }
        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.diagnosticsGenerateButton.setOnClickListener {
            generateReport(session = null, classification = SessionEnd.UNKNOWN)
        }
        binding.diagnosticsSaveButton.setOnClickListener {
            val report = diagnostics.latestReport()
            if (report == null) {
                showMessage(getString(R.string.diagnostics_nothing_to_share))
            } else {
                saveReportLauncher.launch(report.name)
            }
        }
        binding.diagnosticsShareButton.setOnClickListener { shareLatestReport() }
        binding.diagnosticsCopyButton.setOnClickListener { copyLatestReport() }
        binding.diagnosticsDeleteButton.setOnClickListener { deleteLatestReport() }

        // The previous run never said goodbye, so record what it left behind.
        if (unfinishedSession != null) {
            generateReport(session = unfinishedSession, classification = unfinishedSession.classification)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-applied here as well as in onCreate so a change made in the
        // settings screen shows up the moment the user comes back.
        SettingsApplier.apply(this, settings)
        orientation.applyLauncher()
        updateFpsOverlay()
        diagnostics.noteForegrounded()

        // Deliberately no rescan here: the cached result is still valid, and
        // re-walking the game folder on every resume is what made reopening the
        // app slow. Use "Re-scan for game files" in Settings when needed.
    }

    override fun onPause() {
        super.onPause()
        fpsMeter.stop()
    }

    override fun onStop() {
        diagnostics.noteBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        // Only a deliberate close counts as clean. A crash never gets here, and
        // neither does Android reclaiming the process, which is exactly how the
        // next run knows something went wrong.
        if (isFinishing) {
            diagnostics.closeCleanly()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ orientation

    // -------------------------------------------------- RuntimeHost.Host seams
    //
    // These four are the entire coupling between the launcher and the runtime.
    // The activity contributes the screen and nothing else: which backend is
    // used, whether a launch is allowed and how a run ended are all decided
    // inside RuntimeHost.

    /**
     * Hands the screen to the game.
     *
     * Landscape here is a requirement of the game, not a stored preference, so
     * it overrides whatever the launcher was doing and nothing in Settings can
     * weaken it. Called by the runtime host only once a game process exists,
     * and always paired with [exitGameplay].
     */
    override fun enterGameplay() = runOnUiThread { orientation.enterGameplay() }

    /**
     * Takes the screen back after the game closes and restores whatever
     * orientation the launcher was using.
     */
    override fun exitGameplay() = runOnUiThread { orientation.exitGameplay() }

    /**
     * Every callback below hops to the UI thread, because none of them is
     * called from it any more.
     *
     * Bringing Wine up runs `wineboot`, which takes a minute or two on a
     * mid-range phone and has to happen off the main thread. That moves the
     * launch off it too, and with it every callback the runtime makes.
     */
    override fun onPhaseChanged(phase: RuntimeManager.Phase) = runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        binding.launchHint.text = if (phase is RuntimeManager.Phase.InGame) {
            // Said plainly rather than as "in game": there is no surface in
            // this build, so a process that reached this phase is running with
            // nowhere to draw, and the screen is not going to change.
            getString(R.string.launch_in_game_no_surface)
        } else {
            RuntimeDiagnostics.describePhase(phase)
        }
    }

    override fun onLaunchProgress(message: String) = runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        binding.launchProgress.isVisible = true
        binding.launchProgressText.text = message
    }

    override fun onWineWarning(failed: WineInitialiser.StageResult) = runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        binding.launchProgress.isVisible = true
        binding.launchProgressText.text =
            getString(R.string.wine_32bit_check_warning, failed.describe())
    }

    override fun onFinished(message: String, exit: GameProcessManager.ExitResult?) = runOnUiThread {
        // A game that crashed is a failure that arrives here, not through
        // onError, and a toast is one line. Without this the one outcome we
        // most need explained — the game itself dying — is the one with no
        // evidence attached to it. A clean exit is not a failure and gets
        // nothing.
        if (exit != null) {
            lastExit = exit
            // Written straight away, because the record has to outlive this
            // process. A crash is investigated after the fact, and often after
            // the app itself has been killed — which is precisely when an
            // in-memory record is gone and a report generated on request can
            // say nothing but "the previous session did not finish".
            lastRunStore.save(exit)
        }
        if (exit != null && exit.reason != GameProcessManager.ExitReason.CLEAN &&
            exit.reason != GameProcessManager.ExitReason.STOPPED
        ) {
            launchFailure = describeLaunchFailure(message, gameRan = true)
        }
        showMessage(message)
        updateLaunchState()
    }

    override fun onError(message: String) = runOnUiThread {
        // Kept, not just shown: a toast is one line and it disappears. What
        // failed is Wine, and its own output is the only evidence for why.
        launchFailure = describeLaunchFailure(message)
        showMessage(message)
        updateLaunchState()
    }

    /**
     * The last launch attempt, in more than a toast's worth of words.
     *
     * Everything the run printed is already kept for the diagnostics report,
     * so it is put on screen here rather than making someone generate a ZIP
     * to discover that a Windows program segfaulted.
     */
    private fun describeLaunchFailure(message: String, gameRan: Boolean = false): String = buildString {
        // Which build produced this, and what Wine was asked to print.
        // Without these two a pasted failure cannot be told apart from an
        // older build's, and that ambiguity already cost a round: the same
        // one-line message came from a Wine that had been told to stay
        // silent and from one that had not, with no way to know which.
        val debug = runtimeHost.plannedEnvironment()["WINEDEBUG"]
        appendLine(
            getString(
                R.string.launch_failure_build,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                debug ?: "unset",
            ),
        )
        appendLine()
        appendLine(message)

        val report = runtimeHost.initialisation()
        if (report != null) {
            appendLine()
            appendLine(report.describe())
        }

        // Whose output this is matters more than having output. When the game
        // itself ran, the tail is the game's, and that is the only thing that
        // answers the question. When it never started, the tail belongs to the
        // stage that stopped it — and putting those under one heading is how
        // a probe's crash gets mistaken for the game's.
        if (gameRan) {
            // The game's own buffer first. It is the only place the game's
            // words can survive wineboot; falling back to everything is right
            // only when the game said nothing at all.
            val printed = gameOutput.toString().trim().ifBlank {
                runtimeOutput.toString().trim()
            }
            if (printed.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.launch_failure_game_output_heading))
                append(printed.split('\n').takeLast(LAST_OUTPUT_LINES).joinToString("\n"))
            }
        } else {
            val failed = report?.failedAt
            val output = failed?.let { report.outputOf(it.stage) }.orEmpty()
            if (output.isNotEmpty()) {
                appendLine()
                appendLine(
                    getString(
                        R.string.launch_failure_output_heading,
                        failed?.stage?.title.orEmpty(),
                    ),
                )
                append(output.takeLast(LAST_OUTPUT_LINES).joinToString("\n"))
            }
        }
    }.trimEnd()

    // ---------------------------------------------------------------- content

    private fun bindStaticContent() {
        bindDisplayPanel()
        bindAudioPanel()
        bindInstallPanel()
        bindRuntimeCapabilityRow()
        binding.footerVersion.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
    }

    /**
     * The display panel.
     *
     * The first row is measured from the device; the other two are computed
     * from the stored resolution and scaling choices. The old version of this
     * card printed a hard-coded "1280 × 720", which read like a setting and was
     * nothing of the kind.
     */
    private fun bindDisplayPanel() {
        val plan = ResolutionCalculator.plan(
            display = display,
            resolution = settings.displayResolution,
            custom = settings.customDisplaySize(),
            scalePercent = settings.effectiveScalePercent(),
        )

        bindConfigRow(
            binding.displayPanel,
            R.string.display_panel_label,
            display.describe().ifEmpty { getString(R.string.display_unmeasured) },
        )
        bindConfigRow(
            binding.displayGameResolution,
            R.string.display_game_resolution_label,
            "${plan.presented}",
        )
        bindConfigRow(
            binding.displayRenderResolution,
            R.string.display_render_resolution_label,
            getString(R.string.value_scaled, plan.scalePercent, "${plan.rendered}"),
        )

        binding.displayNote.text = if (plan.reducedToFit) {
            getString(R.string.display_reduced_suffix, "${plan.presented}") + "\n" +
                getString(R.string.display_panel_note)
        } else {
            getString(R.string.display_panel_note)
        }
    }

    /** Volume is real today; the backend is only stored for the runtime. */
    private fun bindAudioPanel() {
        bindConfigRow(
            binding.audioVolume,
            R.string.audio_volume_label,
            if (settings.masterVolume >= 0) {
                "${settings.masterVolume}%"
            } else {
                getString(R.string.audio_volume_unset)
            },
        )
        bindConfigRow(
            binding.audioBackend,
            R.string.audio_backend_label,
            settings.audioBackend.label,
        )
    }

    private fun bindInstallPanel() {
        bindConfigRow(
            binding.installExpected,
            R.string.install_expected_label,
            R.string.install_expected_value,
        )
        val rootCount = StorageLocations.searchRoots(this, gameFolderStore).size
        bindConfigRow(
            binding.installSearchPath,
            R.string.install_search_path_label,
            resources.getQuantityString(R.plurals.install_search_path_value, rootCount, rootCount),
        )
    }

    private fun bindRuntimeCapabilityRow() {
        bindConfigRow(
            binding.runtimeCapability,
            R.string.runtime_capability_label,
            getString(R.string.runtime_capability_pending),
        )
        binding.runtimeCapability.root.setOnClickListener { showRuntimeProbeOutput() }
    }

    /** Components with the live, device measured status folded in. */
    private fun runtimeComponents() = RuntimeRegistry.forProvider(
        provider = runtimeProvider,
        capabilities = runtimeCapabilities,
        nativeHosting = nativeHostingStatus,
        // Detection itself is implemented and works; what is missing is the
        // game, and that is the user's to provide.
        storage = if (install != null && install!!.isComplete) {
            IntegrationStatus.READY
        } else {
            IntegrationStatus.NOT_INTEGRATED
        },
    )

    private fun bindRuntimeRows() {
        val all = runtimeComponents()
        val components = all.associateBy { it.id }

        val overall = RuntimeRegistry.overallOf(all)
        bindStatusRow(
            binding.runtimeOverall,
            RuntimeComponent(
                id = "overall",
                title = getString(R.string.runtime_overall_label),
                // Derived from the status this row is about to draw, so the
                // two cannot disagree. It used to be one fixed string saying
                // the runtime was not integrated, which it kept saying after
                // Wine had been downloaded and had booted a prefix.
                detail = getString(overall.detailRes()),
                status = overall,
            ),
        )
        bindNativeRow(component(components, ComponentIds.NATIVE_HOSTING))
        bindStatusRow(binding.runtimeWine, component(components, ComponentIds.WINE))
        bindStatusRow(binding.runtimeCpu, component(components, ComponentIds.CPU_TRANSLATION))
        bindStatusRow(binding.runtimeContainer, component(components, ComponentIds.CONTAINER))
        bindStatusRow(binding.runtimeGraphics, component(components, ComponentIds.GRAPHICS))
        bindStatusRow(binding.runtimeAudio, component(components, ComponentIds.AUDIO))
        bindStatusRow(binding.runtimeStorage, component(components, ComponentIds.STORAGE))
    }

    private fun component(
        registry: Map<String, RuntimeComponent>,
        id: String,
    ): RuntimeComponent = requireNotNull(registry[id]) { "Missing runtime component: $id" }

    private fun bindStatusRow(row: ViewStatusRowBinding, component: RuntimeComponent) {
        row.rowTitle.text = component.title
        row.rowDetail.text = component.detail
        applyStatus(row, component.status)
    }

    private fun applyStatus(row: ViewStatusRowBinding, status: IntegrationStatus) {
        val color = ContextCompat.getColor(this, status.colorRes())
        val label = getString(status.labelRes())

        ViewCompat.setBackgroundTintList(row.statusDot, ColorStateList.valueOf(color))

        row.statusChip.text = label
        row.statusChip.setTextColor(color)
        // Translucent fill so the chip reads as a tinted pill, not a solid block.
        ViewCompat.setBackgroundTintList(
            row.statusChip,
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 45)),
        )
    }

    private fun bindConfigRow(row: ViewConfigRowBinding, labelRes: Int, valueRes: Int) {
        row.configLabel.text = getString(labelRes)
        row.configValue.text = getString(valueRes)
    }

    private fun bindConfigRow(row: ViewConfigRowBinding, labelRes: Int, value: String) {
        row.configLabel.text = getString(labelRes)
        row.configValue.text = value
    }

    private fun IntegrationStatus.colorRes(): Int = when (this) {
        IntegrationStatus.READY -> R.color.status_ready
        IntegrationStatus.PARTIAL -> R.color.status_partial
        IntegrationStatus.NOT_INTEGRATED -> R.color.status_planned
    }

    private fun IntegrationStatus.labelRes(): Int = when (this) {
        IntegrationStatus.READY -> R.string.status_ready
        IntegrationStatus.PARTIAL -> R.string.status_partial
        IntegrationStatus.NOT_INTEGRATED -> R.string.status_not_integrated
    }

    /**
     * What the aggregate runtime status means, in a sentence.
     *
     * Kept next to [IntegrationStatus.labelRes] for the same reason: the
     * launcher used to print one fixed line about shipping as a UI foundation
     * while the card underneath it said Wine was installed and the device had
     * been measured.
     */
    private fun IntegrationStatus.detailRes(): Int = when (this) {
        IntegrationStatus.READY -> R.string.runtime_overall_detail_ready
        IntegrationStatus.PARTIAL -> R.string.runtime_overall_detail_partial
        IntegrationStatus.NOT_INTEGRATED -> R.string.runtime_overall_detail_none
    }

    // ------------------------------------------------------------ game status

    /**
     * Makes sure the launcher has an up to date view of the game files.
     *
     * When the last verified scan is still fresh this costs nothing at all: the
     * remembered result is rendered immediately. Otherwise the scan runs on a
     * background thread and reports real progress, because walking a game
     * folder over the Storage Access Framework costs thousands of binder calls
     * and used to freeze the launcher completely.
     */
    private fun ensureInstall(force: Boolean = false) {
        installRepository.ensureInstall(
            force = force,
            onProgress = { progress -> runOnUiThread { onScanProgress(progress) } },
            onResult = { report -> runOnUiThread { onScanFinished(report) } },
        )
        setScanningUi(installRepository.isScanning)
    }

    private fun setScanningUi(active: Boolean) {
        binding.importButton.isEnabled = !active
        binding.importProgress.isVisible = active
        if (active) {
            binding.importProgressText.text = getString(R.string.scan_stage_scanning)
        }
    }

    /**
     * Reads the executable's PE header to confirm the folder holds the real
     * 32-bit Windows game.
     *
     * Why this exists at all: [GameInstallScanner] matches on the *name*
     * `Vampire.exe`, which cannot tell the game from a 64-bit build, a Linux
     * binary, or a truncated download. Wine would refuse all three, so the
     * launcher says so before the user presses Launch rather than after.
     *
     * Read-only, and only ever read-only — the stream comes from
     * [StorageEntry.stream], so nothing here can write to the user's game
     * folder.
     */
    private fun validateInstall(found: GameInstall?) {
        if (found == null) {
            installValidation = null
            runtimeHost.setValidation(null)
            updateLaunchState()
            return
        }

        // Opening a SAF tree and reading a few KB is real I/O, so it goes to the
        // background thread the scan already uses.
        background.execute {
            val result = runCatching {
                val folder = StorageLocations.openFolder(this, found.location)
                    ?: return@runCatching InstallValidation.Invalid(
                        path = found.location.displayPath,
                        reason = "That folder can no longer be opened. The permission " +
                            "may have been revoked, or the card removed.",
                    )

                // Listed once: over the Storage Access Framework every entry is
                // a binder call, and the old code listed the folder twice to
                // build the name map and then find the executable again.
                val listed = folder.entries()
                val entries = listed.associate { it.name to it.isDirectory }

                // Matched without case, for the same reason the validator does
                // the same: the scanner found this folder with ignoreCase, and
                // an exact-match lookup here lost the file again on any copy
                // that arrived as VAMPIRE.EXE.
                val executable = listed.firstOrNull {
                    it.name.equals(InstallationValidator.EXECUTABLE_NAME, ignoreCase = true) &&
                        !it.isDirectory
                }
                val stream = executable?.stream()
                    ?: return@runCatching InstallValidation.Invalid(
                        path = found.location.displayPath,
                        reason = "Vampire.exe could not be opened for reading. Android " +
                            "refused access to the file — use Import Game Files to renew " +
                            "the permission for that folder.",
                        detail = InstallationValidator.EXECUTABLE_NAME,
                    )

                InstallationValidator.validate(
                    path = found.location.displayPath,
                    entries = entries,
                    openExecutable = { stream },
                )
            }.getOrElse { error ->
                InstallValidation.Invalid(
                    path = found.location.displayPath,
                    reason = "The executable could not be read (${error.message}).",
                )
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                installValidation = result
                runtimeHost.setValidation(result)
                applyValidationToInstallCard()
                updateLaunchState()
            }
        }
    }

    /**
     * Folds the validation result into the installation card.
     *
     * Without this the card and the launch button describe the same folder in
     * two voices that disagree: the card is drawn from the scan, which only
     * matches names, so it says "game files detected" in green for a folder the
     * validator has just refused. The refusal is the more recent and more
     * specific finding, so it wins.
     */
    private fun applyValidationToInstallCard() {
        val invalid = installValidation as? InstallValidation.Invalid ?: return
        binding.installStatus.rowDetail.text = invalid.reason
        applyStatus(binding.installStatus, IntegrationStatus.PARTIAL)
    }

    /**
     * Opens GPU device nodes and creates an EGL context, so it stays off the
     * main thread - an EGL context belongs to the thread that made it.
     */
    /**
     * Reads the GL renderer and GLES version, and feeds them to the runtime.
     *
     * Done on every start rather than only when the graphics probe has to
     * run, which is what used to happen: the strings were read inside
     * [runGraphicsProbe], so the *first* launch — the one with an empty cache
     * — measured the GPU, and every launch after it read a cached probe and
     * saw empty strings.
     *
     * The effect was that from the second start onwards the device had no
     * GPU as far as the runtime was concerned: GLES 3.1 was reported as
     * unsupported, the renderer the user had chosen was rejected for a
     * requirement the device actually met, and the launcher fell back to
     * software rendering — which is what a report from a real device showed,
     * with "GPU renderer: unknown" sitting two lines above a device section
     * that named a Mali-G52 and OpenGL ES 3.2.
     *
     * Off the main thread because an EGL context belongs to the thread that
     * created it, and creating one is not free.
     */
    private fun measureGpuStrings() {
        background.execute {
            val gpu = runCatching { GpuInfo.probe() }.getOrNull()
            val renderer = gpu?.renderer.orEmpty()
            val version = gpu?.glVersion.orEmpty()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                glRenderer = renderer
                glesVersion = version
                refreshCapabilities()
            }
        }
    }

    private fun runGraphicsProbe() {
        background.execute {
            val result = GraphicsProbe.run(this)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                probeCache.save(
                    PROBE_GRAPHICS,
                    BuildConfig.VERSION_NAME,
                    ProbeCache.Snapshot(result.exitCode, result.output),
                )
                graphicsProbeResult = result
                refreshCapabilities()
            }
        }
    }

    /** Forks a small process tree, so it stays off the main thread. */
    private fun runProcessProbe() {
        background.execute {
            val result = ProcessProbe.run(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                probeCache.save(
                    PROBE_PROCESS,
                    BuildConfig.VERSION_NAME,
                    ProbeCache.Snapshot(result.exitCode, result.output),
                )
                processProbeResult = result
                refreshCapabilities()
            }
        }
    }

    /**
     * Folds whatever the probes have said into one set of device facts.
     *
     * Called after each probe reports rather than once at the end: a probe that
     * fails or is slow must not stop the others from being used. The reading
     * itself lives in [RuntimeCapabilitiesReader], so this class never has to
     * know what a `personality32` line means.
     */
    private fun refreshCapabilities() {
        capabilities = RuntimeCapabilitiesReader.assemble(
            runtimeOutput = runtimeProbeResult?.output.orEmpty(),
            graphicsOutput = graphicsProbeResult?.output.orEmpty(),
            processOutput = processProbeResult?.output.orEmpty(),
            gpu = GpuInfo.Info(
                vendor = "",
                renderer = glRenderer,
                glVersion = glesVersion,
                shadingLanguageVersion = "",
                eglVersion = "",
            ),
        )
        runtimeHost.prepare(capabilities)
        runtimeCapabilities = capabilities
        showRuntimeSelection()
        // The runtime rows and the launch gate both depend on what is
        // installed, so they are re-evaluated once the device is measured.
        bindRuntimeRows()
        updateLaunchState()
    }

    /**
     * Shows which backend the runtime would pick, and why.
     *
     * Worth showing even though nothing can launch: "which route would this
     * device take?" is a device question with a real answer today, and the
     * answer is what decides whether the Vulkan route is ever worth trying.
     */
    private fun showRuntimeSelection() {
        val selection = runtimeHost.selection()
        val backend = selection.graphics.backend ?: return

        binding.runtimeGraphics.rowDetail.text = buildString {
            append(getString(R.string.runtime_selection_prefix))
            append(backend.title)
            if (selection.graphics.wasFallback) append(getString(R.string.runtime_selection_fallback))
            append(". ")
            append(selection.graphics.reason)
            append(" ")
            append(getString(R.string.runtime_selection_translator, selection.translator.title))
            append(" ")
            append(getString(R.string.runtime_selection_audio, selection.audio.title))
        }
    }

    private fun onScanProgress(progress: ScanProgress) {
        binding.importProgress.isVisible = true
        binding.importProgressText.text = buildString {
            appendLine(stageLabel(progress.stage))
            append(
                getString(
                    R.string.scan_counts,
                    progress.entriesExamined,
                    progress.foldersExamined,
                )
            )
        }
    }

    private fun stageLabel(stage: ScanStage): String = getString(
        when (stage) {
            ScanStage.SCANNING_FOLDERS -> R.string.scan_stage_scanning
            ScanStage.CHECKING_REQUIRED_FILES -> R.string.scan_stage_checking
            ScanStage.FINISHED -> R.string.scan_stage_finishing
        }
    )

    private fun onScanFinished(report: InstallScanReport) {
        val found = report.install
        install = found

        // Reading the executable's header is cheap and it turns "a file called
        // Vampire.exe exists" into "the 32-bit Windows executable is here".
        validateInstall(found)

        val status = when {
            found == null -> IntegrationStatus.NOT_INTEGRATED
            found.isComplete -> IntegrationStatus.READY
            else -> IntegrationStatus.PARTIAL
        }

        binding.installStatus.rowTitle.text = getString(
            when {
                found == null -> R.string.install_not_found
                found.isComplete -> R.string.install_found
                else -> R.string.install_incomplete
            }
        )
        binding.installStatus.rowDetail.text = when {
            report.error != null -> getString(
                R.string.scan_failed,
                report.error.message ?: report.error.javaClass.simpleName,
            )

            found == null -> getString(R.string.install_detail_not_found)
            !found.isComplete -> getString(R.string.install_detail_incomplete)
            found.location.kind == LocationKind.EXTERNAL_FOLDER ->
                getString(R.string.install_detail_found_external)

            else -> getString(R.string.install_detail_found)
        }
        applyStatus(binding.installStatus, status)

        // Only show the resolved location; when nothing was found the detail
        // line above already explains what to do.
        val location = found?.location?.displayPath
        binding.installNote.isVisible = location != null
        if (location != null) {
            binding.installNote.text = location
        }

        // Say where the result came from, so a cached answer is never mistaken
        // for a scan that just happened.
        val fromCache = report.source == ScanSource.CACHE && report.scannedAtMillis > 0
        binding.installVerified.isVisible = fromCache
        if (fromCache) {
            binding.installVerified.text = getString(
                R.string.install_verified_at,
                formatTimestamp(report.scannedAtMillis),
                report.entriesExamined,
            )
        }

        binding.importButton.text = getString(
            if (gameFolderStore.load() != null) {
                R.string.import_change_folder
            } else {
                R.string.import_game_files
            }
        )

        setScanningUi(false)

        // Preparing the runtime is real work that may still be in flight: the
        // bundled native probe has to report back before anything can launch.
        if (report.source == ScanSource.FRESH && nativeProbeResult == null) {
            binding.importProgress.isVisible = true
            binding.importProgressText.text = getString(R.string.scan_stage_preparing)
        }

        updateLaunchState()
    }

    private fun formatTimestamp(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    /** Whether the runtime packages can be downloaded on this device at all. */
    private fun runtimeIsAcquirable(): Boolean = RuntimeProviders.deviceAbi().startsWith("arm64")

    private fun updateLaunchState() {
        val state = launchState()

        // Enabled for NeedsRuntime as well as Ready. Disabled there, it was
        // impossible to reach the one thing that downloads the runtime, so the
        // packages could never be acquired on any device.
        binding.launchButton.isEnabled = state is LaunchState.Ready ||
            state is LaunchState.NeedsRuntime
        binding.launchHint.text = when (state) {
            LaunchState.Ready -> getString(R.string.launch_ready)
            LaunchState.NeedsRuntime -> getString(R.string.launch_needs_runtime)
            is LaunchState.Blocked -> blockedHint(state.reason)
        }
        binding.launchButton.text = getString(
            if (state is LaunchState.NeedsRuntime) R.string.launch_install_runtime
            else R.string.launch_game,
        )
        updateRuntimeInstallButton()
    }

    /**
     * A route to the download that the launch gate cannot close.
     *
     * The Launch button is disabled while the game files are missing, so on a
     * fresh install the only thing that fetched the runtime was a button the
     * user could not press. The runtime has nothing to do with the game files,
     * so it gets its own button in its own card instead of inheriting a gate
     * that belongs to something else.
     */
    private fun updateRuntimeInstallButton() {
        val missing = runtimeProvider.missing()
        binding.runtimeInstallButton.isVisible = missing.isNotEmpty()
        if (missing.isEmpty()) return

        val acquirable = runtimeIsAcquirable()
        binding.runtimeInstallButton.isEnabled = acquirable && !runtimeInstalling
        binding.runtimeInstallButton.text = when {
            runtimeInstalling -> getString(R.string.runtime_install_starting)
            acquirable -> getString(
                R.string.runtime_install_action,
                formatSize(RuntimeProviders.acquisition(this).totalBytes()),
            )
            // Nothing to offer: every package is built for arm64. The launch
            // hint already carries the reason.
            else -> getString(R.string.runtime_install_unavailable)
        }
    }

    private fun launchState(): LaunchState = LaunchReadiness.evaluate(
        install = install,
        components = runtimeComponents(),
        validation = installValidation,
        runtimeAcquirable = runtimeIsAcquirable(),
    )

    /**
     * The one line under the Launch button.
     *
     * For a rejected installation this is the validator's own words rather than
     * a fixed string. The fixed one said "has not been verified yet" for two
     * completely different situations — the check had not run, and the check
     * had run and refused the file — and only the second is something the user
     * can act on. A button that is switched off with a reason nobody can read
     * is indistinguishable from a broken one.
     */
    private fun blockedHint(reason: BlockReason): String = when (reason) {
        BlockReason.INSTALL_NOT_VALIDATED -> when (val result = installValidation) {
            is InstallValidation.Invalid -> result.reason
            else -> getString(R.string.launch_locked_not_validated)
        }
        else -> getString(reason.messageRes())
    }

    /**
     * The full reason the Launch button is off, for the hint to open on a tap.
     *
     * The hint is one line of small text; a validation failure also carries the
     * folder that was checked and what its header actually said, and none of
     * that fits. Tapping the hint opens all of it, and offers the one action
     * that can change the answer — re-checking the folder — because a refusal
     * caused by a revoked permission or an unmounted card is not permanent.
     */
    private fun showLaunchBlockDetail() {
        val invalid = installValidation as? InstallValidation.Invalid
        val failure = launchFailure
        // Nothing to expand: the button works and nothing has failed yet, so
        // the one line already on screen is the whole story.
        if (launchState() !is LaunchState.Blocked && invalid == null && failure == null) return

        val message = buildString {
            appendLine(binding.launchHint.text)
            if (invalid != null) {
                if (invalid.detail.isNotBlank()) {
                    appendLine()
                    appendLine(invalid.detail)
                }
                appendLine()
                append(getString(R.string.launch_blocked_checked_folder, invalid.path))
            }
            if (failure != null) {
                appendLine()
                appendLine(getString(R.string.launch_failure_heading))
                append(failure)
            }
        }.trimEnd()

        AlertDialog.Builder(this)
            .setTitle(R.string.launch_blocked_title)
            .setMessage(message)
            .setNeutralButton(R.string.launch_blocked_recheck) { _, _ ->
                ensureInstall(force = true)
            }
            .setPositiveButton(R.string.import_dialog_positive, null)
            .show()
    }

    private fun BlockReason.messageRes(): Int = when (this) {
        BlockReason.NO_GAME_FILES -> R.string.launch_locked_no_install
        BlockReason.INCOMPLETE_GAME_FILES -> R.string.launch_locked_incomplete
        BlockReason.RUNTIME_NOT_INTEGRATED -> R.string.launch_locked_no_runtime
        BlockReason.INSTALL_NOT_VALIDATED -> R.string.launch_locked_not_validated
    }

    // ---------------------------------------------------------------- actions

    /**
     * Links the folder the user picked. Nothing is copied: Android grants
     * persistent read access to that tree and the launcher reads the game where
     * it already is, which saves several gigabytes of duplicated data.
     */
    private fun onFolderPicked(uri: Uri) {
        // One import at a time: a second scan of the same tree would double the
        // cost and can only disagree with the first.
        if (installRepository.isScanning) {
            showMessage(getString(R.string.import_already_running))
            return
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        val accessGranted = runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        if (!accessGranted) {
            showMessage(getString(R.string.import_access_failed))
            return
        }

        val folder = runCatching { DocumentFolder.fromTree(this, uri) }.getOrNull()
        if (folder == null) {
            releaseFolderPermission(uri, flags)
            showInvalidFolderDialog()
            return
        }

        // Checking the picked folder is the same expensive walk as a full scan,
        // so it happens off the main thread too. Doing it inline is exactly what
        // used to freeze the launcher on a black screen.
        setScanningUi(true)
        background.execute {
            val found = runCatching {
                GameInstallScanner(listOf(folder)).findInstall { progress ->
                    runOnUiThread { onScanProgress(progress) }
                }
            }.getOrNull()
            runOnUiThread { onPickedFolderChecked(uri, found, flags) }
        }
    }

    private fun onPickedFolderChecked(uri: Uri, found: GameInstall?, flags: Int) {
        if (found == null) {
            // Nothing usable in there: forget the dead end rather than storing
            // a folder that does not contain the game.
            releaseFolderPermission(uri, flags)
            setScanningUi(false)
            showInvalidFolderDialog()
            return
        }

        gameFolderStore.save(uri)
        // The search roots changed, so the remembered result no longer applies.
        ensureInstall(force = true)
        showMessage(getString(R.string.import_linked, found.location.displayPath))
    }

    private fun releaseFolderPermission(uri: Uri, flags: Int) {
        runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun showInvalidFolderDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_invalid_title)
            .setMessage(R.string.import_invalid_message)
            .setPositiveButton(R.string.import_dialog_positive, null)
            .show()
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun bindNativeRow(component: RuntimeComponent) {
        val result = nativeProbeResult

        val detail = when {
            result == null -> getString(R.string.native_probe_running)
            result.isSuccessful -> buildString {
                val summary = NativeProbe.summary(result.output)
                if (summary.isNotEmpty()) {
                    append(summary)
                    append(" · ")
                }
                append("a bundled native binary ran from the APK library folder")
            }
            else -> getString(R.string.native_probe_failed, result.exitCode)
        }

        bindStatusRow(binding.runtimeNative, component.copy(detail = detail))
        binding.runtimeNative.root.setOnClickListener { showNativeProbeOutput() }
    }

    /** The probe forks a child process, so keep it off the main thread. */
    private fun runNativeProbe() {
        Thread {
            val result = NativeProbe.run(this, cacheDir)
            runOnUiThread {
                probeCache.save(
                    PROBE_NATIVE,
                    BuildConfig.VERSION_NAME,
                    ProbeCache.Snapshot(result.exitCode, result.output),
                )
                onNativeProbeFinished(result)
            }
        }.start()
    }

    /** Forks children and pokes at seccomp, so it stays off the main thread. */
    private fun runRuntimeProbe() {
        background.execute {
            val result = RuntimeProbe.run(this)
            runOnUiThread {
                probeCache.save(
                    PROBE_RUNTIME,
                    BuildConfig.VERSION_NAME,
                    ProbeCache.Snapshot(result.exitCode, result.output),
                )
                onRuntimeProbeFinished(result)
            }
        }
    }

    private fun onRuntimeProbeFinished(result: RuntimeProbe.Result) {
        if (isFinishing || isDestroyed) return

        runtimeProbeResult = result

        binding.runtimeCapability.configValue.text = if (result.isSuccessful) {
            RuntimeProbe.summary(result.output)
        } else {
            getString(R.string.runtime_capability_failed, result.exitCode)
        }
    }

    private fun showRuntimeProbeOutput() {
        val result = runtimeProbeResult
        val message = if (result == null) {
            getString(R.string.runtime_capability_pending)
        } else {
            buildString {
                appendLine("exit code: ${result.exitCode}")
                appendLine()
                append(result.output.trimEnd())
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_capability_label)
            .setMessage(message)
            .setPositiveButton(R.string.import_dialog_positive, null)
            .show()
    }

    private fun onNativeProbeFinished(result: NativeProbe.Result) {
        if (isFinishing || isDestroyed) return

        nativeProbeResult = result
        nativeHostingStatus = if (result.isSuccessful) {
            IntegrationStatus.READY
        } else {
            IntegrationStatus.NOT_INTEGRATED
        }

        bindRuntimeRows()
        updateLaunchState()

        // The runtime is ready to report on, so the preparing stage is over.
        if (!installRepository.isScanning) {
            binding.importProgress.isVisible = false
        }
    }

    private fun showNativeProbeOutput() {
        val result = nativeProbeResult
        val message = if (result == null) {
            getString(R.string.native_probe_running)
        } else {
            buildString {
                appendLine("exit code: ${result.exitCode}")
                appendLine()
                append(result.output.trimEnd())
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.native_probe_title)
            .setMessage(message)
            .setPositiveButton(R.string.import_dialog_positive, null)
            .show()
    }

    private fun onLaunchClicked() {
        // Defensive: the button is disabled unless LaunchReadiness reports
        // Ready, so a click while blocked can only come from an unexpected
        // state. Tell the user why instead of failing silently.
        when (val state = launchState()) {
            // The runtime is acquired rather than bundled, so the first launch
            // has to fetch it. That is a large download, so it is offered as a
            // decision rather than started on a tap.
            LaunchState.NeedsRuntime -> {
                offerRuntimeInstall()
                return
            }

            LaunchState.Ready -> Unit

            is LaunchState.Blocked -> {
                Toast.makeText(this, binding.launchHint.text, Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (runtimeProvider.missing().isNotEmpty()) {
            if (runtimeInstalling) {
                showMessage(getString(R.string.runtime_install_already_running))
            } else {
                offerRuntimeInstall()
            }
            return
        }

        // Everything beyond this point is the runtime's, not the activity's:
        // validate, prepare, initialise, hand the screen over, and give it back
        // when the game ends. The Launch button stays disabled until the path
        // above genuinely works.
        //
        // Off the main thread, and it has to be: this now runs `wine --version`,
        // `wineboot` and a 32-bit program, each of which can take a minute or
        // more on a phone. Calling it from a click handler would freeze the
        // launcher for as long as Wine takes to come up.
        if (launching) {
            showMessage(getString(R.string.launch_already_running))
            return
        }
        launching = true

        binding.launchButton.isEnabled = false
        // Shown before the work starts, not when it finishes: the whole point
        // is that this can take minutes and the screen used to say nothing.
        binding.launchProgress.isVisible = true
        binding.launchProgressText.text = getString(R.string.launch_progress_starting)

        // Each attempt gets a clean log: what Wine said last time is not
        // evidence about what it does this time.
        launchFailure = null
        runtimeOutput.setLength(0)
        gameOutput.setLength(0)
        launchExecutor.execute {
            val phase = try {
                runtimeHost.launch()
            } finally {
                launching = false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.launchProgress.isVisible = false
                if (phase !is RuntimeManager.Phase.InGame) {
                    binding.launchButton.isEnabled = true
                }
                updateLaunchState()
            }
        }
    }

    // ------------------------------------------------- acquiring the runtime

    /** Where the runtime's binaries come from, and whether they are present. */
    private val runtimeProvider: PackagedRuntimeProvider by lazy { RuntimeProviders.packaged(this) }

    /** Guards against two downloads racing each other. */
    @Volatile
    private var runtimeInstalling = false

    private fun offerRuntimeInstall() {
        val bytes = RuntimeProviders.acquisition(this).totalBytes()
        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_install_title)
            .setMessage(getString(R.string.runtime_install_message, formatSize(bytes)))
            .setNegativeButton(R.string.runtime_install_negative, null)
            .setPositiveButton(R.string.runtime_install_positive) { _, _ -> installRuntime() }
            .show()
    }

    /**
     * Whether there is a network to download over.
     *
     * Asked before a 227 MB transfer, not discovered part-way into one: the
     * alternative is a socket error the user has no way to act on.
     */
    private fun hasNetworkConnection(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /**
     * Whether the app may use the network at all.
     *
     * INTERNET is granted at install time, so this is never a prompt — it is a
     * check that the manifest still declares what the downloader needs. The
     * permission was absent when the downloader was written, and the symptom
     * was a socket error with nothing pointing at the missing declaration.
     * Naming it beats failing with "Permission denied".
     */
    private fun hasInternetPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED

    private fun installRuntime() {
        if (!hasInternetPermission()) {
            showMessage(getString(R.string.runtime_install_no_permission))
            return
        }

        if (!hasNetworkConnection()) {
            showMessage(getString(R.string.runtime_install_offline))
            return
        }

        val acquisition = RuntimeProviders.acquisition(this)
        runtimeInstalling = true
        setRuntimeInstallUi(true, getString(R.string.runtime_install_starting))
        updateRuntimeInstallButton()
        revealRuntimeProgress()

        background.execute {
            val outcome = runCatching {
                acquisition.installAll(onProgress = { progress ->
                    runOnUiThread { onRuntimeInstallProgress(progress) }
                })
            }.getOrElse { error ->
                // No package to blame: this is the download as a whole failing,
                // outside anything RuntimePackageManager could name.
                RuntimeAcquisition.Outcome.Failed(
                    getString(R.string.runtime_install_unknown),
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }

            runOnUiThread {
                runtimeInstalling = false
                setRuntimeInstallUi(false, null)
                onRuntimeInstalled(outcome)
                // Recomputed, not restored. A failed download leaves a
                // different state behind from the one the button was disabled
                // for, and only one branch of onRuntimeInstalled recomputes it.
                updateLaunchState()
            }
        }
    }

    private fun onRuntimeInstallProgress(progress: RuntimeAcquisition.Progress) {
        binding.runtimeInstallProgressBar.isIndeterminate = progress.percent <= 0
        if (progress.percent > 0) binding.runtimeInstallProgressBar.progress = progress.percent
        binding.runtimeInstallProgressText.text = getString(
            R.string.runtime_install_progress,
            progress.pack.name,
            progress.percent,
            progress.index + 1,
            progress.total,
        )
        // And on the button that was tapped. The bar is two cards down a
        // scrolling screen, so a download that is progressing looks exactly
        // like one that is stuck to anyone not already looking at it. The
        // button is where the user's finger just was.
        binding.runtimeInstallButton.text = progress.toString()
    }

    /**
     * Scrolls the download progress into view.
     *
     * The runtime card sits below the header, display and install cards. On a
     * phone that puts the progress off-screen at the moment the download
     * starts, which is indistinguishable from nothing happening.
     */
    private fun revealRuntimeProgress() {
        val scroll = binding.launcherScroll
        val target = binding.runtimeInstallProgress
        scroll.post {
            val rect = Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.smoothScrollTo(0, (rect.top - SCROLL_MARGIN_PX).coerceAtLeast(0))
        }
    }

    private fun onRuntimeInstalled(outcome: RuntimeAcquisition.Outcome) {
        when (outcome) {
            is RuntimeAcquisition.Outcome.Installed -> showMessage(getString(R.string.runtime_install_done))
            is RuntimeAcquisition.Outcome.Failed -> showMessage(
                getString(R.string.runtime_install_failed, outcome.packageName, outcome.reason),
            )
            RuntimeAcquisition.Outcome.Cancelled -> showMessage(
                getString(R.string.runtime_install_cancelled),
            )
            RuntimeAcquisition.Outcome.NothingToDo -> Unit
        }
    }

    private fun setRuntimeInstallUi(active: Boolean, text: String?) {
        binding.runtimeInstallProgress.isVisible = active
        binding.launchButton.isEnabled = !active && binding.launchButton.isEnabled
        if (text != null) binding.runtimeInstallProgressText.text = text
        binding.runtimeInstallProgressBar.isIndeterminate = true
    }

    // ------------------------------------------------------------ diagnostics

    private fun refreshDiagnosticsCard() {
        val report = diagnostics.latestReport()

        if (report == null) {
            binding.diagnosticsStatus.rowTitle.text = getString(R.string.diagnostics_none)
            binding.diagnosticsStatus.rowDetail.text = getString(R.string.diagnostics_none_detail)
            applyDiagnosticsChip(null)
            binding.diagnosticsDetail.text = ""
            binding.diagnosticsDetail.isVisible = false
        } else {
            binding.diagnosticsStatus.rowTitle.text = ReportBuilder.headline(report.classification)
            binding.diagnosticsStatus.rowDetail.text = report.name
            applyDiagnosticsChip(report)
            binding.diagnosticsDetail.text = getString(
                R.string.diagnostics_report_size,
                formatTimestamp(report.createdAtMillis),
                formatSize(report.sizeBytes),
            )
            binding.diagnosticsDetail.isVisible = true
        }

        val hasReport = report != null
        binding.diagnosticsSaveButton.isEnabled = hasReport
        binding.diagnosticsShareButton.isEnabled = hasReport
        binding.diagnosticsCopyButton.isEnabled = hasReport
        binding.diagnosticsDeleteButton.isEnabled = hasReport
    }

    private fun applyDiagnosticsChip(report: CrashReport?) {
        val (label, color) = when (report?.classification) {
            SessionEnd.CRASH -> getString(R.string.status_crash) to R.color.blood_400
            SessionEnd.KILLED_WHILE_BACKGROUNDED ->
                getString(R.string.status_background_kill) to R.color.status_partial

            SessionEnd.UNKNOWN -> getString(R.string.status_unclean) to R.color.status_partial
            else -> getString(R.string.status_none) to R.color.ash
        }

        val resolved = ContextCompat.getColor(this, color)
        val chip = binding.diagnosticsStatus.statusChip

        chip.text = label
        chip.setTextColor(resolved)
        ViewCompat.setBackgroundTintList(
            chip,
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(resolved, 45)),
        )
        ViewCompat.setBackgroundTintList(
            binding.diagnosticsStatus.statusDot,
            ColorStateList.valueOf(resolved),
        )
    }

    /** Writes a report on a background thread; zipping and probing cost time. */
    private fun generateReport(session: UnfinishedSession?, classification: SessionEnd) {
        binding.diagnosticsGenerateButton.isEnabled = false
        binding.diagnosticsGenerateButton.text = getString(R.string.install_scanning_hint_short)

        background.execute {
            val inputs = reportInputs()
            val report = runCatching {
                diagnostics.generateReport(session, classification, inputs)
            }.getOrNull()

            runOnUiThread {
                binding.diagnosticsGenerateButton.isEnabled = true
                binding.diagnosticsGenerateButton.text = getString(R.string.diagnostics_generate)
                refreshDiagnosticsCard()
                if (report != null) {
                    showMessage(getString(R.string.diagnostics_generated))
                    appendDiagnosticsNote(getString(R.string.diagnostics_auto_generated))
                }
            }
        }
    }

    /** Everything a report should describe, gathered here where it is known. */
    private fun reportInputs() = ReportInputs(
        settings = settings.snapshot(),
        runtime = runtimeFacts(),
        install = installFacts(),
        nativeProbe = nativeProbeResult?.output,
        runtimeProbe = runtimeProbeResult?.output,
        runtimeOutput = runtimeOutput.toString(),
    )

    /**
     * The runtime section of a report.
     *
     * Starts from the component statuses and adds what the runtime itself
     * decided: which graphics backend, which CPU translator, which audio
     * bridge, and whether the executable was validated. Absent components
     * are written down as absent, so a reader cannot mistake an unfinished
     * runtime for a working one.
     */
    private fun runtimeFacts(): Map<String, String> = buildMap {
        runtimeComponents().forEach { component ->
            put(component.title, getString(component.status.labelRes()))
        }
        // The exit is passed, not left out. It used to be omitted entirely,
        // which meant every report silently lost the one section that says
        // how the run actually ended — and a report that cannot say that is
        // a report that cannot be investigated. Falls back to the record
        // written by the previous run, for the case where the app itself
        // died and this is the next start.
        putAll(
            runtimeHost.diagnosticsSection(
                capabilities = capabilities,
                exit = lastExit ?: lastRunStore.load(),
            ),
        )
    }

    /**
     * What we know about the game files.
     *
     * The folder path is deliberately left out: a report gets shared, and a
     * path can contain the owner's name.
     */
    private fun installFacts(): Map<String, String> {
        val metadata = installRepository.cached()
        return linkedMapOf(
            "Verified" to (metadata?.let { formatTimestamp(it.verifiedAtMillis) } ?: "never"),
            "Status" to when {
                metadata == null -> "not scanned yet"
                metadata.install() == null -> "no game files found"
                metadata.install()!!.isComplete -> "game files detected"
                else -> "partial install, data folder missing"
            },
            "Location kind" to (metadata?.kind?.name ?: "unknown"),
            "Data folder present" to (metadata?.hasDataDirectory?.toString() ?: "unknown"),
            "Entries examined" to (metadata?.entriesExamined?.toString() ?: "0"),
        )
    }

    private fun shareLatestReport() {
        val report = diagnostics.latestReport() ?: run {
            showMessage(getString(R.string.diagnostics_nothing_to_share))
            return
        }

        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.files", report.file)
        }.getOrNull()
        if (uri == null) {
            showMessage(getString(R.string.diagnostics_nothing_to_share))
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, report.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share)))
        } catch (ignored: ActivityNotFoundException) {
            showMessage(getString(R.string.diagnostics_no_app))
        }
    }

    /**
     * Puts the whole report on the clipboard as text.
     *
     * The report on disk is a ZIP, which is the right thing to write and the
     * wrong thing to paste into a conversation. Pasting is the only way the
     * person reading it can actually receive it, so the zip is flattened here
     * rather than expecting them to have something that can open one.
     */
    private fun copyLatestReport() {
        val report = diagnostics.latestReport() ?: run {
            showMessage(getString(R.string.diagnostics_nothing_to_share))
            return
        }

        val text = runCatching { diagnostics.readText(report) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        if (text == null) {
            showMessage(getString(R.string.diagnostics_nothing_to_share))
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            showMessage(getString(R.string.diagnostics_nothing_to_share))
            return
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(report.name, text))
        showMessage(getString(R.string.diagnostics_copied, formatSize(text.length.toLong())))
    }

    private fun saveReportTo(destination: Uri) {
        val report = diagnostics.latestReport() ?: return

        val copied = runCatching {
            contentResolver.openOutputStream(destination)?.use { out ->
                report.file.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)

        showMessage(
            if (copied) {
                getString(R.string.diagnostics_saved)
            } else {
                getString(R.string.diagnostics_nothing_to_share)
            }
        )
    }

    private fun deleteLatestReport() {
        val report = diagnostics.latestReport() ?: return
        diagnostics.delete(report)
        refreshDiagnosticsCard()
        showMessage(getString(R.string.diagnostics_deleted))
    }

    private fun appendDiagnosticsNote(text: String) {
        binding.diagnosticsDetail.isVisible = true
        binding.diagnosticsDetail.text = listOf(
            binding.diagnosticsDetail.text.toString(),
            text,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    // ------------------------------------------------------------ fps overlay

    /**
     * Shows or hides the frame-rate readout.
     *
     * The number comes from [FpsMeter], which counts real Choreographer frame
     * callbacks, so it is the refresh rate Android is actually delivering to
     * this surface rather than a stored value being echoed back.
     */
    private fun updateFpsOverlay() {
        if (!settings.fpsOverlayEnabled) {
            fpsMeter.stop()
            removeFpsOverlay()
            return
        }

        val overlay = ensureFpsOverlay()
        fpsMeter.start()
        overlay.text = getString(R.string.fps_overlay_prefix, 0)
    }

    private fun ensureFpsOverlay(): TextView {
        fpsOverlay?.let { return it }

        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = getString(R.string.fps_overlay_prefix, 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.blood_400))
            textSize = 12f
            letterSpacing = 0.08f
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.fps_overlay_bg)
            val pad = (8 * density).toInt()
            setPadding(pad, (4 * density).toInt(), pad, (4 * density).toInt())
        }

        val margin = (16 * density).toInt()
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(margin, margin + (24 * density).toInt(), margin, 0)
        }

        (window.decorView as ViewGroup).addView(view, params)
        fpsOverlay = view
        return view
    }

    private fun removeFpsOverlay() {
        fpsOverlay?.let { (window.decorView as ViewGroup).removeView(it) }
        fpsOverlay = null
    }

    private companion object {
        /** Cache keys for the two bundled probes. */
        const val PROBE_NATIVE = "native"
        const val PROBE_RUNTIME = "runtime"
        const val PROBE_GRAPHICS = "graphics"
        const val PROBE_PROCESS = "process"

        /** Breathing room above the progress when the card is scrolled to. */
        const val SCROLL_MARGIN_PX = 48

        /** Cap on how much of the runtime's output a report will carry. */
        const val RUNTIME_OUTPUT_LIMIT = 256 * 1024

        /** How much of a failed run to show on screen. Enough to see a crash. */
        const val LAST_OUTPUT_LINES = 40
    }
}
