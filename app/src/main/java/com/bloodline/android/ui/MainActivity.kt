package com.bloodline.android.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.bloodline.android.BuildConfig
import com.bloodline.android.R
import com.bloodline.android.databinding.ActivityMainBinding
import com.bloodline.android.databinding.ViewConfigRowBinding
import com.bloodline.android.databinding.ViewStatusRowBinding
import com.bloodline.android.game.GameInstall
import com.bloodline.android.game.GameInstallScanner
import com.bloodline.android.runtime.BlockReason
import com.bloodline.android.runtime.ComponentIds
import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.LaunchReadiness
import com.bloodline.android.runtime.LaunchState
import com.bloodline.android.runtime.RuntimeComponent
import com.bloodline.android.runtime.RuntimeRegistry
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
    private lateinit var scanner: GameInstallScanner

    private var install: GameInstall? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = GameInstallScanner(StorageLocations.searchRoots(this))

        bindStaticContent()
        bindRuntimeRows()
        refreshGameStatus()

        binding.importButton.setOnClickListener { showImportDialog() }
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
        val roots = StorageLocations.searchRoots(this)
        bindConfigRow(
            binding.installSearchPath,
            R.string.install_search_path_label,
            resources.getQuantityString(R.plurals.install_search_path_value, roots.size, roots.size),
        )

        binding.footerVersion.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
    }

    private fun bindRuntimeRows() {
        val components = RuntimeRegistry.components().associateBy { it.id }

        bindStatusRow(
            binding.runtimeOverall,
            RuntimeComponent(
                id = "overall",
                title = getString(R.string.runtime_overall_label),
                detail = getString(R.string.runtime_overall_detail),
                status = RuntimeRegistry.overallStatus(),
            ),
        )
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
                found.isComplete -> R.string.install_detail_found
                else -> R.string.install_detail_incomplete
            }
        )
        applyStatus(binding.installStatus, status)

        binding.installNote.text = found?.rootDirectory?.absolutePath
            ?: getString(R.string.install_detail_not_found)

        updateLaunchState()
    }

    private fun updateLaunchState() {
        val state = LaunchReadiness.evaluate(install)

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

    private fun showImportDialog() {
        val target = StorageLocations.importTarget(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.import_dialog_title)
            .setMessage(getString(R.string.import_dialog_message, target.absolutePath))
            .setPositiveButton(R.string.import_dialog_positive, null)
            .show()
    }

    private fun onLaunchClicked() {
        // Defensive: the button is disabled unless LaunchReadiness reports
        // Ready, so a click while blocked can only come from an unexpected
        // state. Tell the user why instead of failing silently.
        if (LaunchReadiness.evaluate(install) !is LaunchState.Ready) {
            Toast.makeText(this, binding.launchHint.text, Toast.LENGTH_SHORT).show()
        }
        // Launching itself is implemented together with the runtime container.
    }
}
