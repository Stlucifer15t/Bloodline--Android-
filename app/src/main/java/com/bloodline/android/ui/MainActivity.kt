package com.bloodline.android.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
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
import com.bloodline.android.game.GameInstallScanner
import com.bloodline.android.game.LocationKind
import com.bloodline.android.runtime.BlockReason
import com.bloodline.android.runtime.ComponentIds
import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.LaunchReadiness
import com.bloodline.android.runtime.LaunchState
import com.bloodline.android.runtime.NativeProbe
import com.bloodline.android.runtime.RuntimeComponent
import com.bloodline.android.runtime.RuntimeRegistry
import com.bloodline.android.storage.DocumentFolder
import com.bloodline.android.storage.GameFolderStore
import com.bloodline.android.storage.StorageLocations

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

    private var install: GameInstall? = null

    /** Result of the bundled native probe, and the status it implies. */
    private var nativeProbeResult: NativeProbe.Result? = null
    private var nativeHostingStatus: IntegrationStatus = IntegrationStatus.NOT_INTEGRATED

    /** Built on demand so it always reflects the currently selected folder. */
    private val scanner: GameInstallScanner
        get() = GameInstallScanner(StorageLocations.searchRoots(this, gameFolderStore))

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameFolderStore = GameFolderStore(this)

        bindStaticContent()
        bindRuntimeRows()
        refreshGameStatus()
        runNativeProbe()

        binding.importButton.setOnClickListener { folderPicker.launch(null) }
        binding.launchButton.setOnClickListener { onLaunchClicked() }
    }

    override fun onResume() {
        super.onResume()
        // The user may have copied files over while the app was backgrounded.
        refreshGameStatus()
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

    private fun refreshGameStatus() {
        val found = scanner.findInstall()
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
        binding.installStatus.rowDetail.text = getString(
            when {
                found == null -> R.string.install_detail_not_found
                !found.isComplete -> R.string.install_detail_incomplete
                found.location.kind == LocationKind.EXTERNAL_FOLDER ->
                    R.string.install_detail_found_external
                else -> R.string.install_detail_found
            }
        )
        applyStatus(binding.installStatus, status)

        // Only show the resolved location; when nothing was found the detail
        // line above already explains what to do.
        val location = found?.location?.displayPath
        binding.installNote.isVisible = location != null
        if (location != null) {
            binding.installNote.text = location
        }

        binding.importButton.text = getString(
            if (gameFolderStore.load() != null) {
                R.string.import_change_folder
            } else {
                R.string.import_game_files
            }
        )

        updateLaunchState()
    }

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
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        val accessGranted = runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        if (!accessGranted) {
            showMessage(getString(R.string.import_access_failed))
            return
        }

        val folder = runCatching { DocumentFolder.fromTree(this, uri) }.getOrNull()
        val found = folder?.let { runCatching { scannerFor(it).findInstall() }.getOrNull() }

        if (found == null) {
            // Nothing usable in there: forget the dead end rather than storing
            // a folder that does not contain the game.
            runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
            showInvalidFolderDialog()
            return
        }

        gameFolderStore.save(uri)
        refreshGameStatus()
        showMessage(getString(R.string.import_linked, found.location.displayPath))
    }

    private fun scannerFor(folder: DocumentFolder) = GameInstallScanner(listOf(folder))

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
            runOnUiThread { onNativeProbeFinished(result) }
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
}
