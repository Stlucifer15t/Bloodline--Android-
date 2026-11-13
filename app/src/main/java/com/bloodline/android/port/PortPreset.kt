package com.bloodline.android.port

/**
 * A named set of settings for one kind of device.
 *
 * A preset is data, not a code path: each one says which keys it writes and
 * what it expects to gain, so the screen can explain it and the diagnostics
 * report can record exactly what was applied. Everything a preset touches is a
 * key the port itself understands — nothing here invents a setting.
 */
data class PortPreset(
    val id: String,
    val title: String,
    val summary: String,

    /** Render height to ask for. See [RenderHeight]. */
    val renderHeight: Int,

    /** Frame cap, in frames per second. */
    val frameLimit: Int,

    /** Which renderer to use, or null to leave the port's own choice alone. */
    val renderer: GraphicsApi?,

    /** Image-quality preset index, or null to leave it alone. */
    val imageQualityPreset: Double? = null,

    /** True when this preset is meant for devices that cannot hold 30 fps. */
    val aggressive: Boolean = false,
) {

    /** The port keys this preset writes, in the order they should be reviewed. */
    fun changes(): Map<String, String> = buildMap {
        put(PortSettings.KEY_RENDER_HEIGHT, renderHeight.toString())
        put(PortSettings.KEY_FRAME_LIMIT, frameLimit.toString())
        renderer?.let { put(PortSettings.KEY_GRAPHICS_API, it.value.toInt().toString()) }
        imageQualityPreset?.let {
            put(PortSettings.KEY_IMAGE_PRESET, formatPreset(it))
        }
    }

    /** One line for the screen and the report. */
    fun describe(): String = buildString {
        append(if (renderHeight == RenderHeight.NATIVE) "native height" else "${renderHeight}p")
        append(" · ")
        append(if (frameLimit == 0) "uncapped" else "$frameLimit fps")
        renderer?.let { append(" · ").append(it.title) }
        imageQualityPreset?.let { append(" · image preset ").append(formatPreset(it)) }
    }

    private fun formatPreset(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString() else value.toString()

    companion object {

        /**
         * The preset this app recommends for a device.
         *
         * The rules are the ones the measurements support, in order:
         *
         * * **A frame cap of 30, not 60.** A phone GPU that averages 30 with
         *   dips is worse to play than one pinned at 30 — the dips are what the
         *   player feels, and capping removes the top of the range that causes
         *   them.
         * * **540p or lower on a Mali-G52.** The panel is ~600x1333 at 233 dpi;
         *   drawing 720 rows when the GPU is the limit buys sharpness nobody
         *   sees in motion and costs the frame rate everyone does.
         * * **OpenGL ES when Vulkan is old.** DXVK needs Vulkan features the
         *   stock Arm driver does not expose; the port's own GLES translator
         *   asks far less of it.
         */
        fun recommendedFor(identity: GpuIdentity, screenHeight: Int): PortPreset {
            val mali = identity.family?.uppercase()?.startsWith("MALI") == true
            val bifrost = identity.architecture == MaliArchitecture.V6 ||
                identity.architecture == MaliArchitecture.V7
            val oldVulkan = identity.vulkanVersion?.atLeast(1, 2) != true

            val height = when {
                bifrost -> RenderHeight.recommendedFor(screenHeight, ceiling = 540)
                mali -> RenderHeight.recommendedFor(screenHeight, ceiling = 720)
                else -> RenderHeight.recommendedFor(screenHeight, ceiling = 900)
            }

            val renderer = when {
                bifrost && oldVulkan -> GraphicsApi.OPENGL_ES
                else -> null
            }

            return PortPreset(
                id = "recommended",
                title = "Recommended for this phone",
                summary = buildString {
                    append("A 30 fps cap, ")
                    append(if (height == RenderHeight.NATIVE) "native height" else "${height}p")
                    append(" rendering")
                    renderer?.let { append(", and the ").append(it.title).append(" renderer") }
                    append(". Chosen from this device's GPU (")
                    append(identity.family ?: "unknown")
                    append(") and Vulkan ")
                    append(identity.vulkanVersion?.toString() ?: "version unknown")
                    append(".")
                },
                renderHeight = height,
                frameLimit = 30,
                renderer = renderer,
                imageQualityPreset = null,
                aggressive = false,
            )
        }

        /**
         * For a device that still cannot hold 30.
         *
         * 480p and the lowest quality preset. Deliberately not offered first:
         * it looks noticeably worse, and the point of the recommended preset is
         * to find out whether the bigger win is available.
         */
        val EMERGENCY = PortPreset(
            id = "emergency",
            title = "Lowest settings",
            summary = "480p, 30 fps, lowest image quality. Use this when the recommended " +
                "preset still drops frames; it is the setting most likely to hold a " +
                "steady frame rate on a weak GPU.",
            renderHeight = 480,
            frameLimit = 30,
            renderer = null,
            imageQualityPreset = 0.0,
            aggressive = true,
        )

        /** Put everything back the way the port had it. */
        val STOCK = PortPreset(
            id = "stock",
            title = "Port defaults",
            summary = "Native render height, 60 fps, whatever renderer the port picked. " +
                "This is what the game does before this app touches anything.",
            renderHeight = RenderHeight.NATIVE,
            frameLimit = 60,
            renderer = null,
            imageQualityPreset = null,
        )

        val ALL: List<PortPreset> = listOf(EMERGENCY, STOCK)
    }
}
