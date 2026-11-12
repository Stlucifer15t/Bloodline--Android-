package com.bloodline.android.runtime.audio

import com.bloodline.android.runtime.backends.AudioBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the audio path.
 *
 * The design is the standard ALSA one — a `pcm` definition naming a plugin,
 * pointed at by `ALSA_CONFIG_PATH` and `ALSA_PLUGIN_DIR` — rather than invented
 * environment variables, because those two are what the working Android
 * runtimes actually set. Wine's ALSA driver inside the prefix opens `default`
 * and finds our plugin there.
 */
class AudioConfigurationTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val configuration = AudioConfiguration(
        bridge = AudioBridge.AAUDIO,
        sampleRateHz = 48_000,
        bufferFrames = 512,
    )

    @Test
    fun `the generated config overrides the default ALSA device`() {
        // `pcm.!default` replaces the device every ALSA client asks for when it
        // asks for "default", so nothing needs per-application configuration.
        val text = configuration.asoundConf()

        assertTrue(text.contains("pcm.!default"))
        assertTrue(text.contains("type bloodline"))
        assertTrue(text.contains("rate 48000"))
        assertTrue(text.contains("buffer 512"))
    }

    @Test
    fun `the config is written where ALSA_CONFIG_PATH points`() {
        // The variable and the file must agree, or Wine reads ALSA's own
        // default config and the audio bridge is never involved.
        val runtime = temporary.newFolder("runtime")
        val environment = configuration.environment(runtime)

        val file = File(environment["ALSA_CONFIG_PATH"])

        assertTrue(file.isFile)
        assertEquals("asound.conf", file.name)
        assertTrue(file.path.startsWith(runtime.path))
    }

    @Test
    fun `the environment names both ALSA variables`() {
        val environment = configuration.environment(temporary.root)

        assertTrue(environment["ALSA_CONFIG_PATH"]!!.endsWith("asound.conf"))
        assertTrue(environment["ALSA_PLUGIN_DIR"]!!.endsWith("lib/alsa-lib"))
    }

    @Test
    fun `Wine is told to use its ALSA driver`() {
        val settings = configuration.registrySettings()

        assertEquals(1, settings.size)
        assertEquals("HKEY_CURRENT_USER\\Software\\Wine\\Drivers", settings.first().key)
        assertEquals("audio", settings.first().name)
        assertEquals("winealsa.drv", settings.first().value)
    }

    @Test
    fun `muting is a real setting, not an absent one`() {
        // Starting muted is how the rest of the runtime gets tested without an
        // audio server in the way. It has to be said explicitly, or Wine picks
        // a driver for us.
        val muted = AudioConfiguration(bridge = AudioBridge.NONE)

        assertFalse(muted.producesSound)
        assertEquals("", muted.registrySettings().first().value)
        assertTrue(muted.summary().contains("muted"))
    }

    @Test
    fun `the summary says what is actually configured`() {
        assertTrue(configuration.summary().contains("AAudio"))
        assertTrue(configuration.summary().contains("48000"))
    }
}
