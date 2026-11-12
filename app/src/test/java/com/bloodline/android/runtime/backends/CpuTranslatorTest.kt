package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.RuntimeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the choice of CPU translator.
 *
 * The point being pinned down: `Vampire.exe` is a **32-bit** binary, so the
 * runtime has to offer a genuine 32-bit path. Box64 on its own cannot run it,
 * which is why the catalogue includes Box86 and why the recommendation shifts
 * away from Box86 when the device cannot run 32-bit processes.
 */
class CpuTranslatorTest {

    private val with32Bit = RuntimeCapabilities(
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        has32BitAbi = true,
        canExecute32Bit = true,
        measured = true,
    )

    private val no32Bit = RuntimeCapabilities(
        abis = listOf("arm64-v8a"),
        has32BitAbi = false,
        canExecute32Bit = false,
        measured = true,
    )

    @Test
    fun `prefers Box86 when the device runs 32-bit code`() {
        assertEquals(CpuTranslator.BOX86, CpuTranslator.recommendedFor(with32Bit))
    }

    @Test
    fun `avoids Box86 when the device cannot run 32-bit processes`() {
        // Box86 is itself a 32-bit ARM program. Recommending it on a device that
        // refuses 32-bit processes would guarantee a failure.
        val recommended = CpuTranslator.recommendedFor(no32Bit)

        assertFalse(recommended.needs32BitUserland)
    }

    @Test
    fun `Box86 is the only route that needs a 32-bit userland`() {
        assertEquals(
            listOf(CpuTranslator.BOX86),
            CpuTranslator.entries.filter { it.needs32BitUserland },
        )
    }

    @Test
    fun `Box86 lists both 32-bit requirements separately`() {
        // A device can advertise a 32-bit ABI and still refuse to execute one,
        // so they are two requirements rather than one.
        val requirements = CpuTranslator.BOX86.requirements(RuntimeCapabilities(measured = true))

        assertEquals(2, requirements.count { it.description.contains("32-bit") })
        assertTrue(requirements.none { it.satisfied })
    }

    @Test
    fun `the WOW64 and FEX routes do not ask for a 32-bit userland`() {
        listOf(CpuTranslator.BOX64_WOW64, CpuTranslator.FEX_ARM64EC).forEach { translator ->
            assertTrue(
                translator.requirements(no32Bit).none { it.description.contains("32-bit") },
            )
        }
    }

    @Test
    fun `no translator reports ready, because none ship`() {
        // Same rule as graphics: a device measurement cannot invent a binary.
        CpuTranslator.entries.forEach { translator ->
            assertTrue(
                translator.blocker(with32Bit).contains("shipped in the APK"),
            )
        }
    }

    @Test
    fun `ids survive a round trip`() {
        CpuTranslator.entries.forEach { translator ->
            assertEquals(translator, CpuTranslator.fromId(translator.id))
        }
    }
}
