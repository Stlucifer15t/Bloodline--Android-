package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.RuntimeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the audio bridge choice.
 *
 * The interesting case is the process cap. Android 12 and later kill an app's
 * children past a fixed limit, and Wine starts a tree of them; a device that
 * reports a tight cap is a device where audio should take the cheaper route.
 */
class AudioBridgeTest {

    private val roomy = RuntimeCapabilities(processLimit = 4096, measured = true)

    private val tight = RuntimeCapabilities(processLimit = 8, measured = true)

    @Test
    fun `prefers AAudio by default`() {
        assertEquals(AudioBridge.AAUDIO, AudioBridge.recommendedFor(roomy))
    }

    @Test
    fun `prefers OpenSL ES when the process limit is tight`() {
        assertEquals(AudioBridge.OPENSL_ES, AudioBridge.recommendedFor(tight))
    }

    @Test
    fun `no audio is a real option`() {
        // Starting muted is how the rest of the runtime gets tested without an
        // audio server in the way, so it must stay selectable.
        assertFalse(AudioBridge.NONE.producesSound)
        assertTrue(AudioBridge.NONE.requirements(roomy).isNotEmpty())
    }

    @Test
    fun `a sound-producing bridge needs its binary`() {
        listOf(AudioBridge.AAUDIO, AudioBridge.OPENSL_ES).forEach { bridge ->
            assertTrue(bridge.blocker(roomy).contains("shipped in the APK"))
        }
    }

    @Test
    fun `a tight process limit is reported as a blocker`() {
        assertTrue(AudioBridge.AAUDIO.requirements(tight).any { !it.satisfied })
    }

    @Test
    fun `an unknown process limit does not block`() {
        // 0 means "not read", which must not be mistaken for "no room".
        val unknown = RuntimeCapabilities(measured = true)
        val requirement = AudioBridge.AAUDIO.requirements(unknown)
            .first { it.description.contains("process manager") }

        assertTrue(requirement.satisfied)
    }

    @Test
    fun `ids survive a round trip`() {
        AudioBridge.entries.forEach { bridge ->
            assertEquals(bridge, AudioBridge.fromId(bridge.id))
        }
    }
}
