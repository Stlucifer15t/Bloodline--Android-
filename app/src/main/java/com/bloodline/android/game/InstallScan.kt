package com.bloodline.android.game

/**
 * Real stages of a game-files scan.
 *
 * There is deliberately no percentage: a scan over the Storage Access Framework
 * cannot know how many entries exist before it has listed them, so any number
 * from 0 to 100 would be made up. What the scanner *does* know is exactly how
 * many entries it has examined, and that is what it reports.
 */
enum class ScanStage {
    /** Walking the search folders and their immediate children. */
    SCANNING_FOLDERS,

    /** Vampire.exe was seen; checking that the data folder sits beside it. */
    CHECKING_REQUIRED_FILES,

    /** The scan finished. */
    FINISHED,
}

/** A genuine, incremental report from a scan in progress. */
data class ScanProgress(
    val stage: ScanStage,
    val entriesExamined: Int,
    val foldersExamined: Int,
)

/** Whether a report was recomputed now or reused from the last verified scan. */
enum class ScanSource {
    FRESH,
    CACHE,
}

/** The outcome of a scan, with the real work that went into it. */
data class InstallScanReport(
    val install: GameInstall?,
    val entriesExamined: Int,
    val foldersExamined: Int,
    val scannedAtMillis: Long,
    val source: ScanSource,
    /** Set when the scan itself failed, e.g. access to the folder was revoked. */
    val error: Throwable? = null,
)
