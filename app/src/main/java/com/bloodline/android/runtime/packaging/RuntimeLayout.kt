package com.bloodline.android.runtime.packaging

import com.bloodline.android.runtime.WineFlavour
import com.bloodline.android.runtime.audio.AudioConfiguration
import com.bloodline.android.runtime.native.ElfInspector
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
            // `bin/wine` is the documented entry point, and it is preferred
            // deliberately. A Wine package also carries the real ELF at
            // `lib/wine/<arch>-unix/wine`, and a walk that finds that first
            // produces a launcher that works by accident while every path
            // around it - PATH, the bin directory, where wineserver is looked
            // for - points somewhere else.
            NAMES_WINE_LOADER.firstNotNullOfOrNull { root.resolve(it).takeIf { file -> file.isFile } }
                ?: NAMES_WINE_LOADER.firstNotNullOfOrNull { root.find(it) }
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

    /**
     * The Wine build's `windows` directory, where Wine keeps its own Windows
     * system files.
     *
     * Searched for, because where a package puts it is not something we are
     * told: a Wine built for Android may carry it at the root, under
     * `share/wine`, or nowhere at all if the build was stripped.
     */
    fun windowsDirectory(flavour: WineFlavour = WineFlavour.DEFAULT): File? =
        windowsDirectory(flavour, prefer32Bit = false)

    /**
     * A Windows program shipped inside the Wine build itself.
     *
     * These are real Windows executables, built by Wine and distributed under
     * its licence, which makes them the honest answer to "can this runtime run
     * a Windows program at all": no third-party download, no question of
     * redistribution, and no doubt about what the file is.
     *
     * @param prefer32Bit look under `syswow64` first. WoW64 keeps the 32-bit
     *        Windows system files there, so that is where a 32-bit guest
     *        binary lives — and a 32-bit guest is what this whole runtime
     *        exists to run.
     * @return the executable, or null when the build does not carry one.
     */
    /**
     * Where Wine keeps the Windows system files of one bitness.
     *
     * Observed, not assumed. Reading a real Wine 11.3 ARM64EC package showed
     * the layout that had been guessed at was wrong: there is no `windows`
     * directory at all. Wine puts its Windows system files under
     * `lib/wine/`, split by the architecture they were built for:
     *
     * * `lib/wine/i386-windows` — 32-bit x86, which is what the game is
     * * `lib/wine/aarch64-windows` — 64-bit ARM
     *
     * The 32-bit one matters more than it sounds: it is where a 32-bit Windows
     * program that ships with Wine lives, and one of those is what the WoW64
     * stage runs to prove the 32-bit path works.
     */
    fun windowsDirectory(
        flavour: WineFlavour = WineFlavour.DEFAULT,
        prefer32Bit: Boolean = false,
    ): File? {
        val root = wineRoot(flavour) ?: return null
        val order = if (prefer32Bit) NAMES_WINDOWS_32 + NAMES_WINDOWS_64 else NAMES_WINDOWS_64 + NAMES_WINDOWS_32
        return order.firstNotNullOfOrNull { root.resolve(it).takeIf { dir -> dir.isDirectory } }
    }

    fun windowsExecutable(
        name: String,
        flavour: WineFlavour = WineFlavour.DEFAULT,
        prefer32Bit: Boolean = true,
    ): File? {
        val order = if (prefer32Bit) NAMES_WINDOWS_32 + NAMES_WINDOWS_64 else NAMES_WINDOWS_64 + NAMES_WINDOWS_32
        val root = wineRoot(flavour) ?: return null
        return order.firstNotNullOfOrNull { path ->
            File(root, path).takeIf { it.isDirectory }?.find(name)
        }
    }

    /**
     * What the Wine loader actually is, read from its ELF header.
     *
     * Result of a lesson learned the hard way elsewhere in this project: a
     * binary that is present and executable is not necessarily a binary that
     * will run. An x86_64 build on an ARM64 device, or one linked against
     * glibc on a device with no glibc, unpacks perfectly and then fails with
     * a message that names the wrong thing.
     */
    fun inspectWine(flavour: WineFlavour = WineFlavour.DEFAULT): ElfInspector.Result? =
        wineLoader(flavour)?.let { ElfInspector.inspect(it) }

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

        /**
         * `bin/wine` is the launcher; `wine64` appears in some builds. The bare
         * names are fallbacks for a build that does not use a `bin` directory.
         */
        val NAMES_WINE_LOADER = listOf("bin/wine", "bin/wine64", "wine", "wine64")

        val NAMES_WINE_LIB = listOf("lib", "lib64", "lib/wine", "lib64/wine")

        val NAMES_EMULATOR = listOf("box64")

        /**
         * Where a Wine build keeps its 32-bit Windows system files.
         *
         * In the order observed in a real Wine 11.3 ARM64EC package, with the
         * conventional locations after it in case another build differs.
         */
        val NAMES_WINDOWS_32 = listOf(
            "lib/wine/i386-windows",
            "share/wine/windows/syswow64",
            "windows/syswow64",
            "syswow64",
        )

        /** Where a Wine build keeps its 64-bit Windows system files. */
        val NAMES_WINDOWS_64 = listOf(
            "lib/wine/aarch64-windows",
            "lib/wine/x86_64-windows",
            "share/wine/windows/system32",
            "windows/system32",
            "system32",
        )

        /**
         * Deep enough for `lib/wine/x86_64-windows/...`, shallow enough that a
         * symlink loop or an unexpectedly deep tree cannot hang the launch.
         */
        const val MAX_DEPTH = 6
    }
}
