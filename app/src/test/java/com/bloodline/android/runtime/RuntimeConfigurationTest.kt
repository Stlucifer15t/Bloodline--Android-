package com.bloodline.android.runtime

import com.bloodline.android.runtime.backends.AudioBridge
import com.bloodline.android.runtime.backends.CpuTranslator
import com.bloodline.android.runtime.backends.GraphicsBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the settings round trip.
 *
 * The case worth testing is the one that happens to real users: an upgrade
 * where the stored value names a backend that no longer exists. That has to
 * degrade to "you decide", not crash on startup.
 */
class RuntimeConfigurationTest {

    @Test
    fun `a round trip preserves every choice`() {
        val configuration = RuntimeConfiguration(
            graphicsBackend = GraphicsBackend.PANVK_DXVK,
            cpuTranslator = CpuTranslator.BOX86,
            audioBackend = AudioBridge.AAUDIO,
            stretchToFill = true,
            frameLimit = 30,
        )

        assertEquals(configuration, RuntimeConfiguration.fromMap(configuration.asMap()))
    }

    @Test
    fun `absent keys mean automatic, not off`() {
        val configuration = RuntimeConfiguration()

        assertNull(configuration.asMap()[RuntimeConfiguration.KEY_GRAPHICS])
        assertNull(RuntimeConfiguration.fromMap(emptyMap()).graphicsBackend)
    }

    @Test
    fun `an unknown stored backend degrades to automatic`() {
        // Written by a build that offered a backend this one does not have.
        val values = mapOf(
            RuntimeConfiguration.KEY_GRAPHICS to "turnip-dxvk",
            RuntimeConfiguration.KEY_CPU to "qemu-user",
        )

        val configuration = RuntimeConfiguration.fromMap(values)

        assertNull(configuration.graphicsBackend)
        assertNull(configuration.cpuTranslator)
    }

    @Test
    fun `a corrupt frame limit does not crash the round trip`() {
        val configuration = RuntimeConfiguration.fromMap(
            mapOf(RuntimeConfiguration.KEY_FRAME_LIMIT to "not-a-number"),
        )

        assertNull(configuration.frameLimit)
    }

    @Test
    fun `an explicit Vulkan choice is preserved`() {
        val values = mapOf(RuntimeConfiguration.KEY_GRAPHICS to GraphicsBackend.PANVK_DXVK.id)

        assertEquals(
            GraphicsBackend.PANVK_DXVK,
            RuntimeConfiguration.fromMap(values).graphicsBackend,
        )
    }
}
