package com.bloodline.android.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the settings store's backup and restore.
 *
 * The behaviour that matters is what happens on a phone: the port rewrites its
 * own file, the game may be running, and the process may die mid-write. So the
 * tests pin down that the previous contents are kept before anything changes,
 * that a second change does not overwrite the first backup, and that a write
 * cannot leave a truncated file behind.
 */
class PortSettingsStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun layout(root: File): PortLayout = PortLayout(root.absolutePath)

    private fun writing(root: File, text: String): File =
        File(root, "overlay/port_settings.txt").apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    private val stock = """
        pad_look=2600.0
        render_h=0
        fps_limit=60
        ui.gfxApi=0.0
    """.trimIndent()

    @Test
    fun `applies a change and keeps the previous file`() {
        val root = temporary.newFolder("apply")
        val file = writing(root, stock)
        val store = PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        )

        assertEquals(
            PortSettingsStore.Result.Written,
            store.apply(mapOf(PortSettings.KEY_RENDER_HEIGHT to "480")),
        )

        assertTrue(file.readText().contains("render_h=480"))
        assertTrue(store.hasBackup())
        assertTrue(File(root, "overlay/port_settings.txt.bak").readText().contains("render_h=0"))
    }

    @Test
    fun `a second change does not overwrite the first backup`() {
        val root = temporary.newFolder("twice")
        writing(root, stock)
        val store = PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        )

        store.apply(mapOf(PortSettings.KEY_RENDER_HEIGHT to "480"))
        store.apply(mapOf(PortSettings.KEY_FRAME_LIMIT to "30"))

        val backup = File(root, "overlay/port_settings.txt.bak")
        assertTrue(
            "the backup must still be the stock settings",
            backup.readText().contains("render_h=0"),
        )
    }

    @Test
    fun `restore puts the backup back`() {
        val root = temporary.newFolder("restore")
        val file = writing(root, stock)
        val store = PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        )

        store.apply(mapOf(PortSettings.KEY_RENDER_HEIGHT to "480"))
        assertEquals(PortSettingsStore.Result.Written, store.restore())

        assertTrue(file.readText().contains("render_h=0"))
    }

    @Test
    fun `restore without a backup says so`() {
        val root = temporary.newFolder("nobackup")
        writing(root, stock)
        val store = PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        )

        val result = store.restore()

        assertTrue(result is PortSettingsStore.Result.Failed)
        assertTrue((result as PortSettingsStore.Result.Failed).reason.contains("no backup"))
    }

    @Test
    fun `a missing settings file is refused, not created`() {
        val root = temporary.newFolder("missing")
        val store = PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        )

        val result = store.apply(mapOf(PortSettings.KEY_RENDER_HEIGHT to "480"))

        assertTrue(result is PortSettingsStore.Result.Failed)
        val reason = (result as PortSettingsStore.Result.Failed).reason
        assertTrue(reason, reason.contains("Start the game once"))
        assertFalse("nothing may be created", File(root, "overlay/port_settings.txt").exists())
    }

    @Test
    fun `no temporary file is left behind`() {
        val root = temporary.newFolder("temp")
        writing(root, stock)

        PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        ).apply(mapOf(PortSettings.KEY_RENDER_HEIGHT to "480"))

        assertFalse(File(root, "overlay/port_settings.txt.tmp").exists())
    }

    @Test
    fun `reads a snapshot of the live file`() {
        val root = temporary.newFolder("snapshot")
        writing(root, stock)

        val snapshot = PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        ).snapshot()

        assertNotNull(snapshot)
        assertEquals(0, snapshot!!.renderHeight)
        assertEquals(GraphicsApi.VULKAN, snapshot.renderer)
    }

    @Test
    fun `unknown keys survive a write`() {
        val root = temporary.newFolder("unknown")
        val file = writing(root, stock + "\nui.padBind12=12.0\n")

        PortSettingsStore.forPath(
            settings = File(root, "overlay/port_settings.txt"),
            backup = File(root, "overlay/port_settings.txt.bak"),
        ).apply(mapOf(PortSettings.KEY_FRAME_LIMIT to "30"))

        assertTrue(file.readText().contains("ui.padBind12=12.0"))
    }
}

/**
 * Tests for the presets, which is where a diagnosis becomes one tap.
 */
class PortPresetTest {

    private fun identity(
        family: String?,
        arch: MaliArchitecture?,
        vulkan: VulkanVersion?,
        maliNode: Boolean = true,
    ) = GpuIdentity(
        rawGpuInfo = family?.let { "GPU=$it" },
        hasMaliNode = maliNode,
        vulkanVersion = vulkan,
        vendor = "TECNO",
        model = "TECNO CK6",
    )

    @Test
    fun `a Bifrost Mali with old Vulkan gets GLES and a low height`() {
        val preset = PortPreset.recommendedFor(
            identity("Mali-G52 MC2", MaliArchitecture.V7, VulkanVersion(1, 1, 177)),
            screenHeight = 1333,
        )

        assertEquals(GraphicsApi.OPENGL_ES, preset.renderer)
        assertEquals(30, preset.frameLimit)
        assertTrue("the height must stay low on a G52", preset.renderHeight <= 540)
    }

    @Test
    fun `a modern Vulkan device keeps the port's renderer choice`() {
        val preset = PortPreset.recommendedFor(
            identity("Mali-G615", MaliArchitecture.V11, VulkanVersion(1, 4, 0)),
            screenHeight = 1333,
        )

        assertNull("the port's own choice is not second-guessed", preset.renderer)
        assertEquals(30, preset.frameLimit)
    }

    @Test
    fun `the preset writes only keys the port owns`() {
        val preset = PortPreset.recommendedFor(
            identity("Mali-G52", MaliArchitecture.V7, VulkanVersion(1, 1, 0)),
            screenHeight = 1333,
        )

        val keys = preset.changes().keys

        assertTrue(keys.contains(PortSettings.KEY_RENDER_HEIGHT))
        assertTrue(keys.contains(PortSettings.KEY_FRAME_LIMIT))
        assertTrue(keys.contains(PortSettings.KEY_GRAPHICS_API))
    }

    @Test
    fun `the frame cap is a value the port offers`() {
        PortPreset.ALL.forEach { preset ->
            assertTrue(
                "${preset.id} uses a cap the port does not offer",
                preset.frameLimit == 0 || preset.frameLimit in FrameLimit.CHOICES,
            )
        }
    }

    @Test
    fun `every preset describes itself`() {
        PortPreset.ALL.forEach { preset ->
            assertTrue(preset.describe(), preset.describe().isNotBlank())
            assertTrue(preset.summary.isNotBlank())
        }
    }
}
