package com.bloodline.android.storage

import android.content.Context
import androidx.core.content.edit
import com.bloodline.android.game.LocationKind

/** [InstallMetadataStore] backed by SharedPreferences. */
class SharedPreferencesInstallMetadataStore(context: Context) : InstallMetadataStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): InstallMetadata? {
        // Nothing has been scanned yet, or the cache was cleared.
        if (!preferences.contains(KEY_VERIFIED_AT)) return null

        val kindName = preferences.getString(KEY_KIND, null)
        return InstallMetadata(
            kind = kindName?.let { runCatching { LocationKind.valueOf(it) }.getOrNull() },
            reference = preferences.getString(KEY_REFERENCE, null),
            displayPath = preferences.getString(KEY_DISPLAY_PATH, null),
            hasDataDirectory = preferences.getBoolean(KEY_HAS_DATA, false),
            entriesExamined = preferences.getInt(KEY_ENTRIES, 0),
            foldersExamined = preferences.getInt(KEY_FOLDERS, 0),
            verifiedAtMillis = preferences.getLong(KEY_VERIFIED_AT, 0L),
            appVersion = preferences.getString(KEY_APP_VERSION, "").orEmpty(),
            fingerprint = preferences.getString(KEY_FINGERPRINT, "").orEmpty(),
        )
    }

    override fun save(metadata: InstallMetadata) {
        preferences.edit {
            putString(KEY_KIND, metadata.kind?.name)
            putString(KEY_REFERENCE, metadata.reference)
            putString(KEY_DISPLAY_PATH, metadata.displayPath)
            putBoolean(KEY_HAS_DATA, metadata.hasDataDirectory)
            putInt(KEY_ENTRIES, metadata.entriesExamined)
            putInt(KEY_FOLDERS, metadata.foldersExamined)
            putLong(KEY_VERIFIED_AT, metadata.verifiedAtMillis)
            putString(KEY_APP_VERSION, metadata.appVersion)
            putString(KEY_FINGERPRINT, metadata.fingerprint)
        }
    }

    override fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFERENCES_NAME = "bloodlines_install_metadata"
        const val KEY_KIND = "kind"
        const val KEY_REFERENCE = "reference"
        const val KEY_DISPLAY_PATH = "display_path"
        const val KEY_HAS_DATA = "has_data_directory"
        const val KEY_ENTRIES = "entries_examined"
        const val KEY_FOLDERS = "folders_examined"
        const val KEY_VERIFIED_AT = "verified_at_millis"
        const val KEY_APP_VERSION = "app_version"
        const val KEY_FINGERPRINT = "roots_fingerprint"
    }
}
