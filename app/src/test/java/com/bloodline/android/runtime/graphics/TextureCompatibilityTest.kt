package com.bloodline.android.runtime.graphics

import com.bloodline.android.runtime.RuntimeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for how the game's compressed textures get onto a Mali screen.
 *
 * This is the failure mode worth a whole test file, because it is silent and it
 * is total. PC games store textures in the BCn formats — DXT1 through DXT5, in
 * 2004 terms — and **Mali does not decode them**. Vampire: Bloodlines is a 2004
 * Source-engine game, so effectively every world texture is DXT1 or DXT5.
 *
 * Get this wrong and the game does not crash and does not slow down: it runs,
 * and the world is black. That is the kind of bug that reads as "Wine doesn't
 * work" and sends an investigation down the wrong path entirely.
 */
class TextureCompatibilityTest {

    /** The Mali-G52's real extension string, which has neither S3TC nor ASTC. */
    private val maliExtensions = "GL_EXT_debug_marker GL_OES_EGL_image GL_ARM_shader_framebuffer_fetch"

    @Test
    fun `BCn is not in the GLES 3 mandatory set, which is the whole problem`() {
        // Nothing about a conformant GLES 3.2 driver implies DXT support. This
        // test exists so nobody "simplifies" the check into `hasGles31`.
        assertEquals(TextureCompatibility.ETC2, TextureCompatibility.detect(3, ""))
    }

    @Test
    fun `a GPU that decodes BCn natively needs nothing else`() {
        val extensions = "GL_EXT_texture_compression_s3tc GL_EXT_texture_compression_dxt1"

        assertEquals(TextureCompatibility.NATIVE, TextureCompatibility.detect(3, extensions))
        assertEquals(0, TextureCompatibility.NATIVE.cost)
    }

    @Test
    fun `ASTC is preferred over ETC2 when the GPU has it`() {
        val extensions = "GL_KHR_texture_compression_astc_ldr"

        assertEquals(TextureCompatibility.ASTC, TextureCompatibility.detect(3, extensions))
        assertTrue(TextureCompatibility.ASTC.cost < TextureCompatibility.ETC2.cost)
    }

    @Test
    fun `a GLES 3 device without ASTC falls back to ETC2`() {
        // ETC2 is mandatory from OpenGL ES 3.0 onwards, so it is always
        // available on anything this project can target.
        assertEquals(TextureCompatibility.ETC2, TextureCompatibility.detect(3, maliExtensions))
        assertTrue(TextureCompatibility.ETC2.canHandleBcn)
    }

    @Test
    fun `a GLES 2 device has no compressed fallback and must decode on the CPU`() {
        assertEquals(TextureCompatibility.SOFTWARE, TextureCompatibility.detect(2, maliExtensions))
        assertTrue(TextureCompatibility.SOFTWARE.canHandleBcn)
        assertTrue(TextureCompatibility.SOFTWARE.cost > TextureCompatibility.ETC2.cost)
    }

    @Test
    fun `unknown readings are reported as unsupported rather than assumed`() {
        // No version and no extension string means we know nothing. Claiming
        // ETC2 here would be a guess dressed up as a measurement.
        assertEquals(TextureCompatibility.UNSUPPORTED, TextureCompatibility.detect(null, null))
        assertEquals(TextureCompatibility.UNSUPPORTED, TextureCompatibility.detect(3, null))
        assertEquals(TextureCompatibility.UNSUPPORTED, TextureCompatibility.detect(null, maliExtensions))
        assertFalse(TextureCompatibility.UNSUPPORTED.canHandleBcn)
    }

    @Test
    fun `bestOf picks the cheapest route`() {
        val best = TextureCompatibility.bestOf(
            listOf(TextureCompatibility.SOFTWARE, TextureCompatibility.ASTC, TextureCompatibility.ETC2),
        )

        assertEquals(TextureCompatibility.ASTC, best)
    }

    @Test
    fun `bestOf on an empty list means nothing can show these textures`() {
        assertEquals(TextureCompatibility.UNSUPPORTED, TextureCompatibility.bestOf(emptyList()))
    }

    // ------------------------------------------------------- wired into caps

    @Test
    fun `the Mali-G52 as measured reports a working route, not native support`() {
        val capabilities = RuntimeCapabilities(
            glesVersion = "OpenGL ES 3.2 build 1.9@1234",
            glRenderer = "Mali-G52 MC2",
            glExtensions = maliExtensions,
            measured = true,
        )

        assertEquals(TextureCompatibility.ETC2, capabilities.textureSupport)
        assertTrue(capabilities.textureSupport.canHandleBcn)
    }

    @Test
    fun `an unmeasured device reports no texture support`() {
        assertFalse(RuntimeCapabilities().textureSupport.canHandleBcn)
    }
}
