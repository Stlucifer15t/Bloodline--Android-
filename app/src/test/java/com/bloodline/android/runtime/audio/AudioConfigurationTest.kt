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

    private val plugin = File("/runtime/lib/alsa-lib/libpcm_bloodline.so")

    /** Sound is enabled, but nothing in the runtime is carrying it yet. */
    private val configuration = AudioConfiguration(
        bridge = AudioBridge.AAUDIO,
        sampleRateHz = 48_000,
        bufferFrames = 512,
    )

    /** The same bridge once the plugin library actually exists. */
    private val routed = AudioConfiguration(
        bridge = AudioBridge.AAUDIO,
        plugin = plugin,
        sampleRateHz = 48_000,
        bufferFrames = 512,
    )

    @Test
    fun `the generated config overrides the default ALSA device`() {
        // `pcm.!default` replaces the device every ALSA client asks for when it
        // asks for "default", so nothing needs per-application configuration.
        val text = routed.asoundConf()

        assertTrue(text.contains("pcm.!default"))
        assertTrue(text.contains("type bloodline"))
        assertTrue(text.contains("rate 48000"))
        assertTrue(text.contains("buffer 512"))
    }

    @Test
    fun `a config with no plugin names the null device, not a plugin that is not there`() {
        // This is the case that applies today: the plugin library does not
        // exist. Naming its PCM type anyway would fail with "Unknown PCM" the
        // moment Wine opened the device; ALSA's null device always resolves.
        val text = configuration.asoundConf()

        assertTrue(text.contains("pcm.!default"))
        assertTrue(text.contains("type null"))
        assertFalse(text.contains("type bloodline"))
    }

    @Test
    fun `ALSA_PLUGIN_DIR points at wherever the plugin actually is`() {
        val environment = routed.environment(temporary.root)

        assertEquals(
            "/runtime/lib/alsa-lib",
            environment["ALSA_PLUGIN_DIR"],
        )
    }

    @Test
    fun `enabled sound with no plugin is reported as silent, not as working`() {
        // "Sound is on" and "sound is reaching the speakers" are different
        // claims, and only the first one can be made here.
        assertTrue(configuration.producesSound)
        assertFalse(configuration.routedToAndroid)
        assertTrue(configuration.summary().contains("silent"))

        assertTrue(routed.routedToAndroid)
        assertTrue(routed.summary().contains("48000"))
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
        // With no plugin there is nothing to point at, so the conventional
        // directory is named. It is harmless: ALSA falls back to its own
        // search path, and the config it was given names only the null device.
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
        assertTrue(routed.summary().contains("AAudio"))
        assertTrue(routed.summary().contains("48000"))
        assertTrue(configuration.summary().contains("AAudio"))
    }
}
