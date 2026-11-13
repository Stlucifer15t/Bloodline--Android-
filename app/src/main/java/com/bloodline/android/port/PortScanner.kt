package com.bloodline.android.port

import android.content.Context
import java.io.File

/**
 * Finds the native port's game folder on the device.
 *
 * Different from `game/GameInstallScanner`, which looks for the Wine-style
 * `Vampire.exe` + `Vampire/` pair. This looks for what the port actually
 * installs — `Bin/engine.dll` beside `Vampire/` — and it looks in the places a
 * port install ends up: the shared Download folder, and a location the user
 * picked and this app remembered.
 *
 * Read-only, like the rest of the detection in this project: nothing here
 * creates, moves or modifies a game file.
 */
class PortScanner(
    private val roots: List<File>,
    private val maxDepth: Int = 2,
) {

    /** One found installation. */
    data class Found(
        val layout: PortLayout,
        val displayPath: String,
        val hasPatch: Boolean,
        val hasSettings: Boolean,
    )

    /**
     * The first plausible install under any root.
     *
     * "Plausible" means `Bin/engine.dll` exists and a `Vampire/` folder sits
     * beside it. That pair is what the port needs to start; `port_settings.txt`
     * is not required, because the port writes it on its own first run and an
     * install that has never been launched is still an install.
     */
    fun find(): Found? {
        for (root in roots) {
            if (!root.isDirectory) continue
            found(root, depth = 0)?.let { return it }
        }
        return null
    }

    private fun found(directory: File, depth: Int): Found? {
        if (depth > maxDepth) return null
        if (isInstall(directory)) return describe(directory)

        val children = runCatching { directory.listFiles() }.getOrNull() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            // Hidden folders and Android's own tree are noise, and walking
            // `Android/data` would find every other app's cache.
            if (child.name.startsWith(".")) continue
            if (child.name == "Android") continue
            found(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isInstall(directory: File): Boolean =
        File(directory, "Bin/engine.dll").isFile && File(directory, "Vampire").isDirectory

    private fun describe(directory: File): Found = Found(
        layout = PortLayout(directory.absolutePath),
        displayPath = directory.absolutePath,
        hasPatch = File(directory, "Unofficial_Patch").isDirectory,
        hasSettings = File(directory, "overlay/port_settings.txt").isFile,
    )

    companion object {

        /** The roots a first-run scan inspects, in priority order. */
        fun defaultRoots(context: Context): List<File> = buildList {
            // The shared storage the port's own installer writes into.
            @Suppress("DEPRECATION")
            android.os.Environment.getExternalStorageDirectory()?.let { shared ->
                // A picked folder is remembered separately; these are the
                // places a fresh install typically lands.
                PortLayout.COMMON_FOLDER_NAMES.forEach { name ->
                    add(File(shared, "Download/$name"))
                }
                add(File(shared, "Download"))
                add(shared)
            }
            // App-specific storage, in case the user copied it there.
            context.getExternalFilesDir(null)?.let { add(it) }
        }
    }
}

/**
 * Runs a "tune this installation" attempt end to end.
 *
 * This is the object the screen calls, and it is deliberately the only place
 * that sequences the pieces: read the live settings, decide the preset from the
 * device, write, and report exactly what changed. It holds no Android types, so
 * the whole flow can be driven in a test with a temporary folder.
 */
class PortTuner(
    private val store: PortSettingsStore,
) {

    /** What a tuning attempt did. */
    sealed interface Outcome {
        /** Settings were changed. [applied] is the key/value list actually written. */
        data class Applied(
            val preset: PortPreset,
            val applied: Map<String, String>,
            val previous: PortSettingsSnapshot?,
        ) : Outcome

        /** Nothing changed; [reason] explains why. */
        data class Refused(val reason: String) : Outcome
    }

    /** Applies [preset] to the port's settings file. */
    fun apply(preset: PortPreset): Outcome {
        if (!store.exists()) {
            return Outcome.Refused(
                "The port's settings file is not there yet. Start the game once so it " +
                    "writes its own settings, then come back.",
            )
        }

        val previous = store.snapshot()
        val changes = preset.changes()

        return when (val result = store.apply(changes)) {
            is PortSettingsStore.Result.Written -> Outcome.Applied(preset, changes, previous)
            is PortSettingsStore.Result.Failed -> Outcome.Refused(result.reason)
        }
    }

    /** Puts the settings back the way they were before this app touched them. */
    fun restore(): PortSettingsStore.Result = store.restore()

    /** The live settings, for the screen to show. */
    fun current(): PortSettingsSnapshot? = store.snapshot()

    /** True when there is something to restore. */
    fun canRestore(): Boolean = store.hasBackup()
}
