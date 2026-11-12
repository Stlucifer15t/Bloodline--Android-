package com.bloodline.android.runtime

import com.bloodline.android.runtime.packaging.RuntimePackage
import com.bloodline.android.runtime.packaging.RuntimePackageCatalog
import com.bloodline.android.runtime.packaging.RuntimeLayout
import com.bloodline.android.runtime.packaging.RuntimePackageManager
import com.bloodline.android.runtime.packaging.RuntimePackageStore

/**
 * Where the runtime's binaries come from.
 *
 * The launcher asks this one question — "do you have what the game needs?" —
 * and never where the files came from. That indirection is the point: the
 * acquisition mechanism can change (download now, a user-supplied archive
 * later, a bundled build in some future release) without the launch gate
 * changing, and a test can answer "yes" with a directory of empty files.
 *
 * It also keeps the licensing rule in one place. A provider is the only thing
 * allowed to fetch a component, so "do not bundle what you cannot
 * redistribute" is enforced by there being exactly one way in.
 */
interface RuntimeProvider {

    /** Stable name, for the report. */
    val id: String

    /** The packages this provider needs, in the order it would acquire them. */
    fun requiredPackages(): List<RuntimePackage>

    /** What is missing, phrased for a person. Empty when ready. */
    fun missing(): List<String>

    /** True when every required package is installed. */
    fun isInstalled(): Boolean = missing().isEmpty()

    /** True when the installed files actually resolve to executables. */
    fun isUsable(flavour: WineFlavour): Boolean
}

/**
 * The provider that downloads upstream packages on first run.
 *
 * Deliberately the only implementation: runtime components are fetched, not
 * bundled, because bundling hundreds of megabytes of third-party binaries we
 * cannot verify is worse than asking once — and for the one component found
 * during discovery with **no declared licence** (the Bifrost PanVK driver),
 * it is not permitted at all.
 */
class PackagedRuntimeProvider(
    private val store: RuntimePackageStore,
    private val manager: RuntimePackageManager,
    private val layout: RuntimeLayout = RuntimeLayout(store),
    private val deviceAbi: String = "arm64-v8a",
) : RuntimeProvider {

    override val id: String = "packaged"

    override fun requiredPackages(): List<RuntimePackage> =
        RuntimePackageCatalog.requiredFor(RuntimePackageCatalog.WINE_ARM64EC)

    override fun missing(): List<String> {
        if (!deviceAbi.startsWith("arm64")) {
            return listOf("This device reports $deviceAbi; every runtime package is built for arm64.")
        }

        return requiredPackages()
            .filter { manager.stateOf(it) !is RuntimePackageManager.State.Installed }
            .map { "${it.name} ${it.version} is not installed." }
    }

    override fun isUsable(flavour: WineFlavour): Boolean =
        isInstalled() && layout.isComplete(flavour)

    /** The files the launch needs, once everything is installed. */
    fun layout(): RuntimeLayout = layout
}

/**
 * A provider for tests and for diagnostics: answers from a list of things it
 * has been told are present, with no filesystem involved.
 *
 * Worth having because the launch gate is the one piece of logic that must be
 * provably honest, and it can only be tested thoroughly if "installed" can be
 * faked.
 */
class StaticRuntimeProvider(
    private val installed: Set<String>,
    private val missingFiles: List<String> = emptyList(),
) : RuntimeProvider {

    override val id: String = "static"

    override fun requiredPackages(): List<RuntimePackage> =
        RuntimePackageCatalog.requiredFor(RuntimePackageCatalog.WINE_ARM64EC)

    override fun missing(): List<String> =
        requiredPackages().filter { it.id !in installed }.map { "${it.name} is not installed." }

    override fun isUsable(flavour: WineFlavour): Boolean = isInstalled() && missingFiles.isEmpty()

    /** The reasons the files could not be used, for the report. */
    fun fileProblems(): List<String> = missingFiles
}
