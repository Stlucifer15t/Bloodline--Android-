package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.Requirement
import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.RuntimeModule

/**
 * The ways 32-bit x86 instructions get executed on an ARM64 phone.
 *
 * This is the layer the brief singles out, and for a good reason: `Vampire.exe`
 * is a **32-bit** Windows binary, so the obvious answer — Box64 — is not by
 * itself an answer. Three routes exist, and they differ in the dependency that
 * makes them fragile:
 *
 * * **[BOX86]** — the established route. Box86 is itself a 32-bit ARM program,
 *   so it needs a 32-bit userland *and* a working 32-bit execution path. That
 *   second dependency is the risky one: 32-bit support is exactly what kernels
 *   are dropping, and exactly what `runtime_probe` measures on this device.
 * * **[BOX64_WOW64]** — Box64 built with its WOW64 option can host 32-bit
 *   Windows DLLs directly. Same job, no 32-bit userland needed.
 * * **[FEX_ARM64EC]** — FEX supports both 32- and 64-bit x86 and, in ARM64EC
 *   mode, plugs into Wine as a CPU module so Wine services the Windows system
 *   calls. No separate x86 rootfs at all.
 *
 * Whichever is used, it runs *under* Wine, not instead of it. Nothing here
 * claims one is faster — that claim requires a measurement on the CK6.
 */
enum class CpuTranslator(
    override val id: String,
    override val title: String,
    val summary: String,

    /** True when the route needs a 32-bit userland to be present. */
    val needs32BitUserland: Boolean,
) : RuntimeModule {

    BOX86(
        id = "box86",
        title = "Box86 (32-bit x86)",
        summary = "The established route for 32-bit Windows games. Box86 is " +
            "itself a 32-bit ARM program, so it needs a 32-bit userland and a " +
            "kernel that still accepts 32-bit processes.",
        needs32BitUserland = true,
    ),

    BOX64_WOW64(
        id = "box64-wow64",
        title = "Box64 (WOW64 mode)",
        summary = "Box64 built with its WOW64 option, hosting the game's " +
            "32-bit Windows DLLs directly. Runs the same 32-bit code without " +
            "needing a 32-bit userland.",
        needs32BitUserland = false,
    ),

    FEX_ARM64EC(
        id = "fex-arm64ec",
        title = "FEX (ARM64EC module)",
        summary = "FEX loaded into an ARM64 Wine as a CPU module, so Wine " +
            "itself services the Windows system calls. Handles 32- and 64-bit " +
            "x86 and needs no separate x86 rootfs.",
        needs32BitUserland = false,
    ),
    ;

    override fun requirements(capabilities: RuntimeCapabilities): List<Requirement> =
        buildList {
            add(
                Requirement(
                    "64-bit ARM process",
                    capabilities.primaryAbi.contains("arm64") ||
                        capabilities.primaryAbi.contains("aarch64"),
                    measured = capabilities.measured,
                ),
            )
            if (needs32BitUserland) {
                add(
                    Requirement(
                        "A 32-bit ABI on this device",
                        capabilities.has32BitAbi,
                        measured = capabilities.measured,
                    ),
                )
                add(
                    Requirement(
                        "32-bit processes actually run here",
                        capabilities.canExecute32Bit,
                        measured = capabilities.measured,
                    ),
                )
            }
            add(
                Requirement(
                    "$title shipped in the APK",
                    satisfied = false,
                    measured = false,
                ),
            )
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
        val ENTRIES: List<CpuTranslator> get() = entries.toList()

        fun fromId(id: String): CpuTranslator? = entries.firstOrNull { it.id == id }

        /**
         * The translator to prefer on this device.
         *
         * Prefers a route that does **not** need a 32-bit userland when the
         * device cannot provide one, because that dependency is the one most
         * likely to disappear. When the device happily runs 32-bit code, Box86
         * stays first: it is the best-exercised path for exactly this kind of
         * 2004-era 32-bit game.
         */
        fun recommendedFor(capabilities: RuntimeCapabilities): CpuTranslator =
            entries.firstOrNull {
                !it.needs32BitUserland || capabilities.canExecute32Bit
            } ?: BOX86
    }
}
