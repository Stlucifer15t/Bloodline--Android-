package com.bloodline.android.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/**
 * Remembers the game folder the user picked.
 *
 * Only the `content://` tree URI is stored. The launcher holds no copy of the
 * game itself, so clearing app data simply forgets the link.
 */
class GameFolderStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Persists the picked folder so it survives restarts. */
    fun save(uri: Uri) {
        preferences.edit { putString(KEY_GAME_FOLDER_URI, uri.toString()) }
    }

    /** The saved folder, or null when the user has not picked one yet. */
    fun load(): Uri? = preferences.getString(KEY_GAME_FOLDER_URI, null)?.let(Uri::parse)

    fun clear() {
        preferences.edit { remove(KEY_GAME_FOLDER_URI) }
    }

    private companion object {
        const val PREFERENCES_NAME = "bloodlines_game_folder"
        const val KEY_GAME_FOLDER_URI = "game_folder_uri"
    }
}
