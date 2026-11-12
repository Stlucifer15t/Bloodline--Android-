package com.bloodline.android.runtime.backends

import com.bloodline.android.runtime.RuntimeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the choice of CPU translator.
 *
 * The property under test, and the one this file exists to protect:
 *
 * > **`Vampire.exe` is a 32-bit binary, so whatever we recommend must be able
 * > to host a 32-bit Windows guest.**
 *
 * An earlier version of this class recommended Box86 whenever the device could
 * run 32-bit processes. That was wrong in a way worth recording, because the
 * reasoning looked sound: the game is 32-bit, so a 32-bit translator fits.
 * Inspecting what the working Android runtimes actually ship showed they do the
 * opposite — a **64-bit** prefix with **WoW64** hosting the 32-bit guest, and
 * no Box86 anywhere. The deciding factor is that Box86 is itself a 32-bit ARM
 * program, so it needs a 32-bit userland, which is precisely the dependency
 * that is disappearing from Android devices.
 *
 * These tests pin the corrected behaviour down so it cannot drift back.
 */
class CpuTranslatorTest {

    /** A device that can still run 32-bit processes. */
    private val with32Bit = RuntimeCapabilities(
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        has32BitAbi = true,
        canExecute32Bit = true,
        measured = true,
    )

    /** A 64-bit-only device, which is where Android is heading. */
    private val no32Bit = RuntimeCapabilities(
        abis = listOf("arm64-v8a"),
        has32BitAbi = false,
        canExecute32Bit = false,
        measured = true,
    )

    /**
     * The CK6 as described: a 64-bit-only Mali device. This is the shape the
     * recommendation has to work for.
     */
    private val maliDevice = RuntimeCapabilities(
        abis = listOf("arm64-v8a"),
        gpuNodes = setOf("mali0"),
        measured = true,
    )

    // -------------------------------------------------------- the default route

    @Test
    fun `recommends WOWBox64 by default`() {
        assertEquals(CpuTranslator.WOWBOX64, CpuTranslator.recommendedFor(with32Bit))
        assertEquals(CpuTranslator.WOWBOX64, CpuTranslator.recommendedFor(no32Bit))
        assertEquals(CpuTranslator.WOWBOX64, CpuTranslator.recommendedFor(maliDevice))
    }

    @Test
    fun `the default never depends on a 32-bit userland`() {
        // The point of the correction. Whatever we recommend, it must work on a
        // device that has no 32-bit ABI at all.
        listOf(with32Bit, no32Bit, maliDevice).forEach { device ->
            assertFalse(CpuTranslator.recommendedFor(device).needs32BitUserland)
        }
    }

    @Test
    fun `Box86 is never recommended, even where it would work`() {
        // Box86 remains listed and selectable, but it is never the default: the
        // 32-bit-userland dependency makes it the wrong bet on modern Android.
        listOf(with32Bit, no32Bit, maliDevice).forEach { device ->
            assertTrue(CpuTranslator.recommendedFor(device) != CpuTranslator.BOX86)
        }
    }

    @Test
    fun `falls back to FEX on a Mali device that cannot map 32-bit memory`() {
        // Box64's 32-bit address-space handling is a known problem on Mali, and
        // the Mali builds turn it off explicitly.
        val device = maliDevice.copy(canExecute32Bit = false)
        val recommended = CpuTranslator.recommendedFor(device)

        assertEquals(CpuTranslator.FEX, recommended)
    }

    // --------------------------------------------------------- 32-bit hosting

    @Test
    fun `plain Box64 cannot host the game, because the game is 32-bit`() {
        assertFalse(CpuTranslator.BOX64.canRun32Bit())
        assertEquals(null, CpuTranslator.BOX64.wow64)
    }

    @Test
    fun `every route that can host the game names the mechanism`() {
        CpuTranslator.capableOf32Bit()
            .mapNotNull { it.wow64 }
            .filter { it != WoW64Mode.WIN32_PREFIX }
            .forEach { mode ->
                assertTrue(
                    "${mode.name} hosts 32-bit guests without naming a DLL.",
                    mode.dllName.isNotEmpty(),
                )
            }
    }

    @Test
    fun `capableOf32Bit excludes plain Box64`() {
        assertFalse(CpuTranslator.capableOf32Bit().contains(CpuTranslator.BOX64))
        assertTrue(CpuTranslator.capableOf32Bit().contains(CpuTranslator.WOWBOX64))
    }

    @Test
    fun `the WoW64 routes need no 32-bit userland`() {
        listOf(CpuTranslator.WOWBOX64, CpuTranslator.FEX).forEach { translator ->
            assertFalse(translator.needs32BitUserland)
            assertTrue(translator.canRun32Bit())
        }
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

        val descriptions = requirements.map { it.description }
        assertTrue(descriptions.any { it.contains("32-bit ABI") })
        assertTrue(descriptions.any { it.contains("32-bit processes") })
        assertTrue(requirements.none { it.satisfied })
    }

    @Test
    fun `the WoW64 routes ask nothing of a 64-bit-only device`() {
        // A 64-bit-only phone is the future, and the whole reason WoW64 was
        // chosen over Box86. These routes must not need anything it lacks.
        listOf(CpuTranslator.WOWBOX64, CpuTranslator.FEX).forEach { translator ->
            val unmet = translator.requirements(no32Bit).filter { !it.satisfied }

            assertEquals(
                "${translator.id} wants something a 64-bit-only device does not have: $unmet",
                listOf("${translator.title} package installed"),
                unmet.map { it.description },
            )
        }
    }

    // -------------------------------------------------------------- blockers

    @Test
    fun `no translator reports ready, because no packages are installed`() {
        // Same rule as graphics: a device measurement cannot invent a binary.
        CpuTranslator.entries.forEach { translator ->
            assertTrue(
                "${translator.id} claims to be ready with nothing installed.",
                translator.blocker(with32Bit).contains("package installed"),
            )
        }
    }

    @Test
    fun `the recommended route is not blocked on a 64-bit-only device`() {
        // The correction's real payoff: nothing about the recommendation
        // depends on a capability this device does not have.
        val missing = CpuTranslator.recommendedFor(no32Bit)
            .requirements(no32Bit)
            .filter { !it.satisfied }

        assertEquals(
            "The only thing missing should be the package itself.",
            listOf("${CpuTranslator.WOWBOX64.title} package installed"),
            missing.map { it.description },
        )
    }

    @Test
    fun `ids survive a round trip`() {
        CpuTranslator.entries.forEach { translator ->
            assertEquals(translator, CpuTranslator.fromId(translator.id))
        }
    }
}
