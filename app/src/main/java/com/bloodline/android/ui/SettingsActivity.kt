package com.bloodline.android.ui

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bloodline.android.BuildConfig
import com.bloodline.android.R
import com.bloodline.android.databinding.ActivitySettingsBinding
import com.bloodline.android.databinding.DialogCustomResolutionBinding
import com.bloodline.android.databinding.DialogCustomScaleBinding
import com.bloodline.android.databinding.ViewSettingOptionBinding
import com.bloodline.android.databinding.ViewSettingSliderBinding
import com.bloodline.android.databinding.ViewSettingSwitchBinding
import com.bloodline.android.databinding.ViewSettingsSectionBinding
import com.bloodline.android.display.AspectMode
import com.bloodline.android.display.DisplayCapabilities
import com.bloodline.android.display.DisplayInfo
import com.bloodline.android.display.RenderResolution
import com.bloodline.android.display.ResolutionCalculator
import com.bloodline.android.display.ResolutionScale
import com.bloodline.android.display.describe
import com.bloodline.android.game.GameInstallRepository
import com.bloodline.android.runtime.NativeProbe
import com.bloodline.android.runtime.ProbeCache
import com.bloodline.android.runtime.RuntimeProbe
import com.bloodline.android.storage.GameFolderStore
import com.bloodline.android.storage.SharedPreferencesInstallMetadataStore
import com.bloodline.android.storage.StorageLocations
import com.bloodline.android.settings.AudioBackend
import com.bloodline.android.settings.AudioVolumeController
import com.bloodline.android.settings.FpsLimit
import com.bloodline.android.settings.OrientationApplier
import com.bloodline.android.settings.OrientationController
import com.bloodline.android.settings.PerformancePreset
import com.bloodline.android.settings.RendererChoice
import com.bloodline.android.settings.SettingsApplier
import com.bloodline.android.settings.SettingsProvider
import com.bloodline.android.settings.SettingsRepository
import com.bloodline.android.settings.UiScale
import com.bloodline.android.settings.UiScaleApplier

/**
 * The settings screen.
 *
 * Three rules shape everything here:
 *
 * 1. **A control only exists if it does something.** Rows that need the Windows
 *    compatibility runtime are shown switched off with the reason written
 *    underneath, instead of pretending to work.
 * 2. **Every row says what it does.** Each one carries a sentence explaining its
 *    effect and whether that effect exists yet.
 * 3. **Launcher-only and runtime-bound settings are kept apart.** The six
 *    sections are General, Display, Performance, Audio, Input and Diagnostics,
 *    and a row is filed under the section that owns it rather than wherever it
 *    happened to be added.
 *
 * The one exception to rule 1 is deliberate: display resolution and render
 * scaling accept a value even though nothing renders yet, because the screen
 * can show the *computed result* against the *measured display*. The user can
 * see "1600 × 720 → 1120 × 504" and judge the choice, which makes it a real
 * preference rather than a decorative switch. The rows still say they are not
 * applied.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private lateinit var audio: AudioVolumeController
    private lateinit var gameFolderStore: GameFolderStore
    private lateinit var installRepository: GameInstallRepository

    /** Measured on this device, never assumed. Read once when the screen opens. */
    private lateinit var display: DisplayInfo

    /**
     * The settings screen is part of the launcher phase, so it follows the
     * device like the launcher does. Gameplay forces landscape through its own
     * controller, never through anything chosen here.
     */
    private val orientation = OrientationController { request ->
        OrientationApplier.apply(this, request)
    }

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
        display = DisplayCapabilities.detect(this)

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
        SettingsApplier.apply(this, settings)
        orientation.applyLauncher()
    }

    // ------------------------------------------------------------- sections

    private fun buildSections() {
        val inflater = layoutInflater

        generalSection(inflater)
        displaySection(inflater)
        performanceSection(inflater)
        audioSection(inflater)
        inputSection(inflater)
        diagnosticsSection(inflater)
    }

    // ------------------------------------------------------------- 1 general

    /**
     * Things the launcher can genuinely do today: how it looks, how it behaves,
     * and where the user's own game files are.
     */
    private fun generalSection(inflater: LayoutInflater) {
        section(inflater, R.string.settings_section_general, R.drawable.ic_section_general) {
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
            switchRow(
                title = getString(R.string.setting_immersive),
                detail = getString(R.string.setting_immersive_detail),
                checked = settings.immersiveMode,
                onChanged = { settings.immersiveMode = it },
            )
            switchRow(
                title = getString(R.string.setting_keep_awake),
                detail = getString(R.string.setting_keep_awake_detail),
                checked = settings.keepScreenAwake,
                onChanged = { settings.keepScreenAwake = it },
            )
            note(getString(R.string.setting_orientation_note))

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
        }
    }

    // ------------------------------------------------------------- 2 display

    /**
     * Display resolution and internal render resolution, kept visibly apart.
     *
     * The first is the size the game surface is presented at; the second is the
     * fraction of that the game actually renders. Every number shown here is
     * either measured from the device or computed from those two choices.
     */
    private fun displaySection(inflater: LayoutInflater) {
        section(inflater, R.string.settings_section_display, R.drawable.ic_section_display) {
            readOnlyRow(
                title = getString(R.string.setting_display_detected),
                detail = getString(R.string.setting_display_detected_detail),
                value = displayLabel(),
            )

            val needsRuntime = getString(R.string.setting_needs_runtime)
            val plan = currentPlan()

            optionRow(
                title = getString(R.string.setting_display_resolution),
                detail = joinDetail(
                    getString(R.string.setting_display_resolution_detail),
                    needsRuntime,
                ),
                options = resolutionOptions(),
                selected = settings.displayResolution,
                onSelected = {
                    settings.displayResolution = it
                    recreate()
                },
            )

            if (settings.displayResolution.isCustom) {
                optionRow(
                    title = getString(R.string.setting_custom_resolution),
                    detail = joinDetail(
                        getString(
                            R.string.setting_custom_resolution_detail,
                            RenderResolution.MIN_WIDTH,
                            RenderResolution.MAX_WIDTH,
                            RenderResolution.MIN_HEIGHT,
                            RenderResolution.MAX_HEIGHT,
                        ),
                        needsRuntime,
                    ),
                    options = emptyList(),
                    selected = Unit,
                    onSelected = {},
                    valueOverride = "${plan.presented}",
                    onTapped = { showCustomResolutionDialog() },
                )
            }

            optionRow(
                title = getString(R.string.setting_render_scale),
                detail = joinDetail(
                    getString(R.string.setting_render_scale_detail),
                    needsRuntime,
                ),
                options = ResolutionScale.PRESETS.map { scale ->
                    Option("${scale.percent}%", scale)
                } + Option(getString(R.string.value_custom), ResolutionScale.CUSTOM),
                selected = settings.renderScale,
                onSelected = {
                    settings.renderScale = it
                    recreate()
                },
            )

            if (settings.renderScale.isCustom) {
                optionRow(
                    title = getString(R.string.setting_custom_scale),
                    detail = joinDetail(
                        getString(
                            R.string.setting_custom_scale_detail,
                            ResolutionScale.MIN_PERCENT,
                            ResolutionScale.MAX_PERCENT,
                        ),
                        needsRuntime,
                    ),
                    options = emptyList(),
                    selected = Unit,
                    onSelected = {},
                    valueOverride = "${settings.customScalePercent}%",
                    onTapped = { showCustomScaleDialog() },
                )
            }

            readOnlyRow(
                title = getString(R.string.setting_render_plan),
                detail = joinDetail(
                    getString(R.string.setting_render_plan_detail),
                    if (plan.reducedToFit) {
                        getString(R.string.display_reduced_suffix, "${plan.presented}")
                    } else {
                        ""
                    },
                ),
                value = getString(
                    R.string.value_render_plan,
                    "${plan.presented}",
                    "${plan.rendered}",
                ),
            )

            optionRow(
                title = getString(R.string.setting_aspect_mode),
                detail = getString(R.string.setting_aspect_mode_detail),
                enabled = false,
                reason = needsRuntime,
                options = listOf(
                    option(R.string.value_preserve, AspectMode.PRESERVE),
                    option(R.string.value_fit, AspectMode.FIT),
                    option(R.string.value_stretch, AspectMode.STRETCH),
                ),
                selected = settings.aspectMode,
                onSelected = { settings.aspectMode = it },
            )
            switchRow(
                title = getString(R.string.setting_letterbox),
                detail = getString(R.string.setting_letterbox_detail),
                enabled = false,
                reason = needsRuntime,
                checked = settings.letterboxEnabled,
                onChanged = { settings.letterboxEnabled = it },
            )
            switchRow(
                title = getString(R.string.setting_fullscreen_scaling),
                detail = getString(R.string.setting_fullscreen_scaling_detail),
                enabled = false,
                reason = needsRuntime,
                checked = settings.fullscreenScaling,
                onChanged = { settings.fullscreenScaling = it },
            )

            // The one row here that does something right now.
            actionRow(
                title = getString(R.string.setting_reset_graphics),
                detail = getString(R.string.setting_reset_graphics_detail),
            ) {
                settings.resetGraphics()
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_graphics_reset_done,
                    Toast.LENGTH_SHORT,
                ).show()
                recreate()
            }
        }
    }

    // --------------------------------------------------------- 3 performance

    private fun performanceSection(inflater: LayoutInflater) {
        section(
            inflater,
            R.string.settings_section_performance,
            R.drawable.ic_section_performance,
        ) {
            val needsRuntime = getString(R.string.setting_needs_runtime)

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
                reason = needsRuntime,
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
                reason = needsRuntime,
                checked = settings.vsyncEnabled,
                onChanged = { settings.vsyncEnabled = it },
            )
            switchRow(
                title = getString(R.string.setting_frame_pacing),
                detail = getString(R.string.setting_frame_pacing_detail),
                enabled = false,
                reason = needsRuntime,
                checked = settings.framePacingEnabled,
                onChanged = { settings.framePacingEnabled = it },
            )
            optionRow(
                title = getString(R.string.setting_performance_preset),
                detail = getString(R.string.setting_performance_preset_detail),
                enabled = false,
                reason = needsRuntime,
                options = listOf(
                    option(R.string.value_preset_safe, PerformancePreset.SAFE),
                    option(R.string.value_preset_balanced, PerformancePreset.BALANCED),
                    option(R.string.value_preset_aggressive, PerformancePreset.AGGRESSIVE),
                ),
                selected = settings.performancePreset,
                onSelected = { settings.performancePreset = it },
            )
            optionRow(
                title = getString(R.string.setting_renderer),
                detail = getString(R.string.setting_renderer_detail),
                enabled = false,
                reason = needsRuntime,
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
    }

    // -------------------------------------------------------------- 4 audio

    private fun audioSection(inflater: LayoutInflater) {
        section(inflater, R.string.settings_section_audio, R.drawable.ic_section_audio) {
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
    }

    // -------------------------------------------------------------- 5 input

    /**
     * Everything the touch HUD will need, listed so the shape of the feature is
     * visible — and greyed out, because none of it exists. Building editors for
     * it now would ship controls wired to nothing.
     */
    private fun inputSection(inflater: LayoutInflater) {
        section(inflater, R.string.settings_section_input, R.drawable.ic_section_input) {
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
    }

    // -------------------------------------------------------- 6 diagnostics

    /** What the device has told us, read from the probe cache — never re-run here. */
    private fun diagnosticsSection(inflater: LayoutInflater) {
        section(
            inflater,
            R.string.settings_section_diagnostics,
            R.drawable.ic_section_diagnostics,
        ) {
            readOnlyRow(
                title = getString(R.string.setting_native_probe),
                detail = getString(R.string.setting_native_probe_detail),
                value = probeSummary(PROBE_NATIVE) { NativeProbe.summary(it) },
            )
            readOnlyRow(
                title = getString(R.string.setting_device_capability),
                detail = getString(R.string.setting_device_capability_detail),
                value = probeSummary(PROBE_RUNTIME) { RuntimeProbe.summary(it) },
            )
            readOnlyRow(
                title = getString(R.string.setting_runtime_version),
                detail = getString(R.string.runtime_overall_detail),
                value = getString(R.string.status_not_integrated),
            )
        }
    }

    private fun probeSummary(key: String, summarise: (String) -> String): String {
        val cached = ProbeCache(this).load(key, BuildConfig.VERSION_NAME)
            ?: return getString(R.string.probe_not_measured)
        return summarise(cached.output).ifBlank { getString(R.string.setting_value_unavailable) }
    }

    // ------------------------------------------------------------- display

    private fun currentPlan() = ResolutionCalculator.plan(
        display = display,
        resolution = settings.displayResolution,
        custom = settings.customDisplaySize(),
        scalePercent = settings.effectiveScalePercent(),
    )

    /**
     * "720 × 1600 · 60 Hz", measured from the device.
     *
     * Formatted by [DisplayInfo.describe] so the launcher and this screen
     * cannot end up showing the same measurement two different ways.
     */
    private fun displayLabel(): String =
        display.describe().ifEmpty { getString(R.string.display_unmeasured) }

    private fun resolutionOptions(): List<Option<RenderResolution>> {
        val native = display.landscapeBounds
        return listOf(
            Option(
                getString(R.string.value_native, native.width, native.height),
                RenderResolution.NATIVE,
            ),
        ) + RenderResolution.PRESETS
            .filter { it != RenderResolution.NATIVE }
            .map { Option("${it.width} × ${it.height}", it) } +
            Option(getString(R.string.value_custom), RenderResolution.CUSTOM)
    }

    // -------------------------------------------------------------- dialogs

    private fun showCustomResolutionDialog() {
        val dialog = DialogCustomResolutionBinding.inflate(layoutInflater)
        dialog.widthInput.setText(settings.customDisplayWidth.toString())
        dialog.heightInput.setText(settings.customDisplayHeight.toString())

        // The positive button is handled by hand: the default behaviour closes
        // the dialog whatever was typed, so an out-of-range value would be
        // clamped into the settings without the user ever seeing the number.
        val alert = AlertDialog.Builder(this)
            .setTitle(R.string.setting_custom_resolution)
            .setView(dialog.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .show()

        alert.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val width = dialog.widthInput.text.toString().toIntOrNull()
            val height = dialog.heightInput.text.toString().toIntOrNull()

            if (width == null || height == null ||
                width !in RenderResolution.MIN_WIDTH..RenderResolution.MAX_WIDTH ||
                height !in RenderResolution.MIN_HEIGHT..RenderResolution.MAX_HEIGHT
            ) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.dialog_number_out_of_range,
                        RenderResolution.MIN_WIDTH,
                        RenderResolution.MAX_WIDTH,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            settings.customDisplayWidth = width
            settings.customDisplayHeight = height
            alert.dismiss()
            recreate()
        }
    }

    private fun showCustomScaleDialog() {
        val dialog = DialogCustomScaleBinding.inflate(layoutInflater)
        dialog.percentLabel.text = getString(
            R.string.dialog_percent,
            ResolutionScale.MIN_PERCENT,
            ResolutionScale.MAX_PERCENT,
        )
        dialog.percentInput.setText(settings.customScalePercent.toString())

        val alert = AlertDialog.Builder(this)
            .setTitle(R.string.setting_custom_scale)
            .setView(dialog.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .show()

        alert.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val percent = dialog.percentInput.text.toString().toIntOrNull()
            if (percent == null ||
                percent !in ResolutionScale.MIN_PERCENT..ResolutionScale.MAX_PERCENT
            ) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.dialog_number_out_of_range,
                        ResolutionScale.MIN_PERCENT,
                        ResolutionScale.MAX_PERCENT,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            settings.customScalePercent = percent
            alert.dismiss()
            recreate()
        }
    }

    // ----------------------------------------------------------------- misc

    /** What the last verified scan concluded, without running a new one. */
    private fun installStatusLabel(): String {
        val metadata = installRepository.cached()
            ?: return getString(R.string.install_status_unknown)
        val install = metadata.install()
            ?: return getString(R.string.install_status_not_found)

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

    // ---------------------------------------------------------------- widgets

    private fun section(
        inflater: LayoutInflater,
        titleRes: Int,
        iconRes: Int,
        build: SectionScope.() -> Unit,
    ) {
        val section = ViewSettingsSectionBinding.inflate(inflater, binding.settingsSections, false)
        section.sectionTitle.text = getString(titleRes)
        section.sectionIcon.setImageResource(iconRes)
        SectionScope(section.sectionItems, inflater).build()
        if (section.sectionItems.childCount > 0) {
            binding.settingsSections.addView(section.root)
        }
    }

    private fun <T> option(labelRes: Int, value: T): Option<T> = Option(getString(labelRes), value)

    private class Option<T>(val label: String, val value: T)

    /**
     * Builds the rows of one panel.
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
            onTapped: (() -> Unit)? = null,
        ) {
            val row = ViewSettingOptionBinding.inflate(inflater, container, false)
            row.settingTitle.text = title
            row.settingDetail.text = joinDetail(detail, reason)
            row.settingValue.text = valueOverride
                ?: options.firstOrNull { it.value == selected }?.label
                ?: options.firstOrNull()?.label
                ?: getString(R.string.setting_value_not_set)

            applyEnabled(row.root, enabled && (onTapped != null || options.isNotEmpty()))
            val tap = onTapped ?: {
                showChoiceDialog(title, options, selected, onSelected)
            }
            if (enabled && (onTapped != null || options.isNotEmpty())) {
                row.root.setOnClickListener { tap() }
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
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        /** Ask the launcher to verify the game files again. */
        const val EXTRA_RESCAN = "extra_rescan"

        /** Ask the launcher to forget the cached result and scan again. */
        const val EXTRA_CLEAR_CACHE = "extra_clear_cache"

        private val BRIGHTNESS_STEPS = listOf(25, 50, 75, 100)

        /** Cache keys of the two bundled probes, shared with the launcher. */
        const val PROBE_NATIVE = "native"
        const val PROBE_RUNTIME = "runtime"
    }
}
