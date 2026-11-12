package com.bloodline.android.runtime

/**
 * How far along a single piece of the Windows compatibility stack is.
 *
 * Nothing in this project lies about its state: a component only reports
 * [READY] once its code actually ships inside the APK (and, where it can be
 * measured, once it has been verified on the device that is running).
 */
enum class IntegrationStatus {
    /** No code exists in the APK for this component. */
    NOT_INTEGRATED,

    /** Some of the component works, but the feature is incomplete. */
    PARTIAL,

    /** Shipped and functional. */
    READY,
}

/**
 * One independently replaceable piece of the compatibility runtime.
 *
 * @param id        stable identifier, see [ComponentIds].
 * @param title     short name shown in the launcher.
 * @param detail    one or two sentences describing the current state.
 * @param status    integration state of the component.
 */
data class RuntimeComponent(
    val id: String,
    val title: String,
    val detail: String,
    val status: IntegrationStatus,
)

/**
 * Stable component identifiers. The UI maps each id to a row, so adding a
 * component here means adding one row to the launcher layout.
 */
object ComponentIds {
    /** Executing a bundled native binary the W^X-safe way. */
    const val NATIVE_HOSTING = "native-hosting"

    const val WINE = "wine"
    const val CPU_TRANSLATION = "cpu-translation"
    const val CONTAINER = "container"
    const val GRAPHICS = "graphics"
    const val AUDIO = "audio"
    const val STORAGE = "storage"
}
