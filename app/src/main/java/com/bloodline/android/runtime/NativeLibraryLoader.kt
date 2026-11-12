package com.bloodline.android.runtime

import java.io.File

/**
 * Loads the native libraries this app ships, and says which ones loaded.
 *
 * Why this exists separately from launching the game: the libraries here are
 * **ours** — the device probes, and later the graphics wrapper — and they are
 * loaded into *this* process with `dlopen`. The Wine and Box64 libraries are
 * not: they belong to the guest process and are loaded by its own dynamic
 * linker. Conflating the two is how a project ends up trying to `System.load`
 * a Wine build compiled for a different libc.
 *
 * Two Android specifics are worth recording, because they are the reason
 * loading is fussy:
 *
 * 1. A `.so` must live in a directory the platform will execute from —
 *    normally the app's native library directory. Files under the app's data
 *    directory are mounted no-exec.
 * 2. Order matters. A library that depends on another has to be loaded after
 *    it, and `dlopen` will not resolve the dependency for us the way a process
 *    launch would.
 *
 * The loader function is injected so the ordering and the reporting can be
 * tested on the JVM, where `System.load` does nothing useful.
 */
class NativeLibraryLoader(
    private val load: (String) -> Unit = { System.load(it) },
) {

    /** What one library did. */
    sealed interface Outcome {
        data class Loaded(val name: String) : Outcome
        data class Failed(val name: String, val reason: String) : Outcome
        data class Skipped(val name: String, val reason: String) : Outcome
    }

    /** Everything that happened on the last call to [loadAll]. */
    private val outcomes = mutableListOf<Outcome>()

    /**
     * Loads [libraries] in the order given.
     *
     * A failure does not abort the rest: knowing that two of three libraries
     * loaded is more useful than knowing that one of them did not.
     *
     * @return the outcome for every library, in order.
     */
    fun loadAll(libraries: List<File>): List<Outcome> {
        outcomes.clear()
        libraries.forEach { library ->
            outcomes += loadOne(library)
        }
        return outcomes.toList()
    }

    /** The libraries that loaded, for the diagnostics report. */
    fun loaded(): List<String> = outcomes.mapNotNull { (it as? Outcome.Loaded)?.name }

    /** The libraries that did not, each with the reason. */
    fun problems(): List<String> = outcomes.mapNotNull {
        when (it) {
            is Outcome.Failed -> "${it.name}: ${it.reason}"
            is Outcome.Skipped -> "${it.name}: ${it.reason}"
            is Outcome.Loaded -> null
        }
    }

    private fun loadOne(library: File): Outcome {
        if (!library.exists()) {
            return Outcome.Skipped(library.name, "the file is not present")
        }
        if (!library.canRead()) {
            return Outcome.Skipped(library.name, "the file cannot be read")
        }

        return try {
            load(library.absolutePath)
            Outcome.Loaded(library.name)
        } catch (error: UnsatisfiedLinkError) {
            Outcome.Failed(library.name, error.message ?: "the linker refused it")
        } catch (error: SecurityException) {
            Outcome.Failed(library.name, error.message ?: "the platform refused it")
        }
    }

    companion object {

        /**
         * The libraries the launcher needs, in dependency order.
         *
         * The probes ship as `lib*.so` because Android only extracts — and
         * permits executing — files that look like shared libraries, which is
         * also what makes them loadable from the app's native library dir.
         */
        val SHIPPED: List<String> = listOf(
            "libnative_probe.so",
            "libruntime_probe.so",
            "libgraphics_probe.so",
            "libprocess_probe.so",
        )

        /** Resolves [SHIPPED] against the app's native library directory. */
        fun shipped(nativeLibraryDir: File): List<File> = SHIPPED.map { File(nativeLibraryDir, it) }
    }
}
