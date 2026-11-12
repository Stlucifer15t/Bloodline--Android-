package com.bloodline.android.storage

import com.bloodline.android.game.GameInstall
import com.bloodline.android.game.GameLocation
import com.bloodline.android.game.LocationKind

/**
 * What the launcher remembered about the last verified scan.
 *
 * Storing this is what makes reopening the app fast: the launcher can show the
 * status it already proved instead of walking the game folder again. It holds
 * no game data, only the location and the counts from the scan.
 *
 * @param fingerprint identity of the search folders at the time of the scan, so
 *                    picking a different folder invalidates the cache.
 * @param appVersion  build that produced the result; a new build rescans.
 */
data class InstallMetadata(
    val kind: LocationKind?,
    val reference: String?,
    val displayPath: String?,
    val hasDataDirectory: Boolean,
    val entriesExamined: Int,
    val foldersExamined: Int,
    val verifiedAtMillis: Long,
    val appVersion: String,
    val fingerprint: String,
) {

    /** Rebuilds the install this metadata describes, or null when none was found. */
    fun install(): GameInstall? {
        val resolvedKind = kind ?: return null
        val resolvedReference = reference ?: return null
        val resolvedPath = displayPath ?: return null

        return GameInstall(
            location = GameLocation(resolvedKind, resolvedReference, resolvedPath),
            hasDataDirectory = hasDataDirectory,
        )
    }

    /**
     * A remembered result is trusted while it is recent, produced by this build
     * and gathered from the same search folders.
     */
    fun isFresh(
        fingerprint: String,
        appVersion: String,
        now: Long,
        staleAfterMillis: Long,
    ): Boolean {
        if (this.fingerprint != fingerprint) return false
        if (this.appVersion != appVersion) return false
        if (verifiedAtMillis <= 0 || now < verifiedAtMillis) return false
        return now - verifiedAtMillis <= staleAfterMillis
    }

    companion object {

        val EMPTY = InstallMetadata(
            kind = null,
            reference = null,
            displayPath = null,
            hasDataDirectory = false,
            entriesExamined = 0,
            foldersExamined = 0,
            verifiedAtMillis = 0,
            appVersion = "",
            fingerprint = "",
        )

        fun of(
            install: GameInstall?,
            entriesExamined: Int,
            foldersExamined: Int,
            verifiedAtMillis: Long,
            appVersion: String,
            fingerprint: String,
        ): InstallMetadata = if (install == null) {
            EMPTY.copy(
                entriesExamined = entriesExamined,
                foldersExamined = foldersExamined,
                verifiedAtMillis = verifiedAtMillis,
                appVersion = appVersion,
                fingerprint = fingerprint,
            )
        } else {
            InstallMetadata(
                kind = install.location.kind,
                reference = install.location.reference,
                displayPath = install.location.displayPath,
                hasDataDirectory = install.hasDataDirectory,
                entriesExamined = entriesExamined,
                foldersExamined = foldersExamined,
                verifiedAtMillis = verifiedAtMillis,
                appVersion = appVersion,
                fingerprint = fingerprint,
            )
        }
    }
}

/** Persists [InstallMetadata]. */
interface InstallMetadataStore {
    fun load(): InstallMetadata?
    fun save(metadata: InstallMetadata)
    fun clear()
}

/** [InstallMetadataStore] that keeps everything in memory. Used by tests. */
class InMemoryInstallMetadataStore : InstallMetadataStore {
    private var value: InstallMetadata? = null

    override fun load(): InstallMetadata? = value

    override fun save(metadata: InstallMetadata) {
        value = metadata
    }

    override fun clear() {
        value = null
    }
}
