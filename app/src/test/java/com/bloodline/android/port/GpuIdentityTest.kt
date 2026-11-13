package com.bloodline.android.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the GPU facts a driver package is gated on.
 *
 * The strings here are the ones a real device reports, and the mapping from a
 * marketing name to an architecture generation is the dangerous part: getting
 * it wrong means installing a driver built for a different GPU, which on this
 * hardware is a black screen rather than a slow game.
 */
class GpuIdentityTest {

    /** The shape the TECNO CK6 reports. */
    private val ck6 = "GPU=Mali-G52 MC2 r0p0 0x74021000 kbase_version=11.38 arch=7"

    @Test
    fun `reads the GPU name out of gpuinfo`() {
        assertEquals("Mali-G52 MC2", parseFamily("GPU=Mali-G52 MC2"))
        assertEquals("Mali-G52", parseFamily("GPU=Mali-G52"))
    }

    @Test
    fun `a name without the GPU key is still read`() {
        assertEquals("Mali-G76", parseFamily("Mali-G76 MP12 r0p0"))
    }

    @Test
    fun `nothing is reported from an empty file`() {
        assertNull(parseFamily(null))
        assertNull(parseFamily(""))
        assertNull(parseFamily("   \n\n"))
    }

    @Test
    fun `Mali-G52 is Bifrost v7`() {
        assertEquals(MaliArchitecture.V7, MaliArchitecture.of("Mali-G52 MC2"))
    }

    @Test
    fun `Bifrost generations are told apart`() {
        assertEquals(MaliArchitecture.V6, MaliArchitecture.of("Mali-G31"))
        assertEquals(MaliArchitecture.V6, MaliArchitecture.of("Mali-G51"))
        assertEquals(MaliArchitecture.V6, MaliArchitecture.of("Mali-G72"))
        assertEquals(MaliArchitecture.V7, MaliArchitecture.of("Mali-G71"))
        assertEquals(MaliArchitecture.V7, MaliArchitecture.of("Mali-G76"))
    }

    @Test
    fun `Valhall generations are told apart`() {
        assertEquals(MaliArchitecture.V9, MaliArchitecture.of("Mali-G57 MC2"))
        assertEquals(MaliArchitecture.V9, MaliArchitecture.of("Mali-G78"))
        assertEquals(MaliArchitecture.V10, MaliArchitecture.of("Mali-G610"))
        assertEquals(MaliArchitecture.V10, MaliArchitecture.of("Mali-G710"))
        assertEquals(MaliArchitecture.V11, MaliArchitecture.of("Mali-G615"))
        assertEquals(MaliArchitecture.V11, MaliArchitecture.of("Mali-G715"))
    }

    @Test
    fun `the fifth generation is mapped`() {
        assertEquals(MaliArchitecture.V12, MaliArchitecture.of("Immortalis-G720 MC12"))
        assertEquals(MaliArchitecture.V13, MaliArchitecture.of("Immortalis-G925"))
        assertEquals(MaliArchitecture.V14, MaliArchitecture.of("Mali-G1-Ultra"))
    }

    @Test
    fun `an Adreno GPU has no Mali architecture`() {
        assertNull(MaliArchitecture.of("Adreno (TM) 740"))
    }

    @Test
    fun `decodes the packed Vulkan version`() {
        // 1.1.177 as Android packs it.
        val version = VulkanVersion.fromPacked(4198400)

        assertNotNull(version)
        assertEquals(1, version!!.major)
        assertEquals(1, version.minor)
        assertEquals(177, version.patch)
        assertFalse(version.atLeast(1, 2))
        assertTrue(version.atLeast(1, 1))
    }

    @Test
    fun `a packed zero is not a version`() {
        assertNull(VulkanVersion.fromPacked(0))
    }

    @Test
    fun `parses a version string`() {
        assertEquals(VulkanVersion(1, 3, 363), VulkanVersion.parse("1.3.363"))
        assertEquals(VulkanVersion(1, 1, 0), VulkanVersion.parse("1.1"))
        assertNull(VulkanVersion.parse(""))
        assertNull(VulkanVersion.parse("garbage"))
    }

    @Test
    fun `a device with the Mali node and a known family can host a driver`() {
        val identity = GpuIdentity(
            rawGpuInfo = ck6,
            hasMaliNode = true,
            vulkanVersion = VulkanVersion(1, 1, 177),
            vendor = "TECNO",
            model = "TECNO CK6",
        )

        assertEquals("Mali-G52 MC2", identity.family)
        assertEquals(MaliArchitecture.V7, identity.architecture)
        assertTrue(identity.canHostKbaseDriver)
    }

    @Test
    fun `a hidden Mali node means no driver`() {
        val identity = GpuIdentity(
            rawGpuInfo = ck6,
            hasMaliNode = false,
            vulkanVersion = VulkanVersion(1, 1, 177),
            vendor = "TECNO",
            model = "TECNO CK6",
        )

        assertFalse(identity.canHostKbaseDriver)
    }

    @Test
    fun `the render height never exceeds the panel`() {
        assertEquals(480, RenderHeight.recommendedFor(screenHeight = 400))
        assertEquals(720, RenderHeight.recommendedFor(screenHeight = 1333))
    }

    @Test
    fun `a lower ceiling is respected`() {
        assertEquals(480, RenderHeight.recommendedFor(screenHeight = 1333, ceiling = 540))
    }

    @Test
    fun `the frame cap is never above what was asked`() {
        assertEquals(30, FrameLimit.atMost(30))
        assertEquals(60, FrameLimit.atMost(60))
        assertEquals(30, FrameLimit.atMost(45))
    }
}
