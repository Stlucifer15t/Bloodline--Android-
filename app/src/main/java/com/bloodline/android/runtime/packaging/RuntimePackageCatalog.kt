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
 * **The two checksums were measured, not published.** Upstream publishes no
 * digest for these assets. These were taken from the bytes a real download
 * returned, in CI, by the verification step that fetches the packages and
 * reports what it found. That makes them pins rather than signatures: they
 * detect a changed or substituted file, which is what a pin is for, but they
 * are not an upstream attestation and should not be described as one. The
 * packages with no checksum below install as *unverified* and say so.
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
     * Wine 11.3, ARM64EC build. **This is the Wine the runtime targets.**
     *
     * ARM64EC rather than x86_64, and the distinction decides the whole launch
     * shape. Reading the launcher in the Mali builds: with an ARM64EC Wine the
     * command is just `wine <exe>`, because Wine's own ARM64 code runs natively
     * and only the 32-bit guest needs an emulator — supplied through `HODLL`.
     * With an x86_64 Wine the entire `wine` binary is x86_64, so the command
     * becomes `box64 wine <exe>`.
     *
     * ARM64EC is the better fit for this project: it needs no 32-bit userland,
     * it puts a single emulator between the game and the CPU rather than two,
     * and it is the configuration the 32-bit-only path (`HODLL`) is built for.
     *
     * The `.wcp.xz` suffix is not decoration: upstream publishes the same
     * package as plain `.wcp` too, and only the suffixed one is stated to be
     * XZ-compressed. We take the format that is declared rather than guessing.
     *
     * **The layout below is observed, not assumed.** This package was fetched
     * and unpacked in CI: 2210 files, and no `windows` directory at all. Wine
     * keeps its Windows system files under `lib/wine/`, split by the
     * architecture they were built for:
     *
     * * `bin/wine` — the loader, and the one to use
     * * `lib/wine/i386-windows` — 32-bit x86, where `cmd.exe` is
     * * `lib/wine/aarch64-windows` — 64-bit ARM, where `wineboot.exe` is
     * * `share/wine/wine.inf`
     * * `prefixPack.txz` — a prebuilt prefix
     *
     * The 32-bit directory matters more than it sounds: `cmd.exe` there is a
     * PE 32-bit x86 Windows console program, and it is what the WoW64 stage
     * runs to prove the 32-bit path works.
     */
    val WINE_ARM64EC = RuntimePackage(
        id = "wine-11.3-arm64ec",
        type = PackageType.WINE,
        name = "Wine 11.3 (ARM64EC)",
        version = "11.3",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/Proton/wine/wine-11.3-arm64ec.wcp.xz",
        sizeBytes = 226_178_252L,
        sha256 = "9fe4cfcd5fa4a9ffe5131620843046ec50b8db980aaa8a51cbcac41270c3a6eb",
        licence = WINE_LICENCE,
        target = "packages/wine-11.3-arm64ec",
        required = true,
        note = "64-bit prefix, ARM64EC Wine. The game's 32-bit code runs through the " +
            "WoW64 layer named by HODLL, not through a 32-bit prefix.",
    )

    /**
     * Wine 11.3, x86_64 build: the alternative Wine.
     *
     * Not required. It is the conventional Winlator arrangement — `box64 wine`
     * — and it is offered because some games behave differently under it. It
     * needs [BOX64_BIONIC] and it does not use `HODLL`, because Box64 is
     * already emulating the whole Wine process.
     */
    val WINE_X86_64 = RuntimePackage(
        id = "wine-11.3-x86_64",
        type = PackageType.WINE,
        name = "Wine 11.3 (x86_64)",
        version = "11.3",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/Proton/wine/wine-11.3-x86_64.wcp",
        sizeBytes = 207_779_756L,
        licence = WINE_LICENCE,
        target = "packages/wine-11.3-x86_64",
        required = false,
        note = "Needs Box64 in front of it. Offered as an alternative to the ARM64EC build.",
    )

    /**
     * Box64 as a standalone emulator, built against Android's Bionic libc.
     *
     * Not required by the ARM64EC path: there, Box64 arrives as [WOWBOX64], a
     * WoW64 DLL rather than a binary this app executes. This package is only
     * needed to run the x86_64 Wine build, which is itself the alternative.
     */
    val BOX64_BIONIC = RuntimePackage(
        id = "box64-bionic",
        type = PackageType.CPU_EMULATOR,
        name = "Box64 (Bionic)",
        version = "0.4.5",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/nightly-latest/Box64-0.4.5-79d07c69e-Bionic.zip",
        sizeBytes = 5_103_225L,
        licence = BOX64_LICENCE,
        target = "packages/box64",
        required = false,
        note = "Built for Android's Bionic libc, so no glibc root filesystem is needed. " +
            "Only needed to run the x86_64 Wine build.",
    )

    /**
     * WOWBox64 — Box64 built with WoW64 support. **This is what lets a 32-bit
     * executable run**, by hosting its Windows DLLs inside the 64-bit prefix.
     */
    val WOWBOX64 = RuntimePackage(
        id = "wowbox64",
        type = PackageType.WOW64,
        name = "WOWBox64",
        version = "0.4.4",
        abi = "arm64-v8a",
        url = "$NIGHTLIES/Wowbox64/WOWBox64-0.4.4.wcp",
        sizeBytes = 1_029_612L,
        sha256 = "8575b947fa674298289ae8089f3e9f2fb936369c88dba07bf223f2ba4f579e52",
        licence = BOX64_LICENCE,
        target = "packages/wowbox64",
        required = true,
        note = "Copied into the prefix's windows/system32, which is where WoW64 " +
            "looks for the DLL named by HODLL.",
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
        sizeBytes = 2_078_400L,
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
        WINE_ARM64EC,
        WOWBOX64,
        FEX,
        WINE_X86_64,
        BOX64_BIONIC,
    )

    /** Only the packages the game cannot start without. */
    val REQUIRED: List<RuntimePackage> get() = ALL.filter { it.required }

    fun byId(id: String): RuntimePackage? = ALL.firstOrNull { it.id == id }

    /** Total download for [packages], for the confirmation the UI shows first. */
    fun totalBytes(packages: List<RuntimePackage>): Long = packages.sumOf { it.sizeBytes }

    /**
     * The packages needed to run the game through [WINE_ARM64EC].
     *
     * Two packages: a Wine and a WoW64 layer. That is genuinely all the
     * 32-bit path needs, which is the point of choosing WoW64 over Box86 —
     * the latter would also need a 32-bit userland that no package supplies.
     */
    fun requiredFor(wine: RuntimePackage): List<RuntimePackage> =
        if (wine == WINE_X86_64) listOf(wine, BOX64_BIONIC, WOWBOX64)
        else listOf(wine, WOWBOX64)

    /** Every distinct licence, so NOTICE can be generated rather than hand-kept. */
    fun licences(): List<Licence> = ALL.map { it.licence }.distinctBy { it.spdx }
}
