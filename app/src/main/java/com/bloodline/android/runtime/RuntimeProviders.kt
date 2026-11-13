package com.bloodline.android.runtime

import android.content.Context
import android.os.Build
import com.bloodline.android.runtime.packaging.RuntimeAcquisition
import com.bloodline.android.runtime.packaging.RuntimeLayout
import com.bloodline.android.runtime.packaging.RuntimePackageManager
import com.bloodline.android.runtime.packaging.RuntimePackageStore
import com.bloodline.android.runtime.process.ProcessRunner
import java.io.File

/**
 * Builds the runtime pieces that need an Android [Context].
 *
 * This is the *only* place in the runtime that touches Android. Everything it
 * produces is then free of Android types, which is what lets the launch
 * decision be unit tested on the JVM — the one kind of testing available for
 * code that cannot run on a device yet.
 */
object RuntimeProviders {

    /** The directory the app owns, where every runtime file lives. */
    fun runtimeRoot(context: Context): File =
        File(context.filesDir, "runtime").apply { mkdirs() }

    /** The primary ABI of this process, e.g. `arm64-v8a`. */
    fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    /** The provider that acquires upstream packages on first run. */
    fun packaged(context: Context): PackagedRuntimeProvider {
        val store = RuntimePackageStore(runtimeRoot(context))
        return PackagedRuntimeProvider(
            store = store,
            manager = RuntimePackageManager(store),
            layout = RuntimeLayout(store),
            deviceAbi = deviceAbi(),
        )
    }

    /** The launcher wired to the same store as [packaged]. */
    fun launcher(context: Context): RuntimeLauncher {
        val store = RuntimePackageStore(runtimeRoot(context))
        return RuntimeLauncher(store = store, layout = RuntimeLayout(store))
    }

    /** Acquires the packages the runtime needs, for the first-run download. */
    fun acquisition(context: Context): RuntimeAcquisition {
        val store = RuntimePackageStore(runtimeRoot(context))
        return RuntimeAcquisition(RuntimePackageManager(store), deviceAbi())
    }

    /** Starts real processes. */
    fun processRunner(): ProcessRunner = ProcessRunner()

    /** Brings Wine up, wired to the same store as [launcher]. */
    fun initialiser(context: Context): WineInitialiser {
        val store = RuntimePackageStore(runtimeRoot(context))
        return WineInitialiser(
            runner = processRunner(),
            layout = RuntimeLayout(store),
            store = store,
        )
    }

    /** A [RuntimeManager] wired to the real provider, launcher and Wine. */
    fun manager(
        context: Context,
        configuration: RuntimeConfiguration = RuntimeConfiguration(),
        onOutput: (String) -> Unit = {},
        onGameOutput: (String) -> Unit = {},
    ): RuntimeManager = RuntimeManager(
        configuration = configuration,
        provider = packaged(context),
        launcher = launcher(context),
        initialiser = initialiser(context),
        runner = processRunner(),
        onOutput = onOutput,
        onGameOutput = onGameOutput,
    )
}
