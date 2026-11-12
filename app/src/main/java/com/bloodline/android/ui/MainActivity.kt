package com.bloodline.android.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import com.bloodline.android.BuildConfig
import com.bloodline.android.R
import com.bloodline.android.databinding.ActivityMainBinding
import com.bloodline.android.databinding.ViewConfigRowBinding
import com.bloodline.android.databinding.ViewStatusRowBinding
import com.bloodline.android.game.GameInstall
import com.bloodline.android.game.GameInstallRepository
import com.bloodline.android.game.GameInstallScanner
import com.bloodline.android.game.InstallScanReport
import com.bloodline.android.game.LocationKind
import com.bloodline.android.game.ScanProgress
import com.bloodline.android.game.ScanSource
import com.bloodline.android.game.ScanStage
import com.bloodline.android.runtime.BlockReason
import com.bloodline.android.runtime.ComponentIds
import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.LaunchReadiness
import com.bloodline.android.runtime.LaunchState
import com.bloodline.android.runtime.NativeProbeCache
import com.bloodline.android.runtime.NativeProbe
import com.bloodline.android.runtime.RuntimeComponent
import com.bloodline.android.runtime.RuntimeRegistry
import com.bloodline.android.settings.FpsMeter
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
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameFolderStore: GameFolderStore
    private lateinit var settings: SettingsRepository
    private lateinit var installRepository: GameInstallRepository

    /** Background thread for one-off work that must never block the UI. */
    private val background: ExecutorService = Executors.newSingleThreadExecutor()

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
    private var nativeHostingStatus: IntegrationStatus = IntegrationStatus.NOT_INTEGRATED

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

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

        bindStaticContent()
        bindRuntimeRows()

        // Renders the remembered result straight away, and only scans when that
        // result is missing or stale. This is what makes reopening fast.
        ensureInstall()

        val cachedProbe = NativeProbeCache(this).load(BuildConfig.VERSION_NAME)
        if (cachedProbe != null) {
            onNativeProbeFinished(cachedProbe)
        } else {
            runNativeProbe()
        }

        binding.importButton.setOnClickListener { folderPicker.launch(null) }
        binding.launchButton.setOnClickListener { onLaunchClicked() }
        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-applied here as well as in onCreate so a change made in the
        // settings screen shows up the moment the user comes back.
        SettingsApplier.apply(this, settings)
        updateFpsOverlay()

        // Deliberately no rescan here: the cached result is still valid, and
        // re-walking the game folder on every resume is what made reopening the
        // app slow. Use "Re-scan for game files" in Settings when needed.
    }

    override fun onPause() {
        super.onPause()
        fpsMeter.stop()
    }

    // ---------------------------------------------------------------- content

    private fun bindStaticContent() {
        bindConfigRow(
            binding.graphicsRenderer,
            R.string.graphics_renderer,
            R.string.graphics_renderer_value,
        )
        bindConfigRow(
            binding.graphicsWrapper,
            R.string.graphics_wrapper,
            R.string.graphics_wrapper_value,
        )
        bindConfigRow(
            binding.graphicsResolution,
            R.string.graphics_resolution,
            R.string.graphics_resolution_value,
        )
        bindConfigRow(binding.audioBackend, R.string.audio_backend, R.string.audio_backend_value)
        bindConfigRow(binding.audioOutput, R.string.audio_output, R.string.audio_output_value)

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

        binding.footerVersion.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
    }

    /** Components with the live, device measured status folded in. */
    private fun runtimeComponents() =
        RuntimeRegistry.components(nativeHosting = nativeHostingStatus)

    private fun bindRuntimeRows() {
        val components = runtimeComponents().associateBy { it.id }

        bindStatusRow(
            binding.runtimeOverall,
            RuntimeComponent(
                id = "overall",
                title = getString(R.string.runtime_overall_label),
                detail = getString(R.string.runtime_overall_detail),
                status = RuntimeRegistry.overallStatus(),
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

    private fun updateLaunchState() {
        val state = LaunchReadiness.evaluate(install, runtimeComponents())

        binding.launchButton.isEnabled = state is LaunchState.Ready
        binding.launchHint.text = when (state) {
            LaunchState.Ready -> getString(R.string.launch_ready)
            is LaunchState.Blocked -> getString(state.reason.messageRes())
        }
    }

    private fun BlockReason.messageRes(): Int = when (this) {
        BlockReason.NO_GAME_FILES -> R.string.launch_locked_no_install
        BlockReason.INCOMPLETE_GAME_FILES -> R.string.launch_locked_incomplete
        BlockReason.RUNTIME_NOT_INTEGRATED -> R.string.launch_locked_no_runtime
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
                NativeProbeCache(this).save(BuildConfig.VERSION_NAME, result)
                onNativeProbeFinished(result)
            }
        }.start()
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
        if (LaunchReadiness.evaluate(install, runtimeComponents()) !is LaunchState.Ready) {
            Toast.makeText(this, binding.launchHint.text, Toast.LENGTH_SHORT).show()
        }
        // Launching itself is implemented together with the runtime container.
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
}
