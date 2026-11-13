package com.bloodline.android.port

/**
 * Reads and writes one settings file, wherever it actually lives.
 *
 * The interface exists because of a measured platform fact: on Android 14 a
 * plain `File` write into shared storage is refused even when
 * `WRITE_EXTERNAL_STORAGE` reports as granted —
 *
 * ```
 * sh: can't create /sdcard/Download/.../port_settings.txt: Permission denied
 * ```
 *
 * The port keeps its settings in exactly that location. So the store does not
 * assume a filesystem path; it asks for text and hands text back, and a caller
 * supplies either a [FilePortFile] (works for app storage) or a SAF-backed
 * implementation (required for `/sdcard`).
 *
 * Every method reports failure as a value rather than throwing, because the
 * caller has to show the user a reason and "PlatformException" is not one.
 */
interface PortFile {

    /** The file's text, or null when it cannot be read. */
    fun read(): String?

    /** The file's text, or a [PortFileError] when it cannot be read. */
    fun readWithReason(): Result<String>

    /** Replaces the file's contents. */
    fun write(text: String): Result<Unit>

    /** True when the file is present and readable. */
    fun exists(): Boolean

    /** When the file was last modified, or 0 when unknown. */
    fun lastModified(): Long

    /** A short description for a report: a path, or a document URI. */
    fun describe(): String

    /** A result that carries a reason written for a person. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val reason: String) : Result<Nothing>
    }
}

/** Convenience aliases, so call sites read the way they think. */
fun <T> PortFile.Result<T>.valueOrNull(): T? = (this as? PortFile.Result.Ok)?.value

fun PortFile.Result<*>.reasonOrNull(): String? = (this as? PortFile.Result.Err)?.reason
