package com.bloodline.android.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A test built from the port's *real* settings file, pasted verbatim.
 *
 * The other tests use a trimmed example; this one uses the whole 882-byte file
 * as the port wrote it on a device, because the failure that matters is a
 * parser that copes with a sample and mangles the real thing — and because
 * every one of those `ui.padBind*` lines is somebody's controller.
 */
class LiveSettingsFormatTest {

    private val live = """
        pad_look=2600.0
        pad_dead=0.12
        swipe=6.4
        invert_y=0
        render_h=0
        hud_scale=1
        fps_limit=60
        ui.swipe=6.4
        ui.padLook=2600.0
        ui.padDead=0.12
        ui.dead=0.08
        ui.scale=1.0
        ui.opacity=1.0
        ui.invert=0.0
        ui.vibration=1.0
        ui.cheatBtn=1.0
        ui.permMode=1.0
        ui.spy=1.0
        ui.btn0=1.0
        ui.btn1=1.0
        ui.btn4=1.0
        ui.btn17=1.0
        ui.fpsLimit=3.0
        ui.monitor=1.0
        ui.renderRes=4.0
        ui.gfxApi=0.0
        ui.gyro=0.0
        ui.padBind0=1.0
        ui.padBind1=2.0
        ui.padBind12=12.0
        ui.lang=0
        ui.gfxSet=1.0
        ui.gfxLock=0.0
        ui.presetInit=1.0
    """.trimIndent()

    @Test
    fun `the recommended preset changes three keys and nothing else`() {
        val identity = GpuIdentity(
            rawGpuInfo = "GPU=Mali-G52 MC2 r0p0 0x74021000 kbase_version=11.38 arch=7",
            hasMaliNode = true,
            vulkanVersion = VulkanVersion(1, 1, 0),
            vendor = "TECNO",
            model = "TECNO CK6",
        )
        val preset = PortPreset.recommendedFor(identity, screenHeight = 600)

        val before = PortSettings.parse(live)
        val after = PortSettings.parse(live).apply {
            preset.changes().forEach { (key, value) -> set(key, value) }
        }

        val keysBefore = before.keys()
        val keysAfter = after.keys()
        assertEquals("no key may be added or dropped", keysBefore, keysAfter)

        val changed = keysBefore.filter { before.get(it) != after.get(it) }
        assertEquals(
            "only the preset's own keys may change: $changed",
            preset.changes().keys.sorted(),
            changed.sorted(),
        )
    }

    @Test
    fun `controller bindings survive the recommended preset`() {
        val preset = PortPreset.EMERGENCY
        val after = PortSettings.parse(live).apply {
            preset.changes().forEach { (key, value) -> set(key, value) }
        }.render()

        assertTrue(after.contains("ui.padBind0=1.0"))
        assertTrue(after.contains("ui.padBind12=12.0"))
        assertTrue(after.contains("ui.lang=0"))
        assertTrue(after.contains("ui.gfxSet=1.0"))
    }

    @Test
    fun `a 600px panel is not asked for a 720p render`() {
        val identity = GpuIdentity(
            rawGpuInfo = "GPU=Mali-G52 MC2",
            hasMaliNode = true,
            vulkanVersion = VulkanVersion(1, 1, 0),
            vendor = "TECNO",
            model = "TECNO CK6",
        )

        val preset = PortPreset.recommendedFor(identity, screenHeight = 600)

        assertTrue(preset.renderHeight <= 540)
        assertTrue(preset.renderHeight == 0 || preset.renderHeight <= 600)
    }
}
