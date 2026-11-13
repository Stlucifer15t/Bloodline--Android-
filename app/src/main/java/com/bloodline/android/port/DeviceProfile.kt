package com.bloodline.android.port

import java.io.File

/**
 * Reads the handful of platform facts that decide whether a driver package may
 * be offered, and whether a preset makes sense.
 *
 * Kept free of Android types on purpose. The activity supplies the manufacturer,
 * model and the Vulkan version it read from `PackageManager`; everything else
 * comes from files in `/sys`, which are read through a plain [File] here so the
 * parsing can be tested against recorded device strings.
 */
object DeviceProfile {

    /** The kbase character device every Mali driver package needs. */
    const val MALI_NODE = "/dev/mali0"

    /** Where `gpuinfo` lives on Arm's kbase driver. */
    const val GPU_INFO = "/sys/class/misc/mali0/device/gpuinfo"

    /**
     * Builds a [GpuIdentity] from what the caller measured.
     *
     * Every input is optional because none of them is guaranteed: a kernel may
     * hide `gpuinfo`, a device may report no Vulkan feature, and an Adreno
     * phone has no `mali0` at all. Missing facts produce a profile that says
     * "unknown", never a default that looks measured.
     */
    fun read(
        vendor: String,
        model: String,
        vulkanPacked: Int?,
        gpuInfo: String? = readText(GPU_INFO),
        maliNodeExists: Boolean = File(MALI_NODE).exists(),
    ): GpuIdentity = GpuIdentity(
        rawGpuInfo = gpuInfo,
        hasMaliNode = maliNodeExists,
        vulkanVersion = vulkanPacked?.let(VulkanVersion::fromPacked),
        vendor = vendor,
        model = model,
    )

    private fun readText(path: String): String? = runCatching {
        val file = File(path)
        if (file.canRead()) file.readText().trim().ifBlank { null } else null
    }.getOrNull()

    /**
     * The screen height a preset should target, in pixels.
     *
     * The port's settings want a render height, but the panel reports its own
     * size. This returns the *smaller* dimension when the device is in
     * landscape, because the game is landscape and its height is the short
     * edge.
     */
    fun landscapeHeight(widthPx: Int, heightPx: Int): Int = minOf(widthPx, heightPx)
}
