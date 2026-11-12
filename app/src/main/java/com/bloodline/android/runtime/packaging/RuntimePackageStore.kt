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
    fun wineRoot(): File = packageDir(RuntimePackageCatalog.WINE_10)

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
