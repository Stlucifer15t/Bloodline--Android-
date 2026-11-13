package com.bloodline.android.port

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * A settings file reached as a plain path.
 *
 * Used for anything under app storage, where a normal write is permitted. It is
 * deliberately not used for `/sdcard`: a measured Android 14 device accepts the
 * permission and still refuses the write, which is why [SafPortFile] exists.
 */
class FilePortFile(private val file: File) : PortFile {

    override fun read(): String? = runCatching {
        if (file.isFile) file.readText() else null
    }.getOrNull()

    override fun readWithReason(): PortFile.Result<String> = try {
        if (!file.isFile) {
            PortFile.Result.Err("The settings file is not there: ${file.path}")
        } else {
            PortFile.Result.Ok(file.readText())
        }
    } catch (error: IOException) {
        PortFile.Result.Err("The settings could not be read: ${error.message}")
    } catch (error: SecurityException) {
        PortFile.Result.Err("Reading ${file.path} was refused by the platform.")
    }

    override fun write(text: String): PortFile.Result<Unit> {
        val temp = File(file.parentFile, "${file.name}.tmp")
        return try {
            file.parentFile?.mkdirs()
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return PortFile.Result.Err("The settings could not be replaced.")
                }
            }
            PortFile.Result.Ok(Unit)
        } catch (error: IOException) {
            temp.delete()
            PortFile.Result.Err("The settings could not be written: ${error.message}")
        } catch (error: SecurityException) {
            temp.delete()
            PortFile.Result.Err("Writing ${file.path} was refused by the platform.")
        }
    }

    override fun exists(): Boolean = file.isFile

    override fun lastModified(): Long = file.lastModified()

    override fun describe(): String = file.absolutePath
}

/**
 * A settings file reached through the Storage Access Framework.
 *
 * This is the only way to write into shared storage on a modern Android, and it
 * is what the user grants when they pick the port's folder. The grant is
 * persisted, so it survives a restart — which matters, because the port writes
 * its settings again on every launch and this app has to be able to follow.
 *
 * `DocumentFile` is used rather than a raw `ContentResolver` query because it
 * is what the rest of this project already uses for the game folder, and the
 * two should not become two different answers to "what is a folder".
 */
class SafPortFile(
    private val resolver: ContentResolver,
    private val document: DocumentFile,
) : PortFile {

    override fun read(): String? = readWithReason().valueOrNull()

    override fun readWithReason(): PortFile.Result<String> = try {
        resolver.openInputStream(document.uri)?.use { stream ->
            PortFile.Result.Ok(stream.bufferedReader().readText())
        } ?: PortFile.Result.Err(
            "The document provider would not open ${document.name}. " +
                "Its permission may have been revoked.",
        )
    } catch (error: IOException) {
        PortFile.Result.Err("The settings could not be read: ${error.message}")
    } catch (error: SecurityException) {
        PortFile.Result.Err("Reading ${document.name} was refused by the provider.")
    }

    override fun write(text: String): PortFile.Result<Unit> = try {
        // "wt" truncates. Without the "t" a shorter settings file would leave
        // the tail of the old one behind, and the port would read a line that
        // looks like a key from the previous file.
        val stream = resolver.openOutputStream(document.uri, "wt")
        if (stream == null) {
            PortFile.Result.Err("The provider would not open ${document.name} for writing.")
        } else {
            stream.use { output -> output.bufferedWriter().use { it.write(text) } }
            PortFile.Result.Ok(Unit)
        }
    } catch (error: IOException) {
        PortFile.Result.Err("The settings could not be written: ${error.message}")
    } catch (error: SecurityException) {
        PortFile.Result.Err("Writing ${document.name} was refused by the provider.")
    }

    override fun exists(): Boolean = document.exists()

    override fun lastModified(): Long = document.lastModified()

    override fun describe(): String = document.uri.toString()
}

/**
 * Finds, and if necessary creates, the settings document inside a picked folder.
 *
 * The port writes `overlay/port_settings.txt` itself, so the common case is
 * finding it. Creating is only for a first run where the port has not started
 * yet — and even then the app refuses to invent the file, because a settings
 * file this app made up would contain none of the port's own keys.
 */
object SafPortFiles {

    /** The `overlay` folder inside [tree]. */
    fun overlayFolder(tree: DocumentFile): DocumentFile? =
        tree.findFile("overlay")?.takeIf { it.isDirectory }

    /** `overlay/port_settings.txt`, or null when it does not exist. */
    fun settingsIn(tree: DocumentFile): DocumentFile? =
        overlayFolder(tree)?.findFile(SETTINGS_NAME)?.takeIf { it.isFile }

    /** A [PortFile] for [tree]'s settings, or null when there is no such file. */
    fun fileIn(resolver: ContentResolver, tree: DocumentFile): PortFile? =
        settingsIn(tree)?.let { SafPortFile(resolver, it) }

    /**
     * The backup document beside the settings, created on first use.
     *
     * Kept next to the settings rather than in app storage so that restoring
     * still works if this app's data is cleared — the port's own folder is the
     * one place the user can also see and rescue by hand.
     */
    fun backupIn(resolver: ContentResolver, tree: DocumentFile): PortFile? {
        val overlay = overlayFolder(tree) ?: return null
        val name = SETTINGS_NAME + BACKUP_SUFFIX
        val existing = overlay.findFile(name)?.takeIf { it.isFile }
            ?: overlay.createFile("text/plain", name)
        return existing?.let { SafPortFile(resolver, it) }
    }

    /** The settings file's name, as the port writes it. */
    const val SETTINGS_NAME = "port_settings.txt"

    /** Suffix this app uses for its own backup document. */
    const val BACKUP_SUFFIX = ".bak"

    /** A display name for a tree, for a report. */
    fun describe(tree: Uri): String = tree.toString()
}
