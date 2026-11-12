package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.Requirement
import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.RuntimeModule

/**
 * The ways Direct3D 9 can be made to land on a Mali-G52.
 *
 * The ordering of these entries is the ordering of the fallback chain, and the
 * rules behind it are recorded in `docs/RUNTIME_ARCHITECTURE.md` §5. Summarised:
 *
 * * **[VIRGL_WINE_D3D]** is the default. Panfrost's OpenGL ES 3.1 is
 *   Khronos-conformant on the Mali-G52, which is the only conformance statement
 *   that covers this GPU. VirGL is also what the Mali-specific builds of the
 *   established Android Wine containers use, because Vulkan routes do not work
 *   properly on many Mali devices.
 * * **[PANVK_DXVK]** is experimental. PanVK upstream is conformant only on
 *   Mali-G610; on Bifrost v7 it reports itself as a non-conformant
 *   implementation that is "not well tested on v7". Community work has got it
 *   rendering on an almost identical device (Mali-G52 MC2 over `/dev/mali0`,
 *   no root), but broad game compatibility is not validated. It is therefore
 *   **never selected automatically** — only an explicit user choice can reach
 *   it, and only when the device measurement says it might work.
 * * **[LLVMPIPE]** renders on the CPU. Useless for playing, and valuable as a
 *   diagnostic: if llvmpipe draws and VirGL does not, the fault is in the GPU
 *   path rather than in Wine or in the game.
 */
enum class GraphicsBackend(
    override val id: String,
    override val title: String,

    /** One paragraph for the UI and for reports. */
    val summary: String,

    /**
     * True when this backend is unproven on the target hardware. An
     * experimental backend is never chosen automatically, whatever the device
     * reports: a device measurement can prove a backend *might* work, it cannot
     * prove it *does*.
     */
    val experimental: Boolean,
) : RuntimeModule {

    VIRGL_WINE_D3D(
        id = "virgl-wined3d",
        title = "VirGL + WineD3D (OpenGL ES)",
        summary = "Direct3D 9 translated to OpenGL by WineD3D and rendered through " +
            "Mesa's VirGL. The default on Mali: Panfrost's OpenGL ES 3.1 is " +
            "conformant on the Mali-G52. Practical for DirectX 9-era games.",
        experimental = false,
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
            Requirement(
                "WineD3D shipped in the APK",
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
            "and warns that it is not well tested on this architecture. Never " +
            "selected automatically.",
        experimental = true,
    ) {
        override fun requirements(capabilities: RuntimeCapabilities) = listOf(
            Requirement(
                "A Vulkan implementation on this GPU",
                capabilities.vulkanUsable,
                measured = capabilities.measured,
            ),
            Requirement(
                "A PanVK driver package for this GPU",
                // kbase is the route the community drivers take; a DRM render
                // node is the upstream route. Either would do, neither proves
                // a driver is installed.
                capabilities.hasMaliKbase || capabilities.hasDriRenderNode,
                measured = capabilities.measured,
            ),
            Requirement(
                "DXVK shipped in the APK",
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
     * the whole point. Every backend also needs binaries that do not ship yet;
     * folding those in here would make every backend "unsupported" and the
     * answer useless. So this says "the device has what it takes", while
     * [status] separately says "and the build has what it takes".
     *
     * Weaker than "will work", then, but not infinitely weak: a device that has
     * not been measured at all supports nothing, because unmeasured is not the
     * same as fine.
     */
    fun isSupportedBy(capabilities: RuntimeCapabilities): Boolean {
        if (!capabilities.measured) return false
        return requirements(capabilities).filter { it.measured }.all { it.satisfied }
    }

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

        /** What the launcher and the settings screen offer, in fallback order. */
        val ENTRIES: List<GraphicsBackend> get() = entries.toList()

        fun fromId(id: String): GraphicsBackend? = entries.firstOrNull { it.id == id }

        /**
         * The backend to try first when the user has not insisted on one.
         *
         * The rule, which is the point of this whole file: take the first
         * non-experimental backend the device supports. An experimental backend
         * is only ever reachable through an explicit choice.
         */
        fun recommendedFor(capabilities: RuntimeCapabilities): GraphicsBackend? =
            entries.firstOrNull { !it.experimental && it.isSupportedBy(capabilities) }
                ?: entries.firstOrNull { !it.experimental }
    }
}

/**
 * What the selector decided, and why.
 *
 * The reason is part of the result rather than a log line, because it ends up
 * on screen: a user who asked for Vulkan and got OpenGL deserves to be told.
 */
data class GraphicsSelection(
    val backend: GraphicsBackend?,
    val wasFallback: Boolean,
    val reason: String,
) {
    /** True when nothing usable was found at all. */
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
                val fallback = GraphicsBackend.recommendedFor(capabilities)
                GraphicsSelection(
                    backend = fallback,
                    wasFallback = true,
                    reason = "${preferred.title} is not usable on this device " +
                        "(${preferred.blocker(capabilities)}).",
                )
            }
        }

        val recommended = GraphicsBackend.recommendedFor(capabilities)
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
                reason = "No backend is fully supported here, so the default was " +
                    "kept. ${recommended.blocker(capabilities)}",
            )
        }
    }
}
