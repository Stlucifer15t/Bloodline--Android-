package com.bloodline.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bloodline.android.BuildConfig
import com.bloodline.android.R
import com.bloodline.android.databinding.ActivitySettingsBinding
import com.bloodline.android.databinding.ViewSettingOptionBinding
import com.bloodline.android.databinding.ViewSettingSliderBinding
import com.bloodline.android.databinding.ViewSettingSwitchBinding
import com.bloodline.android.databinding.ViewSettingsSectionBinding
import com.bloodline.android.game.GameInstallRepository
import com.bloodline.android.storage.GameFolderStore
import com.bloodline.android.storage.SharedPreferencesInstallMetadataStore
import com.bloodline.android.storage.StorageLocations
import com.bloodline.android.settings.AudioBackend
import com.bloodline.android.settings.AudioVolumeController
import com.bloodline.android.settings.AspectRatioMode
import com.bloodline.android.settings.FpsLimit
import com.bloodline.android.settings.PerformancePreset
import com.bloodline.android.settings.RendererChoice
import com.bloodline.android.settings.ResolutionScale
import com.bloodline.android.settings.ScreenOrientation
import com.bloodline.android.settings.SettingsApplier
import com.bloodline.android.settings.SettingsProvider
import com.bloodline.android.settings.SettingsRepository
import com.bloodline.android.settings.UiScale
import com.bloodline.android.settings.UiScaleApplier
import com.bloodline.android.storage.GameFolderStore

/**
 * The settings screen.
 *
 * Two rules shape everything here:
 *
 * 1. A control only exists if it does something. Rows that need the Windows
 *    compatibility runtime (frame limiting, resolution scaling, renderer
 *    choice) are shown switched off with the reason written underneath, instead
 *    of pretending to work.
 * 2. Everything the user changes is persisted through [SettingsRepository], so
 *    it survives restarting the app and the device.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private lateinit var audio: AudioVolumeController
    private lateinit var gameFolderStore: GameFolderStore
    private lateinit var installRepository: GameInstallRepository

    override fun attachBaseContext(newBase: Context) {
        // The interface scale has to be applied before any view exists.
        super.attachBaseContext(
            UiScaleApplier.wrap(newBase, SettingsProvider.get(newBase).uiScale),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsProvider.get(this)
        audio = AudioVolumeController(this)
        gameFolderStore = GameFolderStore(this)
        installRepository = GameInstallRepository(
            roots = { StorageLocations.searchRoots(this, gameFolderStore) },
            metadataStore = SharedPreferencesInstallMetadataStore(this),
            appVersion = BuildConfig.VERSION_NAME,
        )

        buildSections()

        binding.resetButton.setOnClickListener {
            settings.resetToDefaults()
            applyNow()
            recreate()
        }
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        applyNow()
    }

    /** Applies the settings that can take effect without leaving this screen. */
    private fun applyNow() {
        // Orientation is left to the launcher: rotating here would turn the
        // settings screen sideways while you are still using it.
        SettingsApplier.apply(this, settings, includeOrientation = false)
    }

    // ----------------------------------------------------------------- content

    private fun buildSections() {
        val inflater = layoutInflater

        section(inflater, R.string.settings_section_display) {
            switchRow(
                title = getString(R.string.setting_immersive),
                detail = getString(R.string.setting_immersive_detail),
                checked = settings.immersiveMode,
                onChanged = { settings.immersiveMode = it },
            )
            optionRow(
                title = getString(R.string.setting_orientation),
                detail = getString(R.string.setting_orientation_detail),
                options = listOf(
                    option(R.string.value_portrait, ScreenOrientation.PORTRAIT),
                    option(R.string.value_landscape, ScreenOrientation.LANDSCAPE),
                    option(R.string.value_follow_device, ScreenOrientation.FOLLOW_DEVICE),
                ),
                selected = settings.screenOrientation,
                onSelected = { settings.screenOrientation = it },
            )
            switchRow(
                title = getString(R.string.setting_keep_awake),
                detail = getString(R.string.setting_keep_awake_detail),
                checked = settings.keepScreenAwake,
                onChanged = { settings.keepScreenAwake = it },
            )
            optionRow(
                title = getString(R.string.setting_ui_scale),
                detail = getString(R.string.setting_ui_scale_detail),
                options = UiScale.entries.map { scale ->
                    option(
                        when (scale) {
                            UiScale.SMALL -> R.string.value_small
                            UiScale.NORMAL -> R.string.value_normal
                            UiScale.LARGE -> R.string.value_large
                        },
                        scale,
                    )
                },
                selected = settings.uiScale,
                onSelected = {
                    settings.uiScale = it
                    // The scale is baked into the context, so the screen has to
                    // be rebuilt for it to take effect.
                    recreate()
                },
            )
            optionRow(
                title = getString(R.string.setting_brightness),
                detail = getString(R.string.setting_brightness_detail),
                options = listOf(
                    Option(
                        getString(R.string.value_follow_system),
                        SettingsRepository.BRIGHTNESS_FOLLOW_SYSTEM,
                    ),
                ) + BRIGHTNESS_STEPS.map { percent -> Option("$percent%", percent) },
                selected = settings.brightnessPercent,
                onSelected = {
                    settings.brightnessPercent = it
                    applyNow()
                },
            )

            // Everything below needs the runtime to have a rendering surface.
            switchRow(
                title = getString(R.string.setting_native_resolution),
                detail = getString(R.string.setting_native_resolution_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                checked = settings.useNativeResolution,
                onChanged = { settings.useNativeResolution = it },
            )
            optionRow(
                title = getString(R.string.setting_resolution_scale),
                detail = getString(R.string.setting_resolution_scale_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                options = ResolutionScale.entries.map { Option("${it.percent}%", it) },
                selected = settings.resolutionScale,
                onSelected = { settings.resolutionScale = it },
            )
            optionRow(
                title = getString(R.string.setting_aspect_ratio),
                detail = getString(R.string.setting_aspect_ratio_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                options = listOf(
                    option(R.string.value_preserve, AspectRatioMode.PRESERVE),
                    option(R.string.value_fit, AspectRatioMode.FIT),
                    option(R.string.value_stretch, AspectRatioMode.STRETCH),
                ),
                selected = settings.aspectRatio,
                onSelected = { settings.aspectRatio = it },
            )
        }

        section(inflater, R.string.settings_section_performance) {
            switchRow(
                title = getString(R.string.setting_fps_overlay),
                detail = getString(R.string.setting_fps_overlay_detail),
                checked = settings.fpsOverlayEnabled,
                onChanged = { settings.fpsOverlayEnabled = it },
            )
            optionRow(
                title = getString(R.string.setting_fps_limit),
                detail = getString(R.string.setting_fps_limit_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                options = FpsLimit.entries.map { limit ->
                    Option(
                        if (limit == FpsLimit.UNCAPPED) {
                            getString(R.string.value_uncapped)
                        } else {
                            limit.label
                        },
                        limit,
                    )
                },
                selected = settings.fpsLimit,
                onSelected = { settings.fpsLimit = it },
            )
            switchRow(
                title = getString(R.string.setting_vsync),
                detail = getString(R.string.setting_vsync_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                checked = settings.vsyncEnabled,
                onChanged = { settings.vsyncEnabled = it },
            )
            switchRow(
                title = getString(R.string.setting_frame_pacing),
                detail = getString(R.string.setting_frame_pacing_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                checked = settings.framePacingEnabled,
                onChanged = { settings.framePacingEnabled = it },
            )
            optionRow(
                title = getString(R.string.setting_performance_preset),
                detail = getString(R.string.setting_performance_preset_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                options = listOf(
                    option(R.string.value_preset_safe, PerformancePreset.SAFE),
                    option(R.string.value_preset_balanced, PerformancePreset.BALANCED),
                    option(R.string.value_preset_aggressive, PerformancePreset.AGGRESSIVE),
                ),
                selected = settings.performancePreset,
                onSelected = { settings.performancePreset = it },
            )
        }

        section(inflater, R.string.settings_section_graphics) {
            optionRow(
                title = getString(R.string.setting_renderer),
                detail = getString(R.string.setting_renderer_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                options = RendererChoice.entries.map { choice ->
                    Option(
                        getString(
                            when (choice) {
                                RendererChoice.WINE_D3D_VIRGL -> R.string.renderer_wined3d
                                RendererChoice.PANVK_DXVK -> R.string.renderer_panvk
                            }
                        ),
                        choice,
                    )
                },
                selected = settings.renderer,
                onSelected = { settings.renderer = it },
            )
            note(getString(R.string.setting_renderer_fallback_note))
        }

        section(inflater, R.string.settings_section_audio) {
            val systemVolume = audio.currentPercent()
            if (systemVolume < 0) {
                readOnlyRow(
                    title = getString(R.string.setting_master_volume),
                    detail = getString(R.string.setting_master_volume_detail),
                    value = getString(R.string.setting_value_unavailable),
                )
            } else {
                settings.initialiseMasterVolume(systemVolume)
                sliderRow(
                    title = getString(R.string.setting_master_volume),
                    detail = getString(R.string.setting_master_volume_detail),
                    progress = settings.masterVolume.coerceAtLeast(0),
                    onChanged = { percent ->
                        audio.setPercent(percent)
                        settings.masterVolume = percent
                    },
                )
            }
            optionRow(
                title = getString(R.string.setting_audio_backend),
                detail = getString(R.string.setting_audio_backend_detail),
                enabled = false,
                reason = getString(R.string.setting_needs_runtime),
                options = listOf(
                    option(R.string.value_auto, AudioBackend.AUTO),
                    option(R.string.value_aaudio, AudioBackend.AAUDIO),
                    option(R.string.value_opensles, AudioBackend.OPENSLES),
                ),
                selected = settings.audioBackend,
                onSelected = { settings.audioBackend = it },
            )
            readOnlyRow(
                title = getString(R.string.setting_audio_channels),
                detail = getString(R.string.setting_audio_channels_detail),
                value = getString(R.string.setting_unsupported),
            )
        }

        section(inflater, R.string.settings_section_controls) {
            note(getString(R.string.setting_controls_note))
            val reason = getString(R.string.setting_needs_input_layer)
            listOf(
                R.string.setting_controls_hud,
                R.string.setting_controls_joystick,
                R.string.setting_controls_sensitivity,
                R.string.setting_controls_buttons,
                R.string.setting_controls_transparency,
                R.string.setting_controls_slots,
                R.string.setting_controls_handedness,
                R.string.setting_controls_profiles,
            ).forEach { titleRes ->
                readOnlyRow(
                    title = getString(titleRes),
                    detail = reason,
                    value = getString(R.string.setting_value_not_set),
                )
            }
        }

        section(inflater, R.string.settings_section_storage) {
            readOnlyRow(
                title = getString(R.string.setting_install_folder),
                detail = getString(R.string.setting_install_folder_detail),
                value = gameFolderStore.load()?.let { describeFolder(it) }
                    ?: getString(R.string.setting_value_not_set),
            )
            readOnlyRow(
                title = getString(R.string.setting_install_status),
                detail = getString(R.string.setting_install_status_detail),
                value = installStatusLabel(),
            )
            actionRow(
                title = getString(R.string.setting_rescan),
                detail = getString(R.string.setting_rescan_detail),
            ) {
                // The launcher owns the scan, so ask it to run one and leave.
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESCAN, true))
                finish()
            }
            actionRow(
                title = getString(R.string.setting_clear_cache),
                detail = getString(R.string.setting_clear_cache_detail),
            ) {
                installRepository.clearCache()
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CLEAR_CACHE, true))
                finish()
            }
            readOnlyRow(
                title = getString(R.string.setting_runtime_version),
                detail = getString(R.string.runtime_overall_detail),
                value = getString(R.string.status_not_integrated),
            )
        }
    }

    /** What the last verified scan concluded, without running a new one. */
    private fun installStatusLabel(): String {
        val metadata = installRepository.cached() ?: return getString(R.string.install_status_unknown)
        val install = metadata.install() ?: return getString(R.string.install_status_not_found)

        return getString(
            when {
                install.isComplete -> R.string.install_status_found
                else -> R.string.install_status_incomplete
            }
        )
    }

    /** Turns a Storage Access Framework tree URI into something readable. */
    private fun describeFolder(uri: Uri): String {
        val raw = uri.toString()
        val afterColon = raw.substringAfter(':')
        return afterColon.takeIf { it.isNotBlank() && it.length > 1 } ?: raw
    }

    // ------------------------------------------------------------------ widgets

    private fun section(
        inflater: LayoutInflater,
        titleRes: Int,
        build: SectionScope.() -> Unit,
    ) {
        val section = ViewSettingsSectionBinding.inflate(inflater, binding.settingsSections, false)
        section.sectionTitle.text = getString(titleRes)
        SectionScope(section.sectionItems, inflater).build()
        if (section.sectionItems.childCount > 0) {
            binding.settingsSections.addView(section.root)
        }
    }

    private fun <T> option(labelRes: Int, value: T): Option<T> = Option(getString(labelRes), value)

    private class Option<T>(val label: String, val value: T)

    /**
     * Builds the rows of one card.
     *
     * Every row takes an `enabled` flag: when it is false the row is dimmed,
     * cannot be clicked, and says why. That is how this screen avoids shipping
     * a control that does nothing.
     */
    private inner class SectionScope(
        private val container: LinearLayout,
        private val inflater: LayoutInflater,
    ) {

        fun switchRow(
            title: String,
            detail: String,
            enabled: Boolean = true,
            reason: String = "",
            checked: Boolean,
            onChanged: (Boolean) -> Unit,
        ) {
            val row = ViewSettingSwitchBinding.inflate(inflater, container, false)
            row.settingTitle.text = title
            row.settingDetail.text = joinDetail(detail, reason)
            row.settingSwitch.isChecked = checked

            applyEnabled(row.root, enabled)
            row.settingSwitch.isEnabled = enabled
            if (enabled) {
                row.settingSwitch.setOnCheckedChangeListener { _, value -> onChanged(value) }
                row.root.setOnClickListener { row.settingSwitch.toggle() }
            }
            container.addView(row.root)
        }

        fun <T> optionRow(
            title: String,
            detail: String,
            enabled: Boolean = true,
            reason: String = "",
            options: List<Option<T>>,
            selected: T,
            onSelected: (T) -> Unit,
            valueOverride: String? = null,
        ) {
            val row = ViewSettingOptionBinding.inflate(inflater, container, false)
            row.settingTitle.text = title
            row.settingDetail.text = joinDetail(detail, reason)
            row.settingValue.text = valueOverride
                ?: options.firstOrNull { it.value == selected }?.label
                ?: options.firstOrNull()?.label
                ?: getString(R.string.setting_value_not_set)

            applyEnabled(row.root, enabled)
            if (enabled && options.isNotEmpty()) {
                row.root.setOnClickListener {
                    showChoiceDialog(title, options, selected, onSelected)
                }
            }
            container.addView(row.root)
        }

        /** A row that runs an action when tapped. */
        fun actionRow(
            title: String,
            detail: String,
            enabled: Boolean = true,
            action: () -> Unit,
        ) {
            val row = ViewSettingOptionBinding.inflate(inflater, container, false)
            row.settingTitle.text = title
            row.settingDetail.text = detail
            row.settingValue.text = ""

            applyEnabled(row.root, enabled)
            if (enabled) row.root.setOnClickListener { action() }
            container.addView(row.root)
        }

        /** A row that reports a fact and cannot be changed. */
        fun readOnlyRow(title: String, detail: String, value: String) {
            optionRow(
                title = title,
                detail = detail,
                enabled = false,
                options = emptyList(),
                selected = Unit,
                onSelected = {},
                valueOverride = value,
            )
        }

        fun sliderRow(
            title: String,
            detail: String,
            progress: Int,
            onChanged: (Int) -> Unit,
        ) {
            val row = ViewSettingSliderBinding.inflate(inflater, container, false)
            row.settingTitle.text = title
            row.settingDetail.text = detail
            row.settingValue.text = "$progress%"
            row.settingSlider.progress = progress
            row.settingSlider.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        row.settingValue.text = "$value%"
                        if (fromUser) onChanged(value)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
            container.addView(row.root)
        }

        fun note(text: String) {
            val view = inflater.inflate(R.layout.view_settings_note, container, false) as TextView
            view.text = text
            container.addView(view)
        }
    }

    private fun joinDetail(detail: String, reason: String): String =
        if (reason.isBlank()) detail else "$detail\n$reason"

    private fun applyEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.45f
        row.isClickable = enabled
    }

    private fun <T> showChoiceDialog(
        title: String,
        options: List<Option<T>>,
        selected: T,
        onSelected: (T) -> Unit,
    ) {
        val labels = options.map { it.label }.toTypedArray()
        val currentIndex = options.indexOfFirst { it.value == selected }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                onSelected(options[which].value)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.import_dialog_positive, null)
            .show()
    }

    companion object {
        /** Ask the launcher to verify the game files again. */
        const val EXTRA_RESCAN = "extra_rescan"

        /** Ask the launcher to forget the cached result and scan again. */
        const val EXTRA_CLEAR_CACHE = "extra_clear_cache"

        private val BRIGHTNESS_STEPS = listOf(25, 50, 75, 100)
    }
}
