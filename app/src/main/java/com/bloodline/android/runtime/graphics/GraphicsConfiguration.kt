package com.bloodline.android.runtime.graphics

import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.backends.GraphicsBackend

/**
 * A Windows registry setting Wine reads when it starts Direct3D.
 *
 * Registry keys rather than environment variables, because that is where
 * WineD3D's real knobs live. Everything here is a documented Wine setting
 * under `HKEY_CURRENT_USER\Software\Wine`, applied with `wine reg add` before
 * the game starts. Inventing environment variables that no component reads
 * would produce a configuration that looks deliberate and does nothing.
 */
data class RegistrySetting(
    val key: String,
    val name: String,
    val value: String,

    /**
     * `REG_DWORD` for numbers, `REG_SZ` for text. WineD3D is strict about
     * which it expects for a given value.
     */
    val type: RegistryType = RegistryType.STRING,
) {

    enum class RegistryType(val regName: String) {
        STRING("REG_SZ"),
        DWORD("REG_DWORD"),
    }

    /** The `wine reg add` invocation that applies this setting. */
    fun toCommand(wineLoader: String): List<String> = listOf(
        wineLoader,
        "reg", "add",
        key,
        "/v", name,
        "/t", type.regName,
        "/d", value,
        "/f",
    )

    override fun toString(): String = "$key\\$name = $value"

    companion object {

        /** `HKEY_CURRENT_USER\Software\Wine\Direct3D`. */
        private const val DIRECT3D = "HKEY_CURRENT_USER\\Software\\Wine\\Direct3D"

        /** `HKEY_CURRENT_USER\Software\Wine\Drivers`. */
        private const val DRIVERS = "HKEY_CURRENT_USER\\Software\\Wine\\Drivers"

        fun maxGlVersion(major: Int, minor: Int) = RegistrySetting(
            key = DIRECT3D,
            name = "MaxVersionGL",
            value = "$major.$minor",
            type = RegistryType.STRING,
        )

        fun videoMemory(megabytes: Int) = RegistrySetting(
            key = DIRECT3D,
            name = "VideoMemorySize",
            value = megabytes.toString(),
            type = RegistryType.STRING,
        )

        fun offscreenRendering(mode: OffscreenMode) = RegistrySetting(
            key = DIRECT3D,
            name = "OffscreenRenderingMode",
            value = mode.wineName,
            type = RegistryType.STRING,
        )

        /** Selects the Wine driver that provides the windowing system. */
        fun graphicsDriver(name: String) = RegistrySetting(
            key = DRIVERS,
            name = "graphics",
            value = name,
        )

        /** Selects the Wine driver that provides audio. */
        fun audioDriver(name: String) = RegistrySetting(
            key = DRIVERS,
            name = "audio",
            value = name,
        )
    }
}

/** How WineD3D renders offscreen surfaces. */
enum class OffscreenMode(val wineName: String, val description: String) {
    FBO("fbo", "Render to a framebuffer object. The default, and the fastest."),
    BACKBUFFER("backbuffer", "Render to the back buffer. Slower, but works where FBO does not."),
}

/**
 * Everything the graphics stack needs, resolved from a backend and a device.
 *
 * The interesting part is [TextureCompatibility]: a 2004 Source-engine game
 * stores every world texture as DXT1 or DXT5, and **Mali cannot decode BCn**.
 * Miss that and the game does not crash or stutter — it runs, and the world is
 * black. That reads as "Wine is broken" and sends the investigation down the
 * wrong path entirely, so the route is chosen explicitly and reported.
 */
data class GraphicsConfiguration(
    val backend: GraphicsBackend,

    /** How the game's BCn textures will reach the screen. */
    val textureRoute: TextureCompatibility,

    /** GLES version to advertise to WineD3D, or null to leave it alone. */
    val glesVersion: Pair<Int, Int>? = null,

    /** Video memory to report, in megabytes. */
    val videoMemoryMb: Int = DEFAULT_VIDEO_MEMORY_MB,

    val offscreenMode: OffscreenMode = OffscreenMode.FBO,

    /**
     * Box64's 32-bit address-space mapping, disabled on Mali.
     *
     * Taken from what the Mali builds actually do: they set `BOX64_MMAP32=0`
     * rather than switching emulator. It is a Box64 setting, not a reason to
     * change translator.
     */
    val maliMmapWorkaround: Boolean = false,
) {

    /**
     * The environment variables this configuration needs.
     *
     * Only variables a real component reads. `MESA_*` and `LIBGL_*` are Mesa's;
     * `BOX64_MMAP32` is Box64's. Anything else would be decoration.
     */
    fun environment(): Map<String, String> = linkedMapOf<String, String>().apply {
        glesVersion?.let { (major, minor) ->
            put("MESA_GL_VERSION_OVERRIDE", "$major.$minor")
        }

        when (backend.id) {
            GraphicsBackend.LLVMPIPE.id -> {
                put("LIBGL_ALWAYS_SOFTWARE", "1")
                put("GALLIUM_DRIVER", "llvmpipe")
            }
            GraphicsBackend.VIRGL_WINE_D3D.id -> put("GALLIUM_DRIVER", "virgl")
            // The native wrapper and the Vulkan path both use the device's own
            // driver, so neither needs Mesa told what to do.
        }

        if (maliMmapWorkaround) put("BOX64_MMAP32", "0")
    }

    /** The Wine registry settings this configuration needs. */
    fun registrySettings(): List<RegistrySetting> = buildList {
        glesVersion?.let { (major, minor) -> add(RegistrySetting.maxGlVersion(major, minor)) }
        add(RegistrySetting.videoMemory(videoMemoryMb))
        add(RegistrySetting.offscreenRendering(offscreenMode))
    }

    /** One line for the report, including the texture warning where it applies. */
    fun summary(): String = buildString {
        append(backend.title)
        append(" · textures: ").append(textureRoute.title)
        if (textureRoute == TextureCompatibility.SOFTWARE) {
            append(" (slow: every texture is decompressed on the CPU)")
        }
        if (maliMmapWorkaround) append(" · BOX64_MMAP32=0")
    }

    companion object {

        /**
         * Video memory to claim. Bloodlines was built for hardware with 128 MB,
         * and WineD3D uses the figure to decide how much it may cache, so a
         * number in that region is honest.
         */
        const val DEFAULT_VIDEO_MEMORY_MB = 512

        /** Builds the configuration for [backend] on [capabilities]. */
        fun forBackend(
            backend: GraphicsBackend,
            capabilities: RuntimeCapabilities,
        ): GraphicsConfiguration = GraphicsConfiguration(
            backend = backend,
            textureRoute = backend.textureRoute(capabilities),
            glesVersion = capabilities.glesMajorMinor?.let { (major, minor) ->
                // Cap at 3.1 unless the device reports more: WineD3D's GL
                // backend is happier with a version it fully implements than
                // with the newest one the driver advertises.
                if (major > 3 || (major == 3 && minor > 1)) 3 to 1 else major to minor
            },
            maliMmapWorkaround = capabilities.hasMaliKbase,
        )
    }
}
