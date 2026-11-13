package com.bloodline.android.port

import java.io.File

/**
 * Installs a Vulkan driver package and can put the previous one back.
 *
 * The safety net is the reason this class exists at all. PanVK on Bifrost is
 * non-conformant, and the projects that build it warn that a heavy workload can
 * fault the GPU and, on some devices, panic the kernel. So an install is never
 * just "copy the file": it is copy, remember what was there, and keep a record
 * that survives the app being killed so the next start can undo it.
 *
 * Where the files go is decided by the caller, because the port keeps its own
 * driver directory in private storage. What this class owns is the ordering and
 * the record, both of which are testable against a temporary folder.
 */
class DriverInstaller(
    /** Directory the loader reads drivers from. */
    private val driverDirectory: File,
    /** Where the record of an in-progress install is kept. */
    private val stateFile: File,
) {

    /** What an install did. */
    sealed interface Result {
        data class Installed(val library: File, val metadata: DriverUnpacker.DriverMetadata) : Result
        data class Failed(val reason: String) : Result
    }

    /** The record written before a driver is swapped in. */
    data class PendingInstall(
        val packageId: String,
        val libraryName: String,
        val previousLibrary: String?,
        val startedAtMillis: Long,
    ) {

        fun render(): String = buildString {
            appendLine("package=$packageId")
            appendLine("library=$libraryName")
            appendLine("previous=${previousLibrary.orEmpty()}")
            appendLine("started=$startedAtMillis")
        }

        companion object {
            fun parse(text: String): PendingInstall? {
                val values = text.lines()
                    .mapNotNull { line ->
                        val at = line.indexOf('=')
                        if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
                    }
                    .toMap()
                val id = values["package"] ?: return null
                val library = values["library"] ?: return null
                return PendingInstall(
                    packageId = id,
                    libraryName = library,
                    previousLibrary = values["previous"]?.takeIf { it.isNotBlank() },
                    startedAtMillis = values["started"]?.toLongOrNull() ?: 0L,
                )
            }
        }
    }

    /** True when a previous attempt was started and never confirmed. */
    fun pending(): PendingInstall? = runCatching {
        if (stateFile.isFile) PendingInstall.parse(stateFile.readText()) else null
    }.getOrNull()

    /**
     * Moves an unpacked library into place, remembering the one it replaces.
     *
     * The pending record is written **before** the swap, because the failure
     * this protects against is the process dying between the two. A record that
     * existed already (an earlier unconfirmed attempt) is left alone: it names
     * the library that was original, which is the one worth restoring.
     */
    fun install(
        unpackedLibrary: File,
        metadata: DriverUnpacker.DriverMetadata,
        packageId: String,
        whenMillis: Long = System.currentTimeMillis(),
    ): Result {
        if (!driverDirectory.isDirectory && !driverDirectory.mkdirs()) {
            return Result.Failed("The driver directory could not be created.")
        }

        val target = File(driverDirectory, metadata.libraryName)
        val existing = pending()

        val record = existing ?: PendingInstall(
            packageId = packageId,
            libraryName = metadata.libraryName,
            previousLibrary = keepExisting(target, metadata.libraryName, whenMillis),
            startedAtMillis = whenMillis,
        )

        return try {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(record.render())
            unpackedLibrary.copyTo(target, overwrite = true)
            unpackedLibrary.delete()
            Result.Installed(target, metadata)
        } catch (error: Exception) {
            Result.Failed("The driver could not be installed: ${error.message}")
        }
    }

    /**
     * Confirms the install, so it stops being undoable.
     *
     * Called once the game has started far enough to prove the driver works.
     * Until then the record stays, and [rollback] is what the next start does.
     */
    fun confirm() {
        runCatching { stateFile.delete() }
    }

    /** Puts the previous driver back, or removes the one that was added. */
    fun rollback(): Boolean {
        val record = pending() ?: return false
        val current = File(driverDirectory, record.libraryName)

        if (record.previousLibrary != null) {
            val previous = File(driverDirectory, record.previousLibrary)
            val saved = File(driverDirectory, "${record.previousLibrary}.bloodline-saved")
            return runCatching {
                if (saved.isFile) {
                    saved.copyTo(previous, overwrite = true)
                    saved.delete()
                }
                current.delete()
                stateFile.delete()
                true
            }.getOrDefault(false)
        }

        return runCatching {
            current.delete()
            stateFile.delete()
            true
        }.getOrDefault(false)
    }

    /**
     * Moves an existing driver aside and returns the name it is saved under.
     *
     * Renamed rather than deleted: reverting must restore the *previous*
     * driver, and a name is all the record needs to remember.
     */
    private fun keepExisting(target: File, libraryName: String, whenMillis: Long): String? {
        if (!target.isFile) return null
        val saved = File(driverDirectory, "$libraryName.bloodline-saved-$whenMillis")
        return runCatching {
            if (!target.renameTo(saved)) {
                target.copyTo(saved, overwrite = true)
                target.delete()
            }
            saved.name
        }.getOrNull()
    }
}
