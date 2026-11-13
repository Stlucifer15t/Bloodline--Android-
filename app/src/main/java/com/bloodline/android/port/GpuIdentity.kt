package com.bloodline.android.port

/**
 * What this device's GPU is, in the terms a Vulkan driver package cares about.
 *
 * A PanVK package is built for one kernel interface and one GPU family: the
 * universal builds choose a profile from the architecture number and then talk
 * to Arm's `mali_kbase` through `/dev/mali0`. Installing the wrong one is not a
 * slower game, it is a driver that cannot enumerate the GPU — so the checks
 * live here, as plain data, rather than being scattered through the installer.
 *
 * Nothing here opens a device or runs a command: the caller passes in what it
 * observed, and this decides whether a package is allowed.
 */
data class GpuIdentity(

    /** `/sys/class/misc/mali0/device/gpuinfo`, trimmed, or null when absent. */
    val rawGpuInfo: String?,

    /** True when `/dev/mali0` exists and is writable — the kbase interface. */
    val hasMaliNode: Boolean,

    /** Vulkan version the platform advertises, e.g. 1.1.177. Null when unknown. */
    val vulkanVersion: VulkanVersion?,

    /** Manufacturer string from `Build.MANUFACTURER`. */
    val vendor: String,

    /** Model string from `Build.MODEL`. */
    val model: String,
) {

    /** The GPU family name, e.g. `Mali-G52`, parsed from [rawGpuInfo]. */
    val family: String? get() = parseFamily(rawGpuInfo)

    /**
     * The Bifrost/Valhall architecture generation, which is what a driver
     * package actually selects its code paths by.
     *
     * Arm numbers them `v6`…`v14`; the mapping from a marketing name to a
     * generation is fixed and published in Mesa's own documentation, which is
     * why it is encoded here rather than guessed at run time.
     */
    val architecture: MaliArchitecture? get() = family?.let(MaliArchitecture::of)

    /** True when the node exists and the architecture is known. */
    val canHostKbaseDriver: Boolean get() = hasMaliNode && architecture != null
}

/** A Vulkan version, as the platform reports it. */
data class VulkanVersion(val major: Int, val minor: Int, val patch: Int) {

    /** True when this is at least [major].[minor]. */
    fun atLeast(major: Int, minor: Int): Boolean =
        this.major > major || (this.major == major && this.minor >= minor)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {

        /**
         * Decodes the packed integer Android exposes as
         * `android.hardware.vulkan.version`, which is
         * `major << 22 | minor << 12 | patch`.
         */
        fun fromPacked(packed: Int): VulkanVersion? {
            if (packed <= 0) return null
            return VulkanVersion(
                major = packed shr 22,
                minor = (packed shr 12) and 0x3FF,
                patch = packed and 0xFFF,
            )
        }

        /** Parses `1.3.363` or `1.1`. */
        fun parse(text: String?): VulkanVersion? {
            if (text.isNullOrBlank()) return null
            val parts = text.trim().split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return VulkanVersion(major, minor, patch)
        }
    }
}

/**
 * Arm Mali GPU generations, and which of them a PanVK build supports.
 *
 * The names are the ones Mesa's driver and the Android packages use. Only what
 * a driver package can select between is modelled; the marketing detail that
 * does not change a decision is left out.
 */
enum class MaliArchitecture(
    val label: String,
    /** True when the universal PanVK builds cover this generation. */
    val supportedByPanVk: Boolean,
) {
    /** Bifrost, first generation. */
    V6("v6", supportedByPanVk = true),

    /** Bifrost, second generation — Mali-G52 and friends. */
    V7("v7", supportedByPanVk = true),

    /** Valhall, job-manager frontend. */
    V9("v9", supportedByPanVk = true),

    /** Valhall, command-stream frontend. */
    V10("v10", supportedByPanVk = true),
    V11("v11", supportedByPanVk = true),
    V12("v12", supportedByPanVk = true),
    V13("v13", supportedByPanVk = true),
    V14("v14", supportedByPanVk = true),

    /** Reported by the packages as not fully supported. */
    V15("v15", supportedByPanVk = false),
    ;

    companion object {

        /**
         * Maps a GPU name to its generation.
         *
         * Bifrost is v6/v7, Valhall v9-v11, and the 5th generation v12-v14.
         * Unknown names return null, and null means "do not offer a driver"
         * rather than a guess — a wrong driver here is a device whose GPU no
         * longer enumerates.
         */
        fun of(gpuFamily: String): MaliArchitecture? {
            val name = gpuFamily.uppercase().trim()

            // Ordered most-specific first. This is not cosmetic: `MALI-G71`
            // is a prefix of `MALI-G710`, so a naive chain classifies a
            // Valhall G710 as Bifrost v7 and offers it the wrong driver.
            return when {
                // Valhall, third and fourth generation, before the two-digit
                // Bifrost names they share a prefix with.
                name.startsWith("MALI-G610") || name.startsWith("MALI-G710") -> V10
                name.startsWith("MALI-G615") || name.startsWith("MALI-G715") -> V11

                // Immortalis, the 5th generation, before the Mali-G1 rule.
                name.startsWith("IMMORTALIS-G720") -> V12
                name.startsWith("IMMORTALIS-G925") -> V13
                name.startsWith("MALI-G1-") || name.startsWith("IMMORTALIS-G1") -> V14

                // Bifrost, first generation.
                name.startsWith("MALI-G31") || name.startsWith("MALI-G51") -> V6
                name.startsWith("MALI-G72") -> V6

                // Bifrost, second generation.
                name.startsWith("MALI-G52") || name.startsWith("MALI-G71") ||
                    name.startsWith("MALI-G76") -> V7

                // Valhall, job-manager frontend.
                name.startsWith("MALI-G57") || name.startsWith("MALI-G68") ||
                    name.startsWith("MALI-G77") || name.startsWith("MALI-G78") -> V9

                else -> null
            }
        }
    }
}

/**
 * Reads the GPU family out of `gpuinfo`.
 *
 * The first line is a space-separated list of properties; the one that matters
 * is `GPU=<name>`, for example
 * `GPU=Mali-G52 MC2 r0p0 0x74021000 kbase_version=11.38 arch=7`.
 *
 * The name itself keeps its core count — `Mali-G52 MC2` — because the
 * generation mapping matches on the prefix anyway, and a caller that wants to
 * show the model should not be handed a truncated one.
 *
 * Parsing it rather than shelling out keeps this testable against the exact
 * strings real devices report.
 */
internal fun parseFamily(gpuInfo: String?): String? {
    if (gpuInfo.isNullOrBlank()) return null
    val firstLine = gpuInfo.lineSequence().firstOrNull().orEmpty()
    val tokens = firstLine.split(' ', '\t').filter { it.isNotBlank() }

    val keyed = tokens.firstOrNull { it.startsWith("GPU=") }?.removePrefix("GPU=")
    if (!keyed.isNullOrBlank()) {
        // `GPU=` may be followed by the variant on the same token or on the
        // next one (`Mali-G52 MC2`); take the optional `MCn` part when it is
        // the very next token, so the result reads like the marketing name.
        val index = tokens.indexOfFirst { it.startsWith("GPU=") }
        val next = tokens.getOrNull(index + 1)
        val joined = if (next != null && next.matches(Regex("(?i)(MC\\d+|MP\\d+)"))) {
            "$keyed $next"
        } else {
            keyed
        }
        return joined.trim()
    }

    // Some kernels put the name first with no key.
    val first = tokens.firstOrNull()
    return if (first?.uppercase()?.startsWith("MALI") == true) first else null
}
