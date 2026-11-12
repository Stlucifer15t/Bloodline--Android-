package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.Requirement
import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.RuntimeModule
import com.bloodline.android.runtime.graphics.TextureCompatibility

/**
 * The ways Direct3D 9 can be made to land on a Mali-G52.
 *
 * Rewritten after the Milestone 3 investigation. The change: **the default is a
 * native GLES wrapper, not VirGL.** The earlier version proposed VirGL because
 * Mesa's Panfrost GLES is conformant on this GPU, which is true. But the
 * projects that specialise in Mali do not use VirGL — Winlator Mali *removed*
 * it in 1.2 in favour of a native C++ EGL renderer, and offers only "Wrapper"
 * variants. Loading the vendor's own GLES driver through a wrapper is both
 * simpler and closer to the metal than piping everything through VirGL.
 *
 * The principle from Milestone 2 survives unchanged: keep a working GLES path as
 * the default, never auto-select the experimental Vulkan path.
 */
enum class GraphicsBackend(
    override val id: String,
    override val title: String,
    val summary: String,

    /**
     * True when this backend is unproven on the target hardware. An
     * experimental backend is never chosen automatically, whatever the device
     * reports: a measurement can prove a backend *might* work, never that it
     * does.
     */
    val experimental: Boolean,

    /** True when this backend renders through OpenGL ES rather than Vulkan. */
    val usesGles: Boolean,
) : RuntimeModule {

    GLES_WRAPPER_WINE_D3D(
        id = "gles-wrapper-wined3d",
        title = "GLES wrapper + WineD3D",
        summary = "Direct3D 9 translated to OpenGL by WineD3D and rendered through the " +
            "device's own GLES driver. The default on Mali: this is what the " +
            "Mali-focused Android runtimes use, and it needs no Mesa in between.",
        experimental = false,
        usesGles = true,
    ) {
        override fun requirements(capabilities: RuntimeCapabilities) = listOf(
            Requirement(
                "OpenGL ES 3.1 or better",
                capabilities.hasGles31,
                measured = capabilities.measured,
            ),
            Requirement(
                "WineD3D, shipped inside the Wine package",
                satisfied = false,
                measured = false,
            ),
            Requirement(
                "BCn (DXT) texture translation",
                satisfied = false,
                measured = false,
            ),
        )
    },

    VIRGL_WINE_D3D(
        id = "virgl-wined3d",
        title = "VirGL + WineD3D",
        summary = "Direct3D 9 translated to OpenGL by WineD3D and rendered through " +
            "Mesa's VirGL. Kept as an alternative: it is the better-tested route " +
            "on desktop-class Linux, and the fallback if the native wrapper " +
            "misbehaves. No longer the default on Mali.",
        experimental = false,
        usesGles = true,
    ) {
        override fun requirements(capabilities: RuntimeCapabilities) = listOf(
            Requirement(
                "OpenGL ES 3.1 or better",
                capabilities.hasGles31,
                measured = capabilities.measured,
            ),
            Requirement(
                "Mesa VirGL renderer",
                satisfied = false,
                measured = false,
            ),
        )
    },

    PANVK_DXVK(
        id = "panvk-dxvk",
        title = "PanVK + DXVK (Vulkan)",
        summary = "Direct3D 9 translated to Vulkan by DXVK on a PanVK driver. " +
            "Experimental on Bifrost v7: PanVK is conformant only on Mali-G610 " +
            "upstream. A community build reports Mali-G52 MC2 working on a Helio " +
            "G80, but declares no licence, so it cannot be bundled or fetched by " +
            "this app. Never selected automatically.",
        experimental = true,
        usesGles = false,
    ) {
        override fun requirements(capabilities: RuntimeCapabilities) = listOf(
            Requirement(
                "A Vulkan implementation on this GPU",
                capabilities.vulkanUsable,
                measured = capabilities.measured,
            ),
            Requirement(
                "A Vulkan driver package for this GPU",
                capabilities.hasMaliKbase || capabilities.hasDriRenderNode,
                measured = capabilities.measured,
            ),
            Requirement(
                "A Vulkan driver the user installed themselves",
                satisfied = false,
                measured = false,
            ),
        )
    },

    LLVMPIPE(
        id = "llvmpipe",
        title = "llvmpipe (CPU rendering)",
        summary = "Software rendering with no GPU involvement. Far too slow to " +
            "play, but it proves whether a failure is in the GPU path or " +
            "somewhere else.",
        experimental = false,
        usesGles = true,
    ) {
        override fun requirements(capabilities: RuntimeCapabilities) = listOf(
            Requirement(
                "Mesa llvmpipe shipped in the APK",
                satisfied = false,
                measured = false,
            ),
        )
    },
    ;

    /**
     * Whether this device rules this backend out.
     *
     * Only the **measured** requirements are consulted, and that distinction is
     * the whole point: every backend also needs components we may not have, and
     * folding those in here would make every backend "unsupported" and the
     * answer useless. So this says "the device has what it takes"; [status]
     * separately says "and the build has what it takes".
     *
     * A device that has not been measured supports nothing, because unmeasured
     * is not the same as fine.
     */
    fun isSupportedBy(capabilities: RuntimeCapabilities): Boolean {
        if (!capabilities.measured) return false
        return requirements(capabilities).filter { it.measured }.all { it.satisfied }
    }

    /**
     * How this backend would show the game's BCn (DXT) textures on this device.
     *
     * Kept out of [requirements] on purpose: a device with no ASTC and no ETC2
     * does not fail to run, it runs with slower software decoding. Reporting
     * the route separately lets the launcher say "textures will be decompressed
     * on the CPU" instead of silently shipping a bad frame rate.
     */
    open fun textureRoute(capabilities: RuntimeCapabilities): TextureCompatibility =
        capabilities.textureSupport

    override fun status(capabilities: RuntimeCapabilities): IntegrationStatus {
        val requirements = requirements(capabilities)
        return when {
            requirements.all { it.satisfied } -> IntegrationStatus.READY
            requirements.any { it.satisfied } -> IntegrationStatus.PARTIAL
            else -> IntegrationStatus.NOT_INTEGRATED
        }
    }

    override fun blocker(capabilities: RuntimeCapabilities): String {
        val missing = requirements(capabilities).filter { !it.satisfied }
        if (missing.isEmpty()) return ""
        return "Waiting on: " + missing.joinToString(", ") { it.description }
    }

    companion object {

        /** What the settings screen offers, in fallback order. */
        val ENTRIES: List<GraphicsBackend> get() = entries.toList()

        fun fromId(id: String): GraphicsBackend? = entries.firstOrNull { it.id == id }

        /**
         * The backend to try first when the user has not insisted on one.
         *
         * The rule, which is the point of this file: take the first
         * non-experimental backend the device supports. An experimental backend
         * is only ever reachable through an explicit choice.
         */
        fun recommendedFor(capabilities: RuntimeCapabilities): GraphicsBackend? =
            entries.firstOrNull { !it.experimental && it.isSupportedBy(capabilities) }
                ?: entries.firstOrNull { !it.experimental }
    }
}

/** What the selector decided, and why. */
data class GraphicsSelection(
    val backend: GraphicsBackend?,
    val wasFallback: Boolean,
    val reason: String,
) {
    val isEmpty: Boolean get() = backend == null
}

object GraphicsBackendSelector {

    /**
     * Chooses a backend.
     *
     * @param preferred what the user asked for, or null for "you decide".
     */
    fun select(
        capabilities: RuntimeCapabilities,
        preferred: GraphicsBackend? = null,
    ): GraphicsSelection {
        if (preferred != null) {
            return if (preferred.isSupportedBy(capabilities)) {
                GraphicsSelection(preferred, wasFallback = false, reason = "Chosen in settings.")
            } else {
                GraphicsSelection(
                    backend = recommendedFor(capabilities),
                    wasFallback = true,
                    reason = "${preferred.title} is not usable on this device " +
                        "(${preferred.blocker(capabilities)}).",
                )
            }
        }

        val recommended = recommendedFor(capabilities)
            ?: return GraphicsSelection(
                backend = null,
                wasFallback = false,
                reason = "No graphics backend is available for this device.",
            )

        return if (recommended.isSupportedBy(capabilities)) {
            GraphicsSelection(
                recommended,
                wasFallback = false,
                reason = "The safest backend this device supports.",
            )
        } else {
            GraphicsSelection(
                recommended,
                wasFallback = true,
                reason = "No backend is fully supported here, so the default was kept. " +
                    recommended.blocker(capabilities),
            )
        }
    }

    private fun recommendedFor(capabilities: RuntimeCapabilities): GraphicsBackend? =
        GraphicsBackend.recommendedFor(capabilities)
}
