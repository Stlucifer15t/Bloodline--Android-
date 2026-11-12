package com.bloodline.android.runtime.graphics

/**
 * Whether this GPU can cope with the game's compressed textures.
 *
 * This is the Mali problem that is easy to miss and impossible to ignore once
 * you hit it. PC games store textures in the BCn formats (BC1–BC7 — DXT1 to
 * DXT5 in 2004-era terms). Adreno and desktop GPUs support them in hardware.
 * **Mali does not.** Without a translation step the result is not a slow
 * texture, it is a black or missing one: the game runs and the world is
 * invisible.
 *
 * Vampire: Bloodlines is a 2004 Source-engine game, so essentially every world
 * texture is DXT1 or DXT5. This is not an edge case for us.
 *
 * The three ways out, in the order this class prefers them:
 *
 * * **[ASTC]** — transcode BCn to ASTC. Best quality, needs an ASTC-capable GPU.
 * * **[ETC2]** — transcode to ETC2, the GLES 3.0 mandatory format. Lower quality
 *   than ASTC but far more widely available.
 * * **[SOFTWARE]** — decompress on the CPU. Always available, always slow.
 *
 * Detection reads the GL extension string, which is the only reliable way to
 * know what a GLES driver actually supports.
 */
enum class TextureCompatibility(
    val title: String,
    val description: String,

    /** True when this route produces usable textures for a BCn game. */
    val canHandleBcn: Boolean,

    /** How expensive the route is, lower being better. Used to rank candidates. */
    val cost: Int,
) {

    NATIVE(
        title = "Native BCn support",
        description = "The GPU decodes BCn textures directly. Rare outside desktop and " +
            "Adreno parts, but when it is present there is nothing to translate.",
        canHandleBcn = true,
        cost = 0,
    ),

    ASTC(
        title = "BCn → ASTC transcoding",
        description = "Block-compressed textures transcoded to ASTC on the GPU. Best " +
            "quality of the translation routes, and the one the Mali builds prefer.",
        canHandleBcn = true,
        cost = 1,
    ),

    ETC2(
        title = "BCn → ETC2 transcoding",
        description = "Block-compressed textures transcoded to ETC2, which every " +
            "OpenGL ES 3.0 device supports. Lower fidelity than ASTC, broader reach.",
        canHandleBcn = true,
        cost = 2,
    ),

    SOFTWARE(
        title = "BCn decoded on the CPU",
        description = "Textures decompressed in software and uploaded uncompressed. " +
            "Correct but expensive: more memory traffic and more work per frame.",
        canHandleBcn = true,
        cost = 3,
    ),

    UNSUPPORTED(
        title = "No BCn handling",
        description = "Nothing here can produce a visible BCn texture. A 2004 " +
            "Source-engine game would render black or missing geometry.",
        canHandleBcn = false,
        cost = Int.MAX_VALUE,
    ),
    ;

    companion object {

        /** Extension strings that advertise each capability. */
        private val ASTC_EXTENSIONS = listOf(
            "GL_KHR_texture_compression_astc_ldr",
            "GL_KHR_texture_compression_astc_hdr",
        )

        private val BCn_EXTENSIONS = listOf(
            "GL_EXT_texture_compression_s3tc",
            "GL_EXT_texture_compression_dxt1",
            "GL_ARB_texture_compression_bptc",
        )

        /**
         * Picks the best route this GPU supports.
         *
         * @param glesMajor GLES major version, or null when it could not be read.
         * @param extensions the GL extension string, or null.
         */
        fun detect(glesMajor: Int?, extensions: String?): TextureCompatibility {
            if (glesMajor == null || extensions == null) return UNSUPPORTED

            if (BCn_EXTENSIONS.any { extensions.contains(it) }) return NATIVE
            if (ASTC_EXTENSIONS.any { extensions.contains(it) }) return ASTC

            // ETC2 is mandatory in OpenGL ES 3.0 and later. Below that there is
            // no compressed format we can rely on, so software decoding is all
            // that is left.
            return if (glesMajor >= 3) ETC2 else SOFTWARE
        }

        /** The best of several candidates, cheapest first. */
        fun bestOf(candidates: List<TextureCompatibility>): TextureCompatibility =
            candidates.minByOrNull { it.cost } ?: UNSUPPORTED
    }
}
