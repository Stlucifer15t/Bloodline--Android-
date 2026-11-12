package com.bloodline.android.runtime

import com.bloodline.android.runtime.IntegrationStatus.NOT_INTEGRATED
import com.bloodline.android.runtime.IntegrationStatus.PARTIAL
import com.bloodline.android.runtime.IntegrationStatus.READY

/**
 * The catalogue of everything that has to exist before Vampire.exe can start.
 *
 * Each entry is intentionally independent: native hosting, Wine, the CPU
 * translator, graphics and audio can each be built, tested and replaced
 * without touching the others. The registry is pure Kotlin so the state of the
 * stack is unit testable without an Android device.
 *
 * Two entries changed shape after the investigation, and the change is worth
 * stating because both were assumptions rather than findings:
 *
 * * **There is no container.** The Bionic builds run Wine directly against
 *   Android's own libc, with no glibc userland and no PRoot. The entry is kept
 *   because the launcher's UI has a row for it and because some future Wine
 *   build may need one — but it is now reported as *not required* rather than
 *   as missing work.
 * * **CPU translation is WoW64, not Box86.** A 32-bit prefix with Box86 would
 *   need a 32-bit userland that no package supplies; the WoW64 layer inside a
 *   64-bit prefix needs none.
 *
 * Statuses are either *measured on the device* ([nativeHosting]) or *derived
 * from what is actually installed* (the rest). Nothing here is a static claim
 * about the APK any more, because the runtime is acquired rather than bundled.
 */
object RuntimeRegistry {

    /** What each component is currently doing. */
    data class Statuses(
        val wine: IntegrationStatus = NOT_INTEGRATED,
        val cpu: IntegrationStatus = NOT_INTEGRATED,
        val graphics: IntegrationStatus = NOT_INTEGRATED,
        val audio: IntegrationStatus = NOT_INTEGRATED,
        val storage: IntegrationStatus = NOT_INTEGRATED,

        /**
         * READY because there is nothing to install: the Bionic builds run
         * Wine against Android's own libc, with no glibc userland and no PRoot.
         */
        val container: IntegrationStatus = READY,
    )

    /** Current state of every planned runtime component. */
    fun components(
        nativeHosting: IntegrationStatus = NOT_INTEGRATED,
        statuses: Statuses = Statuses(),
    ): List<RuntimeComponent> = listOf(
        RuntimeComponent(
            id = ComponentIds.NATIVE_HOSTING,
            title = "Native execution (W^X safe)",
            detail = when (nativeHosting) {
                READY -> "A bundled native binary runs from the APK's library folder, which is " +
                    "how the runtime's own helpers are loaded."
                PARTIAL -> "Checking whether a bundled native binary can run on this device…"
                NOT_INTEGRATED -> "Not verified yet: the bundled probe did not run successfully " +
                    "on this device."
            },
            status = nativeHosting,
        ),
        RuntimeComponent(
            id = ComponentIds.WINE,
            title = "Wine — Windows API layer",
            detail = when (statuses.wine) {
                READY -> "Wine 11.3 (ARM64EC) is installed. The prefix is 64-bit; the game's " +
                    "32-bit code runs through the WoW64 layer below."
                PARTIAL -> "The Wine package is installed but its loader could not be found."
                NOT_INTEGRATED -> "Not installed. Downloaded once from the upstream project " +
                    "that builds it (LGPL-2.1-or-later); nothing is bundled in this APK."
            },
            status = statuses.wine,
        ),
        RuntimeComponent(
            id = ComponentIds.CPU_TRANSLATION,
            title = "WoW64 — 32-bit x86 on ARM64",
            detail = when (statuses.cpu) {
                READY -> "WOWBox64 is installed, so the game's 32-bit code is hosted inside " +
                    "the 64-bit prefix. No 32-bit userland is needed."
                PARTIAL -> "The WoW64 package is installed but its DLL could not be found."
                NOT_INTEGRATED -> "Not installed. Vampire.exe is 32-bit, and this is the layer " +
                    "that lets a 64-bit Wine host it. Box86 is not used: it would need a " +
                    "32-bit userland this device does not have."
            },
            status = statuses.cpu,
        ),
        RuntimeComponent(
            id = ComponentIds.CONTAINER,
            title = "Rootless container",
            detail = "Not required. Wine is built against Android's own Bionic libc, so there " +
                "is no glibc userland and no PRoot in this runtime.",
            status = statuses.container,
        ),
        RuntimeComponent(
            id = ComponentIds.GRAPHICS,
            title = "Graphics translation",
            detail = when (statuses.graphics) {
                READY -> "WineD3D over the device's own OpenGL ES driver, with BCn (DXT) " +
                    "textures transcoded — the game's 2004-era textures are all DXT1/DXT5."
                PARTIAL -> "Graphics is configured but the device has not been measured yet."
                NOT_INTEGRATED -> "Not configured. The Vulkan path stays optional and is never " +
                    "enabled automatically."
            },
            status = statuses.graphics,
        ),
        RuntimeComponent(
            id = ComponentIds.AUDIO,
            title = "Audio bridge",
            detail = when (statuses.audio) {
                READY -> "Wine's ALSA driver in the prefix, routed to Android through the " +
                    "plugin this app implements."
                PARTIAL -> "Audio is configured but the device has not been measured yet."
                NOT_INTEGRATED -> "Not configured yet."
            },
            status = statuses.audio,
        ),
        RuntimeComponent(
            id = ComponentIds.STORAGE,
            title = "Storage & game files",
            detail = when (statuses.storage) {
                READY -> "The game was found and is read in place, never copied. Everything " +
                    "the runtime writes stays in this app's own folder."
                PARTIAL -> "Detecting the game in app storage and in a folder you pick."
                NOT_INTEGRATED -> "No game installation has been found yet."
            },
            status = statuses.storage,
        ),
    )

    /**
     * Derives the statuses from a [provider] and the last device measurement.
     *
     * The mapping is deliberately pessimistic: "the package is installed and
     * its files resolve" is READY, "the package is installed but the files are
     * gone" is PARTIAL, and "not installed" is NOT_INTEGRATED. A user reading
     * the screen can therefore tell those three apart.
     */
    fun forProvider(
        provider: RuntimeProvider?,
        capabilities: RuntimeCapabilities,
        nativeHosting: IntegrationStatus = NOT_INTEGRATED,
        storage: IntegrationStatus = NOT_INTEGRATED,
    ): List<RuntimeComponent> {
        val installed = provider?.isInstalled() == true
        val usable = provider?.isUsable(WineFlavour.DEFAULT) == true

        val packageStatus = when {
            usable -> READY
            installed -> PARTIAL
            else -> NOT_INTEGRATED
        }

        // Graphics and audio need no download, but they do need a measurement:
        // picking a backend for a device we know nothing about is a guess.
        val measured = if (capabilities.measured) READY else PARTIAL

        return components(
            nativeHosting = nativeHosting,
            statuses = Statuses(
                wine = packageStatus,
                cpu = packageStatus,
                graphics = if (installed) measured else NOT_INTEGRATED,
                audio = if (installed) measured else NOT_INTEGRATED,
                storage = storage,
            ),
        )
    }

    /**
     * Aggregate status of an already-built list.
     *
     * Separate from [overallStatus] so a caller that has already derived the
     * components — from [forProvider], say — gets an aggregate that agrees with
     * the rows it is about to draw, rather than a second, differently-derived
     * answer.
     */
    fun overallOf(components: List<RuntimeComponent>): IntegrationStatus {
        val statuses = components.map { it.status }
        return when {
            statuses.all { it == READY } -> READY
            statuses.any { it != NOT_INTEGRATED } -> PARTIAL
            else -> NOT_INTEGRATED
        }
    }

    /** Aggregate status: READY only when every component is READY. */
    fun overallStatus(
        nativeHosting: IntegrationStatus = NOT_INTEGRATED,
        statuses: Statuses = Statuses(),
    ): IntegrationStatus {
        val all = components(nativeHosting, statuses).map { it.status }
        return when {
            all.all { it == READY } -> READY
            all.any { it != NOT_INTEGRATED } -> PARTIAL
            else -> NOT_INTEGRATED
        }
    }
}
