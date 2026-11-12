package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.IntegrationStatus
import com.bloodline.android.runtime.Requirement
import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.RuntimeModule

/**
 * How x86 instructions get executed, and how a 32-bit Windows application gets
 * hosted.
 *
 * This file was rewritten after the Milestone 3 investigation, and the change
 * matters enough to state plainly:
 *
 * > **Box86 is not the answer, and it is no longer the recommendation.**
 *
 * The earlier version preferred Box86 whenever the device could run 32-bit
 * processes, on the reasoning that `Vampire.exe` is 32-bit so a 32-bit
 * translator is the natural fit. Inspecting what the working Android runtimes
 * actually ship showed the opposite: **none of them ships Box86.** They run
 * 32-bit Windows applications through **WoW64 inside a 64-bit prefix** —
 * WOWBox64 (Box64 built with WoW64 support) or FEX-Emu.
 *
 * That is better for our constraint, not worse. Box86 *is* a 32-bit ARM program,
 * so it needs a 32-bit userland — the exact dependency this project had flagged
 * as fragile, because 32-bit support is what kernels are dropping. The WoW64
 * routes need no 32-bit userland at all.
 *
 * Box86 stays in the catalogue because it remains a legitimate way to run
 * 32-bit code, and because being wrong about it once is a reason to keep the
 * option visible rather than delete it.
 */
enum class CpuTranslator(
    override val id: String,
    override val title: String,
    val summary: String,

    /**
     * How 32-bit Windows applications are hosted.
     *
     * `null` means this translator cannot host a 32-bit guest at all, which is
     * what makes plain Box64 unsuitable on its own for `Vampire.exe`.
     */
    val wow64: WoW64Mode?,

    /** True when the route needs a 32-bit userland to be present. */
    val needs32BitUserland: Boolean,
) : RuntimeModule {

    WOWBOX64(
        id = "wowbox64",
        title = "WOWBox64 (Box64 + WoW64)",
        summary = "Box64 built with WoW64 support. Runs 32-bit Windows applications " +
            "inside a 64-bit Wine prefix, which is how the Mali-focused Android " +
            "runtimes run 32-bit games. Needs no 32-bit userland.",
        wow64 = WoW64Mode.WOWBOX64,
        needs32BitUserland = false,
    ),

    FEX(
        id = "fex-wow64",
        title = "FEX-Emu (WoW64)",
        summary = "FEX-Emu supplying both instruction translation and the WoW64 layer. " +
            "Handles 32- and 64-bit x86 and needs no separate emulator binary. The " +
            "fallback when WOWBox64 misbehaves.",
        wow64 = WoW64Mode.FEX,
        needs32BitUserland = false,
    ),

    BOX64(
        id = "box64",
        title = "Box64 (64-bit only)",
        summary = "Box64 without WoW64 support. Fast for 64-bit Windows programs, and " +
            "useless for Vampire.exe on its own: the game is 32-bit.",
        wow64 = null,
        needs32BitUserland = false,
    ),

    BOX86(
        id = "box86",
        title = "Box86 (32-bit x86, legacy)",
        summary = "The route this project originally planned: a 32-bit translator in a " +
            "32-bit Wine prefix. Box86 is itself a 32-bit ARM program, so it needs a " +
            "32-bit userland that Android devices increasingly do not provide. No " +
            "package in the catalog supplies it.",
        wow64 = WoW64Mode.WIN32_PREFIX,
        needs32BitUserland = true,
    ),
    ;

    /** Whether this translator can host our 32-bit executable. */
    fun canRun32Bit(): Boolean = wow64 != null

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
                    "Can host a 32-bit Windows application",
                    canRun32Bit(),
                    measured = false,
                ),
            )
            add(
                Requirement(
                    "$title package installed",
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
         * The translator to prefer: always WOWBox64.
         *
         * Deliberately unconditional. An earlier draft switched to FEX on a
         * Mali device that could not execute 32-bit processes, on the theory
         * that Box64's 32-bit address-space handling is the problem the Mali
         * builds work around. Reading what those builds actually do showed
         * otherwise: they keep Box64 and set `BOX64_MMAP32=0`. The
         * accommodation is a Box64 setting, not a different translator.
         *
         * Guessing a translator from a device measurement would also be the
         * wrong shape of decision: nothing measured on a phone can tell us
         * which emulator handles a given game better. FEX is offered as an
         * explicit alternative and as a retry after a WOWBox64 failure, which
         * is a decision made from evidence rather than from a spec sheet.
         *
         * Box86 is **never** recommended. It remains listed and selectable,
         * but its 32-bit-userland dependency makes it the worst default on any
         * modern Android device.
         */
        fun recommendedFor(capabilities: RuntimeCapabilities): CpuTranslator = WOWBOX64

        /**
         * What to try when [translator] has failed, or null when there is
         * nothing left.
         *
         * The other WoW64 route, and only that: swapping a working WoW64 layer
         * for Box86 would trade a working configuration for one that needs a
         * 32-bit userland.
         */
        fun fallbackFor(translator: CpuTranslator): CpuTranslator? = when (translator) {
            WOWBOX64 -> FEX
            FEX -> WOWBOX64
            BOX64, BOX86 -> WOWBOX64
        }

        /**
         * Translators that can actually host the game, in preference order.
         *
         * Used by the launch gate so it can refuse a configuration that could
         * never work — plain Box64 with a 32-bit executable, for instance.
         */
        fun capableOf32Bit(): List<CpuTranslator> = entries.filter { it.canRun32Bit() }
    }
}

/** How a 32-bit Windows guest is hosted. */
enum class WoW64Mode(
    /** The DLL Wine loads to host 32-bit guests, or empty for a 32-bit prefix. */
    val dllName: String,
    val description: String,
) {
    WOWBOX64("wowbox64.dll", "Box64's WoW64 layer inside a 64-bit prefix"),
    FEX("libwow64fex.dll", "FEX-Emu's WoW64 layer inside a 64-bit prefix"),
    WIN32_PREFIX("", "A 32-bit prefix; no WoW64 layer involved"),
}
