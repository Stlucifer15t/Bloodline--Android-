package com.bloodline.android.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Tests for verifying a downloaded driver.
 *
 * A driver package is code from a third party that will be loaded into the
 * game's process, so the digest check is the whole security story: a corrupted
 * or substituted archive must not reach the installer, and the failure must
 * say which of those two it suspects.
 */
class DriverDownloaderTest {

    private val payload = "pretend this is a 9 MB driver package".toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun packageWith(digest: String) = DriverPackage(
        id = "test",
        name = "Test driver",
        driverVersion = "test",
        url = "https://example.invalid/driver.zip",
        sizeBytes = payload.size.toLong(),
        sha256 = digest,
        libraryName = "libvulkan_panfrost.so",
        librarySha256 = sha256(payload),
        minApi = 29,
        supportedArchitectures = setOf(MaliArchitecture.V7),
        licence = Licence("MIT", "MIT", "", "", ""),
    )

    @Test
    fun `a matching digest is accepted`() {
        val target = java.io.File.createTempFile("driver", ".zip").apply { deleteOnExit() }
        val downloader = DriverDownloader({ ByteArrayInputStream(payload) })

        val result = downloader.fetch(packageWith(sha256(payload)), target)

        assertTrue(result is DriverDownloader.Result.Ready)
        assertEquals(payload.size.toLong(), target.length())
    }

    @Test
    fun `a mismatched digest is refused and the file is removed`() {
        val target = java.io.File.createTempFile("driver", ".zip").apply { deleteOnExit() }
        val downloader = DriverDownloader({ ByteArrayInputStream(payload) })

        val result = downloader.fetch(packageWith("00".repeat(32)), target)

        assertTrue(result is DriverDownloader.Result.Failed)
        val reason = (result as DriverDownloader.Result.Failed).reason
        assertTrue(reason, reason.contains("checksum"))
        assertTrue("nothing unverified may be left behind", !target.exists())
    }

    @Test
    fun `an empty download is a failure, not a zero-length package`() {
        val target = java.io.File.createTempFile("driver", ".zip").apply { deleteOnExit() }
        val downloader = DriverDownloader({ ByteArrayInputStream(ByteArray(0)) })

        val result = downloader.fetch(packageWith(sha256(ByteArray(0))), target)

        assertTrue(result is DriverDownloader.Result.Failed)
        assertTrue(!target.exists())
    }

    @Test
    fun `a read error is reported with its cause`() {
        val target = java.io.File.createTempFile("driver", ".zip").apply { deleteOnExit() }
        val downloader = DriverDownloader({
            object : java.io.InputStream() {
                override fun read(): Int = throw java.io.IOException("connection reset")
            }
        })

        val result = downloader.fetch(packageWith(sha256(payload)), target)

        assertTrue(result is DriverDownloader.Result.Failed)
        assertTrue((result as DriverDownloader.Result.Failed).reason.contains("connection reset"))
        assertTrue(!target.exists())
    }
}

/**
 * Tests for the gate that decides whether a device may be offered a driver.
 */
class PanVkCatalogTest {

    private fun identity(
        family: String?,
        node: Boolean = true,
    ) = GpuIdentity(
        rawGpuInfo = family?.let { "GPU=$it" },
        hasMaliNode = node,
        vulkanVersion = VulkanVersion(1, 1, 177),
        vendor = "TECNO",
        model = "TECNO CK6",
    )

    @Test
    fun `a Mali-G52 on kbase is offered the universal package`() {
        val packageFor = PanVkCatalog.forDevice(identity("Mali-G52 MC2"))

        assertEquals(PanVkCatalog.PANVK_UNIVERSAL, packageFor)
        assertEquals(null, PanVkCatalog.reasonNotApplicable(identity("Mali-G52 MC2"), apiLevel = 34))
    }

    @Test
    fun `a device without mali0 is refused with the kernel reason`() {
        val reason = PanVkCatalog.reasonNotApplicable(
            identity("Adreno (TM) 740", node = false),
            apiLevel = 34,
        )

        assertTrue(reason.orEmpty(), reason.orEmpty().contains("/dev/mali0"))
    }

    @Test
    fun `an unidentified GPU is refused rather than guessed`() {
        val reason = PanVkCatalog.reasonNotApplicable(identity("Something New"), apiLevel = 34)

        assertTrue(reason.orEmpty(), reason.orEmpty().contains("could not be identified"))
    }

    @Test
    fun `an architecture the packages do not cover is refused`() {
        // v15 is listed by upstream as not fully supported.
        val v15 = MaliArchitecture.V15
        assertTrue(!v15.supportedByPanVk)
    }

    @Test
    fun `an old Android is refused with the API reason`() {
        val reason = PanVkCatalog.reasonNotApplicable(identity("Mali-G52"), apiLevel = 28)

        assertTrue(reason.orEmpty(), reason.orEmpty().contains("API"))
    }

    @Test
    fun `every package carries a published licence`() {
        PanVkCatalog.ALL.forEach { driver ->
            assertTrue(driver.licence.spdx.isNotBlank())
            assertTrue(driver.licence.attribution.isNotBlank())
            assertEquals(64, driver.sha256.length)
            assertEquals(64, driver.librarySha256.length)
        }
    }
}
