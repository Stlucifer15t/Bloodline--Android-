package com.bloodline.android.runtime.packaging

/**
 * One downloadable piece of the Windows compatibility runtime.
 *
 * The runtime is **acquired, not built**. Wine, Box64, WOWBox64 and the rest are
 * large prebuilt binaries maintained by other projects; rebuilding them here
 * would be worse than reusing them, and bundling hundreds of megabytes of
 * binaries we cannot verify would be worse still. So a package is described
 * declaratively — where it comes from, what licence it carries, where it
 * unpacks to — and the runtime acquires it on demand. This is the same model
 * the upstream projects use themselves (`WineDownloader`, `ContentsManager`).
 *
 * Every field that matters for legality is on the record, because point 6 of
 * the brief is explicit: a component that cannot be redistributed must have a
 * legitimate acquisition path rather than being silently copied.
 */
data class RuntimePackage(
    val id: String,

    /** What this package contributes to the runtime. */
    val type: PackageType,

    /** Human-readable name, e.g. "Wine 10 (x86_64)". */
    val name: String,

    val version: String,

    /** ABI this build is for. The runtime refuses a package for another ABI. */
    val abi: String,

    /** Where it comes from. Pinned, never a "latest" redirect. */
    val url: String,

    /**
     * Expected SHA-256, or null when upstream publishes none.
     *
     * Null is recorded as a fact rather than filled with a guess: the
     * [acquisition][RuntimePackageManager] then reports the package as
     * *unverified* instead of claiming it checked something it did not.
     */
    val sha256: String? = null,

    /** Advertised download size, or 0 when unknown. Shown before downloading. */
    val sizeBytes: Long = 0L,

    val licence: Licence,

    /** Where it unpacks to, relative to the runtime root. */
    val target: String,

    /** True when the game cannot start without it. */
    val required: Boolean = true,

    /** Anything a reader of a diagnostics report needs to know. */
    val note: String = "",
) {

    init {
        require(id.isNotBlank()) { "A package needs an id." }
        require(url.startsWith("https://") || isLoopback(url)) {
            "$id must be fetched over HTTPS."
        }
    }

    private companion object {

        /**
         * True for a URL that never leaves this machine.
         *
         * Plain HTTP is refused everywhere else, because these packages are
         * executables and a cleartext fetch is an invitation to substitute
         * one. Loopback is the single exception: the traffic crosses no
         * network, and it is what lets the acquisition path be exercised end
         * to end against a real server instead of a mock.
         */
        private fun isLoopback(url: String): Boolean =
            url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:") ||
                url.startsWith("http://[::1]:")
    }
}

/** What a package contributes. */
enum class PackageType(val title: String) {
    WINE("Wine / Proton"),
    CPU_EMULATOR("CPU emulator"),
    WOW64("32-bit Windows (WoW64) support"),
    GRAPHICS("Graphics driver / wrapper"),
    TEXTURE("Texture compatibility"),
    AUDIO("Audio server"),
    WINDOWS_COMPONENT("Windows component"),
}

/**
 * The licence a package carries, kept with the package rather than in a
 * spreadsheet nobody reads.
 *
 * @param redistributable false when we must not ship the binary ourselves, which
 *                        is exactly the case for the Mali Vulkan driver found
 *                        during discovery (no declared licence).
 */
data class Licence(
    val spdx: String,
    val name: String,
    /** Where the licence text lives. */
    val referenceUrl: String,
    /** Where the source lives — required for LGPL/GPL source-distribution. */
    val sourceUrl: String? = null,
    val redistributable: Boolean = true,
    /** Copyright line to reproduce in NOTICE. */
    val attribution: String,
)

/**
 * Container formats we can read.
 *
 * Detected from the file's magic bytes rather than its extension, because the
 * upstream packages use a private extension (`.wcp`) over an ordinary archive,
 * and trusting the extension is how a wrong decoder gets pointed at a file.
 */
enum class ArchiveFormat(val title: String) {
    ZIP("ZIP"),
    TAR("tar"),
    TAR_ZST("tar.zst"),
    TAR_XZ("tar.xz"),
    TAR_GZ("tar.gz"),
    UNKNOWN("unrecognised"),
    ;

    companion object {

        /** The ZIP local-file-header magic. */
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** The zstd frame magic. */
        private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

        private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)

        private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

        fun detect(header: ByteArray): ArchiveFormat {
            if (header.size >= 4 && header.startsWithWith(ZIP_MAGIC)) return ZIP
            if (header.size >= 4 && header.startsWithWith(ZSTD_MAGIC)) return TAR_ZST
            if (header.size >= 6 && header.startsWithWith(XZ_MAGIC)) return TAR_XZ
            if (header.size >= 2 && header.startsWithWith(GZIP_MAGIC)) return TAR_GZ
            // tar's "ustar" identifier sits at offset 257, so a short read
            // cannot confirm it. Anything we cannot identify is UNKNOWN, and the
            // caller says so instead of guessing.
            if (header.size > 262 && header.copyOfRange(257, 262).contentEquals(
                    byteArrayOf(0x75, 0x73, 0x74, 0x61, 0x72)
                )
            ) return TAR
            return UNKNOWN
        }

        private fun ByteArray.startsWithWith(prefix: ByteArray): Boolean {
            for (i in prefix.indices) if (this[i] != prefix[i]) return false
            return true
        }
    }
}
