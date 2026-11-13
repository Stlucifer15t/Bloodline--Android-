package com.bloodline.android.port

/**
 * Changes the port's settings while keeping a way back.
 *
 * The port rewrites `port_settings.txt` itself, so editing it is only half the
 * job: the other half is knowing what it looked like before, and putting that
 * back when the change is wrong.
 *
 * It works on a [PortFile] rather than a path because of a measured platform
 * fact: on Android 14 a plain write into shared storage is refused even with
 * `WRITE_EXTERNAL_STORAGE` granted, so the only way to touch the port's file is
 * through the Storage Access Framework. Everything below is the same either
 * way, which is why the abstraction is here and not in the caller.
 *
 * The backup is written **beside** the settings, as a sibling document, when the
 * backing store allows it; otherwise it is kept in app storage as text. Losing
 * the backup is the one failure that makes "restore" a lie, so a write is
 * refused rather than proceeding without one.
 */
class PortSettingsStore(
    /** The live settings file. */
    private val settings: PortFile,

    /** Where the previous contents are kept. */
    private val backup: PortFile,
) {

    /** What a write did. */
    sealed interface Result {
        data object Written : Result
        data class Failed(val reason: String) : Result
    }

    /** True when the port's settings file is present. */
    fun exists(): Boolean = settings.exists()

    /** Reads the current settings, or null when the file is absent or unreadable. */
    fun read(): PortSettings? = settings.read()?.let(PortSettings::parse)

    /** The settings as a typed snapshot, or null when nothing could be read. */
    fun snapshot(): PortSettingsSnapshot? = read()?.snapshot()

    /**
     * Applies [changes] and records the previous contents first.
     *
     * The backup is only taken when one does not already contain settings, so
     * the first change of a session is what gets remembered. Overwriting it on
     * every write would make "restore" mean "undo one step", which is not what
     * anyone wants from a settings screen.
     */
    fun apply(changes: Map<String, String>): Result {
        if (!settings.exists()) {
            return Result.Failed(
                "The port's settings file was not found (${settings.describe()}). " +
                    "Start the game once so it writes its own settings, then try again.",
            )
        }

        val original = when (val read = settings.readWithReason()) {
            is PortFile.Result.Ok -> read.value
            is PortFile.Result.Err -> return Result.Failed(read.reason)
        }

        if (backup.read().isNullOrBlank()) {
            when (val written = backup.write(original)) {
                is PortFile.Result.Ok -> Unit
                is PortFile.Result.Err -> return Result.Failed(
                    "A backup could not be written, so nothing was changed: ${written.reason}",
                )
            }
        }

        val updated = PortSettings.parse(original)
        changes.forEach { (key, value) -> updated.set(key, value) }

        return when (val written = settings.write(updated.render())) {
            is PortFile.Result.Ok -> Result.Written
            is PortFile.Result.Err -> Result.Failed(written.reason)
        }
    }

    /** Puts the backup back, and reports whether there was one. */
    fun restore(): Result {
        val text = backup.read()
        if (text.isNullOrBlank()) {
            return Result.Failed("There is no backup to restore; nothing has been changed yet.")
        }
        return when (val written = settings.write(text)) {
            is PortFile.Result.Ok -> Result.Written
            is PortFile.Result.Err -> Result.Failed(written.reason)
        }
    }

    /** True when [restore] would do something. */
    fun hasBackup(): Boolean = !backup.read().isNullOrBlank()

    /** When the backup was written, or 0 when there is none. */
    fun backupTimeMillis(): Long = backup.lastModified()

    companion object {

        /**
         * A store for a settings file reached as a plain path.
         *
         * Only correct for app storage. Shared storage needs the SAF
         * implementation, because a path write there is refused on modern
         * Android even when `WRITE_EXTERNAL_STORAGE` reports as granted.
         */
        fun forPath(settings: java.io.File, backup: java.io.File): PortSettingsStore =
            PortSettingsStore(FilePortFile(settings), FilePortFile(backup))

        /** A store over any two [PortFile]s. */
        fun of(settings: PortFile, backup: PortFile): PortSettingsStore =
            PortSettingsStore(settings, backup)
    }
}
