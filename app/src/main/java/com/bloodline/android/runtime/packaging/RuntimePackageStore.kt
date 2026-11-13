package com.bloodline.android.runtime.packaging

import java.io.File

/**
 * Where runtime packages live on disk, and how we know one is installed.
 *
 * Packages are kept under a directory the app owns, entirely separate from the
 * user's game folder. That separation is a hard rule of this project: nothing
 * the runtime writes may land beside the game files, because a failed
 * experiment must not be able to damage an installation the user cannot
 * re-download.
 *
 * An installed package is marked by a small marker file holding its version, so
 * an upgrade can be detected and a half-completed install cannot be mistaken
 * for a working one.
 */
class RuntimePackageStore(
    /** Directory the app owns, e.g. `files/runtime`. */
    val root: File,
) {

    /** The Wine build's own root, once installed. */
    fun wineRoot(): File = packageDir(RuntimePackageCatalog.WINE_ARM64EC)

    /** The emulator's root. */
    fun emulatorRoot(): File = packageDir(RuntimePackageCatalog.BOX64_BIONIC)

    /** Where the WoW64 layer is unpacked. */
    fun wow64Root(): File = packageDir(RuntimePackageCatalog.WOWBOX64)

    /** Where a package is unpacked: [RuntimePackage.target], under our root. */
    fun packageDir(pack: RuntimePackage): File = File(root, pack.target)

    /** Staging area for downloads, so a partial file is never mistaken for a package. */
    fun stagingDir(): File = File(root, "staging").apply { mkdirs() }

    /** The file a download is written to before it is verified. */
    fun stagedFile(pack: RuntimePackage): File =
        File(stagingDir(), "${pack.id}-${pack.version}.download")

    /** Prefix directory Wine keeps its virtual Windows in. */
    fun prefixDir(): File = File(root, "prefix")

    /**
     * Record of the last successful Wine initialisation.
     *
     * `wine --version`, `wineboot`, every registry write and the 32-bit test
     * each cost a Wine process on a phone; on a mid-range device that is
     * minutes, every single launch. The prefix does not change between
     * launches, so the record is what lets a repeat launch skip straight to
     * running the game.
     *
     * The record names the Wine package version and the prefix's identity, so
     * a re-downloaded Wine or a deleted prefix invalidates it by itself rather
     * than by anyone remembering to call [clear].
     */
    fun initialisationMarker(): File = File(root, "prefix/.initialised")

    /**
     * The fingerprint that the initialisation record is valid for.
     *
     * Covers what the skipped stages actually depended on: the Wine build, the
     * WoW64 layer, and the prefix itself. Anything else changing (graphics
     * settings, for instance) does not invalidate the prefix.
     */
    fun initialisationFingerprint(flavour: String): String = buildList {
        add("wine=${stateDescription(RuntimePackageCatalog.WINE_ARM64EC)}")
        add("wow64=${stateDescription(RuntimePackageCatalog.WOWBOX64)}")
        add("flavour=$flavour")
        add("prefix=${prefixDir().isDirectory}")
    }.joinToString(";")

    private fun stateDescription(pack: RuntimePackage): String =
        when (val state = stateOf(pack)) {
            is InstallState.Installed -> "${pack.id}/${state.version}"
            is InstallState.Stale -> "${pack.id}/${state.installed}->${state.expected}"
            InstallState.Missing -> "${pack.id}/missing"
        }

    /** Every marker file, whatever package it belongs to. */
    private fun marker(pack: RuntimePackage): File =
        File(packageDir(pack), ".installed")

    /** The recorded state of one package. */
    sealed interface InstallState {
        data class Installed(val version: String, val installedAtMillis: Long) : InstallState
        data object Missing : InstallState
        data class Stale(val installed: String, val expected: String) : InstallState
    }

    fun stateOf(pack: RuntimePackage): InstallState {
        val file = marker(pack)
        if (!file.isFile) return InstallState.Missing

        val lines = runCatching { file.readLines() }.getOrNull() ?: return InstallState.Missing
        val version = lines.firstOrNull()?.trim().orEmpty()
        val whenMillis = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L

        if (version.isEmpty()) return InstallState.Missing
        if (version != pack.version) {
            return InstallState.Stale(installed = version, expected = pack.version)
        }
        return InstallState.Installed(version, whenMillis)
    }

    /** Records a successful install. Called only after extraction succeeds. */
    fun markInstalled(pack: RuntimePackage, whenMillis: Long) {
        val directory = packageDir(pack)
        directory.mkdirs()
        File(directory, ".installed").writeText("${pack.version}\n$whenMillis\n")
    }

    /** Forgets a package and deletes its files. */
    fun remove(pack: RuntimePackage) {
        packageDir(pack).deleteRecursively()
        stagedFile(pack).delete()
    }

    /** Total bytes the installed packages occupy, for the diagnostics report. */
    fun installedBytes(): Long = File(root, "packages").walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
}
