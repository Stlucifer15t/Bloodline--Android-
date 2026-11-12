package com.bloodline.android.runtime.packaging

/**
 * The runtime packages this launcher knows how to acquire.
 *
 * Every URL here was read out of an upstream repository during the Milestone 3
 * investigation — from `The412Banner/winlator-contents` (`contents.json`) and
 * `The412Banner/Nightlies` release assets — not invented. Each is pinned to a
 * specific release tag so it does not silently change under us.
 *
 * Two deliberate choices:
 *
 * 1. **Wine is x86_64, and the 32-bit game runs through WoW64.** After the
 *    investigation this is the mainstream path: WOWBox64 or FEX supplies the
 *    WoW64 layer inside a 64-bit prefix. Box86 and a 32-bit prefix are not used,
 *    and no package here provides them.
 * 2. **The Mali Vulkan driver is deliberately absent.** `soulexit`'s Bifrost
 *    PanVK build declares no licence, so we cannot redistribute or even
 *    auto-download it. It is user-installed or nothing.
 */
object RuntimePackageCatalog {

    private const val NIGHTLIES = "https://github.com/The412Banner/Nightlies/releases/download"
    private const val CONTENTS = "https://github.com/The412Banner/winlator-contents/releases/download"

    private val WINE_LICENCE = Licence(
        spdx = "LGPL-2.1-or-later",
        name = "GNU Lesser General Public License v2.1 or later",
        referenceUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html",
        sourceUrl = "https://www.winehq.org",
        attribution = "Copyright (C) 1993-2026 The Wine Project",
    )

    private val BOX64_LICENCE = Licence(
        spdx = "MIT",
        name = "MIT License",
        referenceUrl = "https://opensource.org/licenses/MIT",
        sourceUrl = "https://github.com/ptitSeb/box64",
        attribution = "Copyright (c) ptitSeb",
    )

    private val FEX_LICENCE = Licence(
        spdx = "MIT",
        name = "MIT License",
        referenceUrl = "https://opensource.org/licenses/MIT",
        sourceUrl = "https://github.com/FEX-Emu/FEX",
        attribution = "Copyright (c) FEX-Emu contributors",
    )

    /**
     * Wine 10, x86_64 build.
     *
     * Ref build (the plain Wine rather than Proton) because it is the smaller,
     * more predictable base for a single non-Steam game. The prefix is 64-bit;
     * 32-bit binaries are served by [WOWBOX64].
     */
    val WINE_10 = RuntimePackage(
        id = "wine-10-x86_64",
        type = PackageType.WINE,
        name = "Wine 10 (x86_64)",
        version = "10.0",
        abi = "arm64-v8a",
        url = "$CONTENTS/wine-v1/wine-10-x86_64-ref.wcp",
        sizeBytes = 269L * 1024 * 1024,
        licence = WINE_LICENCE,
        target = "packages/wine-10-x86_64",
        required = true,
        note = "64-bit prefix. The game's 32-bit code runs through WoW64, not a 32-bit prefix.",
    )

    /** Box64, built against Android's Bionic libc. Runs the x86_64 emulation. */
    val BOX64_BIONIC = RuntimePackage(
        id = "box64-bionic",
        type = PackageType.CPU_EMULATOR,
        name = "Box64 (Bionic)",
        version = "0.4.5",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/nightly-latest/Box64-0.4.5-79d07c69e-Bionic.zip",
        sizeBytes = 5L * 1024 * 1024,
        licence = BOX64_LICENCE,
        target = "packages/box64",
        required = true,
        note = "Built for Android's Bionic libc, so no glibc root filesystem is needed.",
    )

    /**
     * WOWBox64 — Box64 built with WoW64 support. **This is what lets a 32-bit
     * executable run**, by hosting its Windows DLLs inside the 64-bit prefix.
     */
    val WOWBOX64 = RuntimePackage(
        id = "wowbox64",
        type = PackageType.WOW64,
        name = "WOWBox64",
        version = "0.4.5",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/nightly-latest/WOWBox64-0.4.5-981b49a09.zip",
        sizeBytes = 2L * 1024 * 1024,
        licence = BOX64_LICENCE,
        target = "packages/wowbox64",
        required = true,
        note = "Extracted into the prefix's windows/system32, which is where WoW64 looks for it.",
    )

    /**
     * FEX-Emu, the alternative 32-bit route.
     *
     * Not required: it is offered as a swap for [WOWBOX64] when Box64's WoW64
     * misbehaves. Both provide the same capability by different means, which is
     * exactly why the emulator is a replaceable module rather than a fixed step.
     */
    val FEX = RuntimePackage(
        id = "fex-2611",
        type = PackageType.WOW64,
        name = "FEX-Emu",
        version = "2611",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/nightly-latest/FEX-2611+45-Nightly-ab135df09.zip",
        sizeBytes = 2L * 1024 * 1024,
        licence = FEX_LICENCE,
        target = "packages/fex",
        required = false,
        note = "Alternative to WOWBox64. Handles 32- and 64-bit x86 without a 32-bit userland.",
    )

    /**
     * The packages the game needs, in the order they should be acquired.
     *
     * Wine first because it is by far the largest: if it fails, the user finds
     * out immediately rather than after downloading everything else.
     */
    val ALL: List<RuntimePackage> = listOf(
        WINE_10,
        BOX64_BIONIC,
        WOWBOX64,
        FEX,
    )

    /** Only the packages the game cannot start without. */
    val REQUIRED: List<RuntimePackage> get() = ALL.filter { it.required }

    fun byId(id: String): RuntimePackage? = ALL.firstOrNull { it.id == id }

    /** Total download for [packages], for the confirmation the UI shows first. */
    fun totalBytes(packages: List<RuntimePackage>): Long = packages.sumOf { it.sizeBytes }

    /** Every distinct licence, so NOTICE can be generated rather than hand-kept. */
    fun licences(): List<Licence> = ALL.map { it.licence }.distinctBy { it.spdx }
}
