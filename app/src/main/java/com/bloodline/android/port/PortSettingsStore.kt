package com.bloodline.android.port

import java.io.File
import java.io.IOException

/**
 * Changes the port's settings while keeping a way back.
 *
 * The port rewrites `port_settings.txt` itself, so editing it is only half the
 * job: the other half is knowing what it looked like before, and putting that
 * back when the change is wrong. On a phone that means the difference between
 * "try a lower resolution" and "reinstall the game".
 *
 * Every write goes through a temporary file and a rename, because a process
 * killed mid-write would otherwise leave the port with a truncated settings
 * file — and the port treats a missing key as its own default, so a half file
 * silently changes settings nobody asked to change.
 */
class PortSettingsStore(
    private val layout: PortLayout,
    private val backupSuffix: String = ".bloodline-backup",
) {

    /** What a write did. */
    sealed interface Result {
        data object Written : Result
        data class Failed(val reason: String) : Result
    }

    private fun settings(): File = File(layout.settingsFile())
    private fun backup(): File = File(layout.settingsFile() + backupSuffix)

    /** True when the port's settings file is where this layout says it is. */
    fun exists(): Boolean = settings().isFile

    /** Reads the current settings, or null when the file is absent or unreadable. */
    fun read(): PortSettings? = runCatching {
        val file = settings()
        if (!file.isFile) null else PortSettings.parse(file.readText())
    }.getOrNull()

    /** The settings as a typed snapshot, or null when nothing could be read. */
    fun snapshot(): PortSettingsSnapshot? = read()?.snapshot()

    /**
     * Applies [changes] and records the previous contents first.
     *
     * The backup is only taken when one does not already exist, so the first
     * change of a session is what gets remembered. Overwriting it on every
     * write would make "restore" mean "undo one step", which is not what
     * anyone wants from a settings screen.
     */
    fun apply(changes: Map<String, String>, whenMillis: Long = System.currentTimeMillis()): Result {
        val file = settings()
        if (!file.isFile) {
            return Result.Failed(
                "The port's settings file was not found at ${layout.settingsFile()}. " +
                    "Start the game once so it writes its own settings, then try again.",
            )
        }

        val original = try {
            file.readText()
        } catch (error: IOException) {
            return Result.Failed("The settings could not be read: ${error.message}")
        } catch (error: SecurityException) {
            return Result.Failed("Reading the settings was refused by the platform.")
        }

        val backupFile = backup()
        if (!backupFile.exists()) {
            runCatching { backupFile.writeText(original) }
                .onFailure {
                    return Result.Failed(
                        "A backup could not be written, so nothing was changed: ${it.message}",
                    )
                }
        }

        val updated = PortSettings.parse(original)
        changes.forEach { (key, value) -> updated.set(key, value) }

        return writeAtomically(file, updated.render())
    }

    /** Puts the backup back, and reports whether there was one. */
    fun restore(): Result {
        val backupFile = backup()
        if (!backupFile.isFile) {
            return Result.Failed("There is no backup to restore; nothing has been changed yet.")
        }
        val text = runCatching { backupFile.readText() }.getOrNull()
            ?: return Result.Failed("The backup could not be read.")
        return writeAtomically(settings(), text)
    }

    /** True when a backup exists, i.e. when [restore] would do something. */
    fun hasBackup(): Boolean = backup().isFile

    /** When the backup was written, or 0 when there is none. */
    fun backupTimeMillis(): Long = backup().lastModified()

    /**
     * Writes [text] to [file] via a sibling temporary file.
     *
     * Written to the same directory so the rename is on one filesystem and
     * therefore atomic. The temporary file is removed on failure, because a
     * `.tmp` beside the settings is a file the port might one day decide to
     * read.
     */
    private fun writeAtomically(file: File, text: String): Result {
        val temp = File(file.parentFile, "${file.name}.tmp")
        return try {
            file.parentFile?.mkdirs()
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                // Some Android filesystems refuse a rename onto an existing
                // file; deleting first is the fallback, and the window is small
                // because the new contents already exist in `temp`.
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return Result.Failed("The settings could not be replaced.")
                }
            }
            Result.Written
        } catch (error: IOException) {
            temp.delete()
            Result.Failed("The settings could not be written: ${error.message}")
        } catch (error: SecurityException) {
            temp.delete()
            Result.Failed("Writing the settings was refused by the platform.")
        }
    }
}
