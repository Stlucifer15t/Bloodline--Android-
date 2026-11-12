package com.bloodline.android.runtime

import com.bloodline.android.runtime.graphics.TextureCompatibility

/**
 * The contract every piece of the compatibility runtime implements.
 *
 * Why this interface exists: the brief asks for the runtime's responsibilities
 * to be separated so that a backend can be replaced without rewriting the
 * launcher. That only works if "can you run here?" is a question each module
 * answers about itself, using [RuntimeCapabilities] measured on the device —
 * rather than a decision made centrally with a pile of `when` statements.
 *
 * Note what is *not* here: no reference to an Activity, a Context or a View.
 * A module is pure Kotlin so its rules can be unit tested on the JVM, which is
 * the only kind of testing available for code that cannot run yet.
 */
interface RuntimeModule {

    /** Stable identifier. */
    val id: String

    /** Short name for the UI and for reports. */
    val title: String

    /**
     * What this module needs from the device before it is worth trying.
     *
     * A requirement can be *measured* (is there a 32-bit ABI?) or *structural*
     * (does the binary ship in the APK?). Both are stated, because a user
     * reading a report deserves to know which kind of problem they have.
     */
    fun requirements(capabilities: RuntimeCapabilities): List<Requirement>

    /** How far this module has got. */
    fun status(capabilities: RuntimeCapabilities): IntegrationStatus

    /** One sentence saying what is missing. Empty when nothing is. */
    fun blocker(capabilities: RuntimeCapabilities): String
}

/**
 * A single thing a module needs.
 *
 * @param description what is needed, phrased for a human.
 * @param satisfied   true when the device or the build provides it.
 * @param measured    true when [satisfied] came from a measurement on this
 *                    device rather than from a statement about the build.
 */
data class Requirement(
    val description: String,
    val satisfied: Boolean,
    val measured: Boolean,
) {
    /** "32-bit ABI (measured)" — so a report cannot blur the two kinds. */
    val label: String get() = if (measured) "$description (measured)" else description
}

/**
 * Everything the runtime needs to know about the device, and nothing else.
 *
 * Every field defaults to "not known", and every field is filled from a
 * measurement. Nothing here is inferred from a model name or a spec sheet:
 * the whole reason the earlier milestones built probes was so that decisions
 * could be made from readings instead of assumptions.
 */
data class RuntimeCapabilities(
    /** ABIs the process could have been loaded as, primary first. */
    val abis: List<String> = emptyList(),

    /** Whether a 32-bit userland is available at all. */
    val has32BitAbi: Boolean = false,

    /** Whether a 32-bit process was actually created successfully. */
    val canExecute32Bit: Boolean = false,

    /** Whether one process may trace another: PRoot's requirement. */
    val ptraceAllowed: Boolean = false,

    /** Whether a binary can be executed out of anonymous memory. */
    val memfdExecAllowed: Boolean = false,

    /**
     * How many processes this app may have at once, or 0 when it could not be
     * read. Android 12+ kills an app's children past a fixed cap, and Wine
     * spawns many, so the number is not academic.
     */
    val processLimit: Int = 0,

    /** GPU device nodes that exist and can be opened, e.g. `mali0`, `dri/renderD128`. */
    val gpuNodes: Set<String> = emptySet(),

    /** The GPU's own Vulkan device name, when a working loader exists. */
    val vulkanDevice: String? = null,

    /** Vulkan apiVersion encoded as Vulkan does it, or 0 when there is none. */
    val vulkanApiVersion: Int = 0,

    /** Whether the Vulkan implementation advertises itself as conformant enough to try. */
    val vulkanUsable: Boolean = false,

    /** OpenGL ES version string reported through EGL. */
    val glesVersion: String = "",

    /** GL renderer string reported through EGL. */
    val glRenderer: String = "",

    /**
     * The `GL_EXTENSIONS` string reported through EGL.
     *
     * Read because it is the only trustworthy answer to a question this project
     * cannot afford to guess at: whether the GPU can decode the compressed
     * texture formats a 2004 PC game uses. Model names and spec sheets are not
     * evidence; the extension string is.
     */
    val glExtensions: String = "",

    /** True when the reading came back at all, so "empty" and "not measured" differ. */
    val measured: Boolean = false,
) {

    val primaryAbi: String get() = abis.firstOrNull().orEmpty()

    /** True when the device exposes Arm's proprietary kbase node. */
    val hasMaliKbase: Boolean get() = gpuNodes.any { it.startsWith("mali") }

    /** True when a DRM render node exists, which is what Mesa's Panfrost needs. */
    val hasDriRenderNode: Boolean get() = gpuNodes.any { it.startsWith("dri/renderD") }

    /** GLES major.minor, or null when the string could not be parsed. */
    val glesMajorMinor: Pair<Int, Int>?
        get() = Regex("""OpenGL ES (\d+)\.(\d+)""").find(glesVersion)?.let { match ->
            match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        }?.let { (major, minor) ->
            if (major == null || minor == null) null else major to minor
        }

    /** True when the device reports OpenGL ES 3.1 or better. */
    val hasGles31: Boolean get() = glesMajorMinor?.let { (major, minor) ->
        major > 3 || (major == 3 && minor >= 1)
    } ?: false

    /**
     * How this GPU can be made to show a BCn (DXT) texture.
     *
     * Read from the extension string rather than assumed, because getting this
     * wrong does not degrade the picture — it deletes it. See
     * [TextureCompatibility] for why it matters to this game in particular.
     */
    val textureSupport: TextureCompatibility
        get() = TextureCompatibility.detect(
            glesMajor = glesMajorMinor?.first,
            extensions = glExtensions,
        )

    companion object {
        /** Everything unknown. Used before the probes have reported. */
        fun unknown(): RuntimeCapabilities = RuntimeCapabilities()
    }
}
