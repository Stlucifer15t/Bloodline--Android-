package com.bloodline.android.settings

/**
 * Key/value persistence behind [SettingsRepository].
 *
 * Split out so the repository can be unit tested on the JVM with
 * [InMemorySettingsStore] instead of Android's SharedPreferences.
 */
interface SettingsStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun contains(key: String): Boolean
}

/** Simple [SettingsStore] used by tests (and as a fallback before storage is ready). */
class InMemorySettingsStore : SettingsStore {
    private val values = HashMap<String, Any>()

    override fun getString(key: String, default: String): String = values[key] as? String ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = values.containsKey(key)
}
