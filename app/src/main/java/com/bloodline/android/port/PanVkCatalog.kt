package com.bloodline.android.port

/**
 * The PanVK driver packages this app knows how to install.
 *
 * Every URL and digest below was read from the upstream release, not invented,
 * and every digest is **published by upstream** rather than measured here — the
 * release API carries `sha256:` digests per asset, which is a stronger claim
 * than the Wine catalogue's measured pins.
 *
 * Why PanVK at all: the port's default renderer is DXVK, and DXVK on this
 * phone's stock driver starts with this in its own log —
 *
 * ```
 * warn: DxvkAdapter: requested core feature #11 is not supported by the device, disabling it
 * ```
 *
 * The stock driver is Vulkan **1.1**. Mesa's PanVK for Bifrost reaches 1.3/1.4
 * and implements BCn decoding for the G52 specifically, which is exactly the
 * class of thing DXVK is turning off. Whether that is faster on this GPU is a
 * question for a measurement, not for this file.
 */
object PanVkCatalog {

    private const val RELEASES =
        "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v26.3.1"

    /**
     * The universal AdrenoTools-format package.
     *
     * Chosen over the per-app zips because the format is a documented, generic
     * container — `meta.json` plus a `libvulkan_*.so` — which is also the shape
     * the Bloodlines port's own driver loader already understands. It is a
     * 9.5 MB download against 34 MB for the bare `.so`, and it carries the
     * metadata needed to name the driver in a report.
     */
    val PANVK_UNIVERSAL = DriverPackage(
        id = "panvk-26.3.1-adrenotools",
        name = "Mesa PanVK 26.3.1",
        driverVersion = "Mesa 26.3.0-devel (git-795ca7ff3e, p0175, 2026-10-31)",
        url = "$RELEASES/Mesa-PanVK-Android-v26.3.1-795ca7ff-adrenotools.zip",
        sizeBytes = 9_482_291L,
        sha256 = "7e2e9987bbebefb06f52b2570e61e6208503d4d3a10a395b97aea9b6e1fad9e4",
        /** Inside the zip: the ICD the loader must open. */
        libraryName = "libvulkan_panfrost.so",
        /** The library's own published digest, checked after unpacking. */
        librarySha256 = "62c2b3f98ca987d5ab50b6f01e2d2c1c5b3ee995fe93666aa5da9415f65c3348",
        minApi = 29,
        /** Architectures this build covers, from the release's own table. */
        supportedArchitectures = setOf(
            MaliArchitecture.V6,
            MaliArchitecture.V7,
            MaliArchitecture.V9,
            MaliArchitecture.V10,
            MaliArchitecture.V11,
            MaliArchitecture.V12,
            MaliArchitecture.V13,
            MaliArchitecture.V14,
        ),
        licence = Licence(
            spdx = "MIT",
            name = "MIT License (Mesa)",
            referenceUrl = "https://opensource.org/licenses/MIT",
            sourceUrl = "https://gitlab.freedesktop.org/mesa/mesa",
            attribution = "Mesa PanVK, patched for mali_kbase by panvk-kbase-android",
        ),
    )

    val ALL: List<DriverPackage> = listOf(PANVK_UNIVERSAL)

    fun byId(id: String): DriverPackage? = ALL.firstOrNull { it.id == id }

    /** The package that can serve [identity], or null when none is known to. */
    fun forDevice(identity: GpuIdentity): DriverPackage? {
        val arch = identity.architecture ?: return null
        if (!identity.hasMaliNode) return null
        return ALL.firstOrNull { arch in it.supportedArchitectures && arch.supportedByPanVk }
    }

    /**
     * Why [identity] cannot take a PakVK driver, written for a person.
     *
     * Empty means it can. This exists so the screen can explain a refusal
     * instead of hiding the button with no reason — an Adreno phone, a hidden
     * `mali0`, or a generation the packages do not cover are three different
     * answers and the user deserves the right one.
     */
    fun reasonNotApplicable(identity: GpuIdentity, apiLevel: Int): String? = when {
        !identity.hasMaliNode ->
            "This device has no /dev/mali0, so it is not running Arm's Mali kernel " +
                "driver. PanVK's kbase builds cannot be used here."
        identity.architecture == null ->
            "The GPU could not be identified (gpuinfo reported " +
                "${identity.rawGpuInfo ?: "nothing"}), so no driver is offered for it."
        !identity.architecture.supportedByPanVk ->
            "${identity.family} is ${identity.architecture.label}, which the universal " +
                "PanVK package lists as not fully supported. Installing it would be a guess."
        forDevice(identity) == null ->
            "No package in the catalogue covers ${identity.family}."
        apiLevel < PANVK_UNIVERSAL.minApi ->
            "This package needs Android API ${PANVK_UNIVERSAL.minApi} or newer; " +
                "this device reports API $apiLevel."
        else -> null
    }
}

/** A driver package, with what is needed to download and verify it. */
data class DriverPackage(
    val id: String,
    val name: String,
    val driverVersion: String,
    val url: String,
    val sizeBytes: Long,
    /** Digest of the zip as published upstream. */
    val sha256: String,
    /** The packaged Vulkan ICD. */
    val libraryName: String,
    /** Digest of that library, as published inside the archive. */
    val librarySha256: String,
    val minApi: Int,
    val supportedArchitectures: Set<MaliArchitecture>,
    val licence: Licence,
)

/** A third-party component's licence, kept so a release can list it. */
data class Licence(
    val spdx: String,
    val name: String,
    val referenceUrl: String,
    val sourceUrl: String,
    val attribution: String,
)
