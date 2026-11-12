package com.bloodline.android.runtime.packaging

import com.bloodline.android.runtime.WineFlavour
import com.bloodline.android.runtime.audio.AudioConfiguration
import java.io.File

/**
 * Turns installed packages into the concrete files the runtime executes.
 *
 * Everything here **searches** rather than assuming. That is deliberate: the
 * internal layout of a third-party package is not something this project
 * controls or is told, and a hard-coded `bin/wine` that is one directory off
 * produces an error message that names the wrong problem. Searching a handful
 * of plausible locations costs a directory walk once per launch and turns a
 * wrong guess into "Wine's loader was not found in the Wine package".
 *
 * Nothing here executes anything. A path resolved here may still point at a
 * file that cannot run, and the distinction is maintained throughout: finding
 * a binary and running it are different claims.
 */
class RuntimeLayout(private val store: RuntimePackageStore) {

    /**
     * The root of the Wine package [flavour] should use.
     *
     * The flavour decides which package to prefer, because an ARM64EC Wine and
     * an x86_64 one are different builds and running the wrong one produces a
     * confusing failure rather than a clear one.
     */
    fun wineRoot(flavour: WineFlavour): File? {
        val preferred = store.packageDir(
            if (flavour == WineFlavour.X86_64_BOX64) RuntimePackageCatalog.WINE_X86_64
            else RuntimePackageCatalog.WINE_ARM64EC,
        )
        val other = store.packageDir(
            if (flavour == WineFlavour.X86_64_BOX64) RuntimePackageCatalog.WINE_ARM64EC
            else RuntimePackageCatalog.WINE_X86_64,
        )
        return listOf(preferred, other).firstOrNull { it.isDirectory }
    }

    /**
     * The Wine loader, e.g. `<wine>/bin/wine`.
     *
     * Searched for rather than assumed: the package's internal layout is not
     * ours to decide, and a binary one directory away from where we guessed
     * should still be found.
     */
    fun wineLoader(flavour: WineFlavour = WineFlavour.DEFAULT): File? =
        wineRoot(flavour)?.let { root ->
            NAMES_WINE_LOADER.firstNotNullOfOrNull { root.find(it) }
        }

    /** The directory holding the loader, for `PATH`. */
    fun wineBinDir(flavour: WineFlavour = WineFlavour.DEFAULT): File? =
        wineLoader(flavour)?.parentFile
            ?: wineRoot(flavour)?.resolve("bin")?.takeIf { it.isDirectory }

    /** The Wine build's own library directory, if it has one. */
    fun wineLibDir(flavour: WineFlavour = WineFlavour.DEFAULT): File? =
        wineRoot(flavour)?.let { root ->
            NAMES_WINE_LIB.firstNotNullOfOrNull { root.resolve(it).takeIf { f -> f.isDirectory } }
        }

    /**
     * The WoW64 DLL Wine should load, for [flavour].
     *
     * This is the file that decides whether a 32-bit executable can run at
     * all, so its absence is reported rather than glossed over.
     */
    fun wow64Dll(flavour: WineFlavour): File? {
        val name = flavour.wow64DllName
        if (name.isEmpty()) return null

        return store.packageDir(RuntimePackageCatalog.WOWBOX64).find(name)
            ?: store.packageDir(RuntimePackageCatalog.FEX).find(name)
    }

    /**
     * The ALSA PCM plugin library, if the runtime ships one.
     *
     * Searched for rather than assumed, and **not expected to be found**: the
     * plugin this app names in `asound.conf` is ours to write and has not been
     * written. [AudioConfiguration] asks for it on every launch and falls back
     * to ALSA's own `null` device when it is absent, so a null answer here is a
     * supported outcome and not a failure.
     */
    fun alsaPlugin(flavour: WineFlavour = WineFlavour.DEFAULT): File? =
        libDirs(flavour).firstNotNullOfOrNull { it.find(AudioConfiguration.PLUGIN_LIBRARY) }

    /** The standalone Box64 binary, needed only by the x86_64 Wine flavour. */
    fun emulatorBinary(): File? = store.packageDir(RuntimePackageCatalog.BOX64_BIONIC)
        .let { root -> NAMES_EMULATOR.firstNotNullOfOrNull { root.find(it) } }

    /** Library directories the guest needs on its search path, in order. */
    fun libDirs(flavour: WineFlavour = WineFlavour.DEFAULT): List<File> = buildList {
        wineLibDir(flavour)?.let { add(it) }
        store.packageDir(RuntimePackageCatalog.BOX64_BIONIC).takeIf { it.isDirectory }?.let { add(it) }
    }

    /**
     * What is missing for [flavour], phrased for a person.
     *
     * Empty means every file the launch needs was found. It still does not
     * mean any of them will run.
     */
    fun missingFiles(flavour: WineFlavour): List<String> = buildList {
        if (wineLoader(flavour) == null) {
            add("Wine's loader was not found inside the Wine package.")
        }
        if (flavour.usesWow64Layer && wow64Dll(flavour) == null) {
            add(
                "The WoW64 layer ${flavour.wow64DllName} was not found. Without it a " +
                    "32-bit executable has nothing to run against.",
            )
        }
        if (flavour.needsEmulator && emulatorBinary() == null) {
            add("Box64 was not found, and this Wine build needs it in front of it.")
        }
    }

    /** True when every file the launch needs is present. */
    fun isComplete(flavour: WineFlavour): Boolean = missingFiles(flavour).isEmpty()

    /** Where the WoW64 DLL has to end up: the prefix's `windows/system32`. */
    fun wow64TargetDirectory(): File =
        File(store.prefixDir(), "drive_c/windows/system32")

    /** Finds [name] anywhere under [root]. Bounded so a stray link cannot hang us. */
    private fun File.find(name: String): File? {
        if (!isDirectory) return null
        return walkTopDown()
            .maxDepth(MAX_DEPTH)
            .firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }

    private companion object {

        /** `wine` is the ARM64EC/x86_64 loader; `wine64` appears in some builds. */
        val NAMES_WINE_LOADER = listOf("wine", "wine64")

        val NAMES_WINE_LIB = listOf("lib", "lib64", "lib/wine", "lib64/wine")

        val NAMES_EMULATOR = listOf("box64")

        /**
         * Deep enough for `lib/wine/x86_64-windows/...`, shallow enough that a
         * symlink loop or an unexpectedly deep tree cannot hang the launch.
         */
        const val MAX_DEPTH = 6
    }
}
