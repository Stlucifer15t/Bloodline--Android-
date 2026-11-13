package com.bloodline.android.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for reading the port's settings file.
 *
 * The load-bearing rules are the two that a naive implementation breaks:
 * unknown keys survive, and order survives. The port owns this file and writes
 * controller bindings into it; a settings app that dropped them would be
 * destructive in a way the user would only discover in the middle of a game.
 */
class PortSettingsTest {

    private val sample = """
        pad_look=2600.0
        render_h=0
        fps_limit=60
        ui.renderRes=4.0
        ui.gfxApi=0.0
        ui.btn0=1.0
        ui.padBind12=12.0
    """.trimIndent()

    @Test
    fun `parses keys and values`() {
        val settings = PortSettings.parse(sample)

        assertEquals("0", settings.get(PortSettings.KEY_RENDER_HEIGHT))
        assertEquals("60", settings.get(PortSettings.KEY_FRAME_LIMIT))
        assertEquals("4.0", settings.get(PortSettings.KEY_RENDER_SCALE))
    }

    @Test
    fun `keeps keys it does not understand`() {
        val settings = PortSettings.parse(sample).set(PortSettings.KEY_RENDER_HEIGHT, "480")

        val rendered = settings.render()

        assertTrue(
            "controller bindings must survive a settings change",
            rendered.contains("ui.padBind12=12.0"),
        )
        assertTrue(rendered.contains("pad_look=2600.0"))
    }

    @Test
    fun `changing a value keeps its position`() {
        val lines = PortSettings.parse(sample)
            .set(PortSettings.KEY_FRAME_LIMIT, "30")
            .render()
            .lines()

        assertEquals("fps_limit=30", lines[2])
    }

    @Test
    fun `a new key is appended rather than inserted`() {
        val lines = PortSettings.parse(sample)
            .set("ui.gfxPreset", "1")
            .render()
            .lines()

        assertEquals("ui.gfxPreset=1", lines.last())
    }

    @Test
    fun `removing a key drops only that key`() {
        val rendered = PortSettings.parse(sample)
            .remove(PortSettings.KEY_FRAME_LIMIT)
            .render()

        assertFalse(rendered.contains("fps_limit"))
        assertTrue(rendered.contains("render_h=0"))
    }

    @Test
    fun `a line without a separator is preserved verbatim`() {
        val withComment = "# my note\nrender_h=0\n"

        val rendered = PortSettings.parse(withComment).set("render_h", "480").render()

        assertTrue(rendered.contains("# my note"))
    }

    @Test
    fun `CRLF and lone CR are normalised`() {
        val settings = PortSettings.parse("render_h=0\r\nfps_limit=60\r")

        assertEquals("0", settings.get(PortSettings.KEY_RENDER_HEIGHT))
        assertEquals("60", settings.get(PortSettings.KEY_FRAME_LIMIT))
    }

    @Test
    fun `a value containing an equals sign survives`() {
        val settings = PortSettings.parse("ui.note=a=b=c")

        assertEquals("a=b=c", settings.get("ui.note"))
    }

    @Test
    fun `round trips unchanged`() {
        val rendered = PortSettings.parse(sample).render()

        assertEquals(sample, rendered)
    }

    @Test
    fun `a missing file is not mistaken for zero`() {
        val snapshot = PortSettings.empty().snapshot()

        assertNull(snapshot.renderHeight)
        assertNull(snapshot.frameLimit)
        assertTrue("an absent height means the port's own default", snapshot.rendersAtNativeHeight)
    }

    @Test
    fun `reads a snapshot`() {
        val snapshot = PortSettings.parse(sample).snapshot()

        assertEquals(0, snapshot.renderHeight)
        assertEquals(60, snapshot.frameLimit)
        assertEquals(GraphicsApi.VULKAN, snapshot.renderer)
        assertTrue(snapshot.rendersAtNativeHeight)
    }

    @Test
    fun `an unparseable number degrades to absent`() {
        val snapshot = PortSettings.parse("render_h=very\nfps_limit=\n").snapshot()

        assertNull(snapshot.renderHeight)
        assertNull(snapshot.frameLimit)
    }
}
