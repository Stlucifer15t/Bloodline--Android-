package com.bloodline.android.runtime.graphics

import com.bloodline.android.runtime.RuntimeCapabilities
import com.bloodline.android.runtime.backends.GraphicsBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the graphics configuration.
 *
 * The one rule that matters more than the rest: **the texture route is chosen
 * explicitly and reported.** A 2004 Source-engine game stores every world
 * texture as DXT1 or DXT5, and Mali does not decode BCn. Miss it and the game
 * runs with a black world, which reads as "Wine is broken" and sends the
 * investigation down the wrong path entirely.
 */
class GraphicsConfigurationTest {

    /** The Mali-G52's extension string: GLES 3.2, no S3TC, no ASTC. */
    private val mali = RuntimeCapabilities(
        glesVersion = "OpenGL ES 3.2 build 1.9@1234",
        glRenderer = "Mali-G52 MC2",
        glExtensions = "GL_OES_EGL_image GL_ARM_shader_framebuffer_fetch",
        gpuNodes = setOf("mali0"),
        measured = true,
    )

    @Test
    fun `a Mali device transcodes textures to ETC2`() {
        val configuration = GraphicsConfiguration.forBackend(
            GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            mali,
        )

        assertEquals(TextureCompatibility.ETC2, configuration.textureRoute)
        assertTrue(configuration.textureRoute.canHandleBcn)
    }

    @Test
    fun `software decoding is called out in the summary as slow`() {
        // A device that can only decompress on the CPU still runs — it just
        // runs badly, and the report should say so rather than let the user
        // discover it as "the game is slow".
        val configuration = GraphicsConfiguration(
            backend = GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            textureRoute = TextureCompatibility.SOFTWARE,
        )

        assertTrue(configuration.summary().contains("slow"))
    }

    @Test
    fun `the GLES version is capped at 3_1 for WineD3D`() {
        // WineD3D's GL backend is happier with a version it fully implements
        // than with the newest one the driver advertises.
        val configuration = GraphicsConfiguration.forBackend(
            GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            mali,
        )

        assertEquals(3 to 1, configuration.glesVersion)
        assertEquals("3.1", configuration.environment()["MESA_GL_VERSION_OVERRIDE"])
    }

    @Test
    fun `the Mali workaround disables Box64's 32-bit mapping only`() {
        // Taken from what the Mali builds do. It is a Box64 setting — it must
        // not turn into a different translator or a different backend.
        val configuration = GraphicsConfiguration.forBackend(
            GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            mali,
        )

        assertTrue(configuration.maliMmapWorkaround)
        assertEquals("0", configuration.environment()["BOX64_MMAP32"])
        assertEquals(GraphicsBackend.GLES_WRAPPER_WINE_D3D, configuration.backend)
    }

    @Test
    fun `a non-Mali device does not get the Mali workaround`() {
        val configuration = GraphicsConfiguration.forBackend(
            GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            RuntimeCapabilities(glesVersion = "OpenGL ES 3.2", measured = true),
        )

        assertFalse(configuration.maliMmapWorkaround)
        assertFalse(configuration.environment().containsKey("BOX64_MMAP32"))
    }

    @Test
    fun `llvmpipe forces software rendering`() {
        val environment = GraphicsConfiguration(
            backend = GraphicsBackend.LLVMPIPE,
            textureRoute = TextureCompatibility.NATIVE,
        ).environment()

        assertEquals("1", environment["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("llvmpipe", environment["GALLIUM_DRIVER"])
    }

    @Test
    fun `the native wrapper leaves Mesa alone`() {
        // Nothing to tell Mesa: the wrapper loads the device's own GLES driver.
        val environment = GraphicsConfiguration(
            backend = GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            textureRoute = TextureCompatibility.ETC2,
        ).environment()

        assertFalse(environment.containsKey("LIBGL_ALWAYS_SOFTWARE"))
        assertFalse(environment.containsKey("GALLIUM_DRIVER"))
    }

    // ------------------------------------------------------------- the registry

    @Test
    fun `registry settings are real Wine keys, not invented variables`() {
        val settings = GraphicsConfiguration.forBackend(
            GraphicsBackend.GLES_WRAPPER_WINE_D3D,
            mali,
        ).registrySettings()

        val keys = settings.map { it.key }.distinct()
        assertEquals(listOf("HKEY_CURRENT_USER\\Software\\Wine\\Direct3D"), keys)
        assertTrue(settings.any { it.name == "MaxVersionGL" })
        assertTrue(settings.any { it.name == "VideoMemorySize" })
        assertTrue(settings.any { it.name == "OffscreenRenderingMode" })
    }

    @Test
    fun `a setting becomes a wine reg add command`() {
        val command = RegistrySetting.offscreenRendering(OffscreenMode.FBO).toCommand("/wine/bin/wine")

        assertEquals(
            listOf("/wine/bin/wine", "reg", "add",
                "HKEY_CURRENT_USER\\Software\\Wine\\Direct3D",
                "/v", "OffscreenRenderingMode",
                "/t", "REG_SZ",
                "/d", "fbo",
                "/f"),
            command,
        )
    }
}
