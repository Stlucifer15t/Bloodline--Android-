package com.bloodline.android.runtime

import com.bloodline.android.runtime.audio.AudioConfiguration
import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import com.bloodline.android.runtime.graphics.GraphicsConfiguration
import com.bloodline.android.runtime.graphics.RegistrySetting
import com.bloodline.android.runtime.packaging.RuntimeLayout
import com.bloodline.android.runtime.packaging.RuntimePackageCatalog
import com.bloodline.android.runtime.packaging.RuntimePackageStore
import java.io.File
import java.io.IOException

/**
 * Assembles the command and environment that start `Vampire.exe`, and the
 * steps that have to happen before it can be started.
 *
 * This is the object that turns a stack of decisions — which Wine flavour,
 * which translator, which graphics backend, which texture route, which audio
 * bridge — into one command line and one environment map. Everything it
 * produces is a value, so the whole of it can be inspected and tested without
 * a device and without executing anything.
 *
 * The distinction this class is careful about, because it is the one that
 * matters most: **building a command is not starting a process, and finding a
 * binary is not running it.** [plan] returns a plan together with everything
 * that is missing; it does not claim the plan will work.
 */
class RuntimeLauncher(
    private val store: RuntimePackageStore,
    private val layout: RuntimeLayout = RuntimeLayout(store),
) {

    /** Everything needed to start the game, or the reasons it cannot be. */
    data class LaunchPlan(

        /** The argument vector. Empty when the plan is not viable. */
        val command: List<String>,

        /** The complete environment for the guest process. */
        val environment: Map<String, String>,

        /** Directory the process should start in: the game's own folder. */
        val workingDirectory: String,

        /** Registry settings to apply to the prefix before the game starts. */
        val registrySettings: List<RegistrySetting>,

        /** What is missing. Empty means the plan is viable. */
        val missing: List<String>,

        /** The decisions the plan was built from, for the report. */
        val flavour: WineFlavour,
        val translator: CpuTranslator,
        val graphics: GraphicsConfiguration,
        val audio: AudioConfiguration,
    ) {
        val isViable: Boolean get() = missing.isEmpty() && command.isNotEmpty()
    }

    /** What preparing the prefix did. */
    sealed interface Preparation {
        data object Ready : Preparation
        data class Failed(val reason: String) : Preparation
    }

    /**
     * Copies the WoW64 layer into the prefix.
     *
     * This is the step that makes a 32-bit executable runnable, and it is easy
     * to miss: Wine looks for the DLL named by `HODLL` in
     * `drive_c/windows/system32`, so a WOWBox64 package unpacked neatly into
     * its own directory does nothing until this copy happens.
     *
     * @return [Preparation.Ready], or [Preparation.Failed] naming the reason.
     */
    fun installWow64Layer(flavour: WineFlavour): Preparation {
        if (!flavour.usesWow64Layer) return Preparation.Ready

        val source = layout.wow64Dll(flavour)
            ?: return Preparation.Failed(
                "The WoW64 layer ${flavour.wow64DllName} was not found in the " +
                    "${RuntimePackageCatalog.WOWBOX64.name} package.",
            )

        val targetDirectory = layout.wow64TargetDirectory()
        if (!targetDirectory.isDirectory && !targetDirectory.mkdirs()) {
            return Preparation.Failed(
                "The prefix's ${targetDirectory.name} directory could not be created.",
            )
        }

        val target = File(targetDirectory, flavour.wow64DllName)
        return try {
            source.copyTo(target, overwrite = true)
            Preparation.Ready
        } catch (error: IOException) {
            Preparation.Failed("Copying ${source.name} into the prefix failed: ${error.message}")
        } catch (error: SecurityException) {
            Preparation.Failed("Copying ${source.name} into the prefix was refused.")
        }
    }

    /** The WoW64 host DLL as Wine will find it, or null when it is not there. */
    fun wow64DllInPrefix(flavour: WineFlavour = WineFlavour.DEFAULT): File? =
        layout.wow64DllInPrefix(flavour)

    /**
     * Maps the game's folder to a drive letter in the prefix.
     *
     * Without this, the launch command points at `D:\Vampire.exe` in a prefix
     * that has no `D:`. This creates the `dosdevices/d:` symlink so Wine can
     * resolve the game.
     *
     * @param gameDirectory absolute Unix path of the folder that holds the game.
     * @return [Preparation.Ready] when the drive is mapped, or a reason when the
     *         prefix is not present yet (nothing was checked, so nothing is claimed).
     */
    fun prepareGameDrive(gameDirectory: File): Preparation {
        val dosDevices = layout.dosDevicesDirectory()
        val prefixExists = dosDevices.isDirectory || dosDevices.parentFile?.isDirectory == true
        if (!prefixExists) {
            return Preparation.Failed(
                "The Wine prefix does not exist yet, so no drive could be mapped.",
            )
        }
        return if (layout.mapGameDrive(gameDirectory) != null) {
            Preparation.Ready
        } else {
            Preparation.Failed("The game folder could not be mapped to a drive letter in the prefix.")
        }
    }

    /**
     * Builds the launch plan.
     *
     * @param validation  the validated installation, which supplies the game
     *                    directory and the executable name.
     */
    fun plan(
        validation: InstallValidation.Valid,
        flavour: WineFlavour = WineFlavour.DEFAULT,
        translator: CpuTranslator = CpuTranslator.recommendedFor(RuntimeCapabilities.unknown()),
        backend: GraphicsBackend,
        capabilities: RuntimeCapabilities,
        audio: AudioBridge = AudioBridge.recommendedFor(capabilities),
        /** `WINEDEBUG`, or null for [WineEnvironment.DEFAULT_WINE_DEBUG]. */
        wineDebug: String? = null,
    ): LaunchPlan {
        val gameDirectory = File(validation.install.executablePath).parentFile.absolutePath
        val executableName = File(validation.install.executablePath).name

        val graphics = GraphicsConfiguration.forBackend(backend, capabilities)
        // The plugin is searched for, not assumed. When it is absent the
        // generated asound.conf names ALSA's null device instead of a plugin
        // that would fail to resolve, so the game gets silence rather than a
        // device that will not open.
        val audioConfiguration = AudioConfiguration(
            bridge = audio,
            plugin = layout.alsaPlugin(flavour),
        )

        val wine = WineEnvironment(
            runtimeRoot = store.root.absolutePath,
            flavour = flavour,
            wineLoader = layout.wineLoader(flavour)?.absolutePath.orEmpty(),
            gameDirectory = gameDirectory,
            executableName = executableName,
            wineDebug = wineDebug ?: WineEnvironment.DEFAULT_WINE_DEBUG,
        )

        val environment = buildMap {
            putAll(wine.environment())
            putAll(
                wine.withRuntimePaths(
                    wineBin = layout.wineBinDir(flavour)?.absolutePath.orEmpty(),
                    libDirs = layout.libDirs(flavour).map { it.absolutePath },
                ),
            )
            putAll(graphics.environment())
            putAll(audioConfiguration.environment(store.root))
        }

        val command = wine.launchCommand(emulatorBinary = layout.emulatorBinary()?.absolutePath)

        val missing = buildList {
            addAll(layout.missingFiles(flavour))
            if (validation.install.executablePath.isBlank()) {
                add("The installation names no executable to start.")
            }
        }

        return LaunchPlan(
            command = if (missing.isEmpty()) command else emptyList(),
            environment = environment,
            workingDirectory = gameDirectory,
            registrySettings = graphics.registrySettings() + audioConfiguration.registrySettings(),
            missing = missing,
            flavour = flavour,
            translator = translator,
            graphics = graphics,
            audio = audioConfiguration,
        )
    }

    /** The `wine reg add` commands that apply [plan]'s registry settings. */
    fun registryCommands(plan: LaunchPlan): List<List<String>> {
        val loader = layout.wineLoader(plan.flavour)?.absolutePath ?: return emptyList()
        return plan.registrySettings.map { it.toCommand(loader) }
    }
}
