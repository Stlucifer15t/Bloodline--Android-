package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the parts of the native probe that are pure logic: how its output is
 * interpreted. Actually launching it needs a device, which CI cannot do, but the
 * decision it feeds into is covered.
 */
class NativeProbeTest {

    private val successfulOutput = """
        native-probe: ok
          path:        /data/app/~~abc==/com.bloodline.android-xyz/lib/arm64/libnative_probe.so
          abi:         arm64-v8a
          api-level:   34
          kernel:      Linux aarch64
          page-size:   4096
          exec-from-app-data: DENIED (Android 10+ W^X: runtime binaries must ship as lib*.so)
    """.trimIndent()

    @Test
    fun `accepts a clean run that announced itself`() {
        assertTrue(NativeProbe.isSuccessful(exitCode = 0, output = successfulOutput))
    }

    @Test
    fun `rejects a non zero exit code`() {
        assertFalse(NativeProbe.isSuccessful(exitCode = 1, output = successfulOutput))
    }

    @Test
    fun `rejects output without the success marker`() {
        assertFalse(NativeProbe.isSuccessful(exitCode = 0, output = "Permission denied"))
    }

    @Test
    fun `rejects the runner's own failure codes`() {
        val message = "Native probe not found at /data/app/lib/libnative_probe.so"
        assertFalse(NativeProbe.isSuccessful(NativeProbe.EXIT_MISSING, message))
        assertFalse(NativeProbe.isSuccessful(NativeProbe.EXIT_TIMEOUT, message))
        assertFalse(NativeProbe.isSuccessful(NativeProbe.EXIT_FAILED, message))
    }

    @Test
    fun `summarises the abi and api level`() {
        assertEquals("arm64-v8a · API 34", NativeProbe.summary(successfulOutput))
    }

    @Test
    fun `summarises to an empty string when nothing is recognised`() {
        assertEquals("", NativeProbe.summary("no useful output"))
    }

    @Test
    fun `reads the W^X verdict from the output`() {
        assertEquals(
            "blocked by W^X, as expected",
            NativeProbe.execFromAppDataVerdict(successfulOutput),
        )
    }

    @Test
    fun `reads a device that still allows exec from app storage`() {
        val output = "native-probe: ok\n  exec-from-app-data: ALLOWED (files in app storage can be executed here)"

        assertEquals(
            "files in app storage can be executed",
            NativeProbe.execFromAppDataVerdict(output),
        )
    }

    @Test
    fun `returns no verdict when the probe never reported one`() {
        assertNull(NativeProbe.execFromAppDataVerdict("native-probe: ok"))
    }
}
