package com.bloodline.android.runtime

import com.bloodline.android.runtime.IntegrationStatus.NOT_INTEGRATED
import com.bloodline.android.runtime.IntegrationStatus.PARTIAL
import com.bloodline.android.runtime.IntegrationStatus.READY

/**
 * The catalogue of everything that has to exist before Vampire.exe can start.
 *
 * Each entry is intentionally independent: native hosting, Wine, the CPU
 * translator, the container, graphics and audio can each be built, tested and
 * replaced without touching the others. The registry is pure Kotlin so the state
 * of the stack is unit testable without an Android device.
 *
 * Statuses that can be measured on the device are passed in
 * ([nativeHosting]); everything else is a static statement about the code that
 * ships in the APK.
 */
object RuntimeRegistry {

    /** Current state of every planned runtime component. */
    fun components(
        nativeHosting: IntegrationStatus = NOT_INTEGRATED,
    ): List<RuntimeComponent> = listOf(
        RuntimeComponent(
            id = ComponentIds.NATIVE_HOSTING,
            title = "Native execution (W^X safe)",
            detail = when (nativeHosting) {
                READY -> "A bundled native binary runs from the APK's library folder, which is " +
                    "how Wine and Box86 will be launched."
                PARTIAL -> "Checking whether a bundled native binary can run on this device…"
                NOT_INTEGRATED -> "Not verified yet: the bundled probe did not run successfully " +
                    "on this device."
            },
            status = nativeHosting,
        ),
        RuntimeComponent(
            id = ComponentIds.WINE,
            title = "Wine — Windows API layer",
            detail = "Planned: Wine 10.x built for Android (LGPL-2.1+). Nothing is bundled today.",
            status = NOT_INTEGRATED,
        ),
        RuntimeComponent(
            id = ComponentIds.CPU_TRANSLATION,
            title = "Box86 / Box64 — x86 → ARM64",
            detail = "Planned. Vampire.exe is a 32-bit binary, so Box86 inside a 32-bit Wine " +
                "prefix is required; Box64 on its own cannot run it.",
            status = NOT_INTEGRATED,
        ),
        RuntimeComponent(
            id = ComponentIds.CONTAINER,
            title = "Rootless container",
            detail = "Planned: glibc userland + PRoot. Android 10+ W^X forbids executing files " +
                "from app data, so binaries ship as lib*.so in the APK.",
            status = NOT_INTEGRATED,
        ),
        RuntimeComponent(
            id = ComponentIds.GRAPHICS,
            title = "Graphics translation",
            detail = "Planned: VirGL + WineD3D for DirectX 9. Turnip is Adreno only and PanVK is " +
                "still experimental on Mali v7 GPUs such as the G52.",
            status = NOT_INTEGRATED,
        ),
        RuntimeComponent(
            id = ComponentIds.AUDIO,
            title = "Audio bridge",
            detail = "Planned: Wine ALSA → AAudio / OpenSL ES. No implementation yet.",
            status = NOT_INTEGRATED,
        ),
        RuntimeComponent(
            id = ComponentIds.STORAGE,
            title = "Storage & game files",
            detail = "Detects the game in app storage and in a folder you pick; files are read " +
                "in place, never copied. Exposing them to the Wine prefix is not implemented.",
            status = PARTIAL,
        ),
    )

    /** Aggregate status: READY only when every component is READY. */
    fun overallStatus(
        nativeHosting: IntegrationStatus = NOT_INTEGRATED,
    ): IntegrationStatus {
        val statuses = components(nativeHosting).map { it.status }
        return when {
            statuses.all { it == READY } -> READY
            statuses.any { it != NOT_INTEGRATED } -> PARTIAL
            else -> NOT_INTEGRATED
        }
    }
}
