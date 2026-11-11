package com.bloodline.android.game

import com.bloodline.android.storage.FileSystemFolder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for game detection. No Android framework is involved, even for the
 * Storage Access Framework case: that is covered by [FakeFolder], which behaves
 * like a document tree without needing a device.
 */
class GameInstallScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun scanner(vararg folders: StorageFolder) = GameInstallScanner(folders.toList())

    private fun appFolder(directory: File) = FileSystemFolder(directory)

    // ---------------------------------------------------------- fake backend

    private class Node(
        val name: String,
        val isDirectory: Boolean,
        val children: List<Node> = emptyList(),
    )

    private fun file(name: String) = Node(name, isDirectory = false)

    private fun dir(name: String, vararg children: Node) =
        Node(name, isDirectory = true, children = children.toList())

    private class FakeFolder(
        override val location: GameLocation,
        private val nodes: List<Node>,
    ) : StorageFolder {
        override fun entries(): List<StorageEntry> = nodes.map { node ->
            StorageEntry(node.name, node.isDirectory) {
                FakeFolder(location.child(node.name), node.children)
            }
        }
    }

    /** A folder we are no longer allowed to read, as when access is revoked. */
    private class BrokenFolder(
        override val location: GameLocation,
    ) : StorageFolder {
        override fun entries(): List<StorageEntry> =
            throw SecurityException("Permission revoked")
    }

    private fun GameLocation.child(name: String) = copy(displayPath = "$displayPath/$name")

    private fun externalFolder(
        vararg nodes: Node,
        reference: String = "content://provider/tree/1/document/1",
    ) = FakeFolder(
        GameLocation(
            kind = LocationKind.EXTERNAL_FOLDER,
            reference = reference,
            displayPath = "Bloodlines",
        ),
        nodes.toList(),
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun `returns null when no game files exist`() {
        val root = temporaryFolder.newFolder("empty")

        assertNull(scanner(appFolder(root)).findInstall())
    }

    @Test
    fun `returns null when only unrelated executables exist`() {
        val root = temporaryFolder.newFolder("other-game").apply {
            File(this, "HalfLife2.exe").createNewFile()
        }

        assertNull(scanner(appFolder(root)).findInstall())
    }

    @Test
    fun `finds an installation in app storage`() {
        val root = temporaryFolder.newFolder("game").apply {
            File(this, "Vampire.exe").createNewFile()
            File(this, "Vampire").mkdirs()
        }

        val install = checkNotNull(scanner(appFolder(root)).findInstall())

        assertEquals(LocationKind.APP_STORAGE, install.location.kind)
        assertEquals(root.absolutePath, install.location.reference)
        assertTrue(install.hasDataDirectory)
        assertTrue(install.isComplete)
    }

    @Test
    fun `reports a partial install when the data folder is missing`() {
        val root = temporaryFolder.newFolder("partial").apply {
            File(this, "Vampire.exe").createNewFile()
        }

        val install = checkNotNull(scanner(appFolder(root)).findInstall())

        assertFalse(install.hasDataDirectory)
        assertFalse(install.isComplete)
    }

    @Test
    fun `matches the executable name case insensitively`() {
        val root = temporaryFolder.newFolder("case").apply {
            File(this, "vampire.EXE").createNewFile()
            File(this, "vampire").mkdirs()
        }

        assertTrue(checkNotNull(scanner(appFolder(root)).findInstall()).isComplete)
    }

    @Test
    fun `finds an installation nested inside sub folders`() {
        val root = temporaryFolder.newFolder("root")
        val nested = File(root, "Bloodlines").apply {
            mkdirs()
            File(this, "Vampire.exe").createNewFile()
            File(this, "Vampire").mkdirs()
        }

        val install = checkNotNull(scanner(appFolder(root)).findInstall())

        assertEquals(nested.absolutePath, install.location.reference)
    }

    @Test
    fun `stops scanning beyond the configured depth`() {
        val root = temporaryFolder.newFolder("deep")
        File(root, "a/b/c").apply {
            mkdirs()
            File(this, "Vampire.exe").createNewFile()
        }

        assertNull(scanner(appFolder(root)).findInstall())
    }

    @Test
    fun `prefers a complete installation over a partial one`() {
        val partial = temporaryFolder.newFolder("partial").apply {
            File(this, "Vampire.exe").createNewFile()
        }
        val complete = temporaryFolder.newFolder("complete").apply {
            File(this, "Vampire.exe").createNewFile()
            File(this, "Vampire").mkdirs()
        }

        val install = checkNotNull(scanner(appFolder(partial), appFolder(complete)).findInstall())

        assertEquals(complete.absolutePath, install.location.reference)
        assertTrue(install.isComplete)
    }

    @Test
    fun `searches roots in the order they are given`() {
        val first = temporaryFolder.newFolder("first").apply {
            File(this, "Vampire.exe").createNewFile()
        }
        val second = temporaryFolder.newFolder("second").apply {
            File(this, "Vampire.exe").createNewFile()
        }

        assertEquals(
            first.absolutePath,
            checkNotNull(scanner(appFolder(first), appFolder(second)).findInstall()).location.reference,
        )
        assertEquals(
            second.absolutePath,
            checkNotNull(scanner(appFolder(second), appFolder(first)).findInstall()).location.reference,
        )
    }

    @Test
    fun `ignores hidden folders`() {
        val root = temporaryFolder.newFolder("with-hidden")
        File(root, ".cache").apply {
            mkdirs()
            File(this, "Vampire.exe").createNewFile()
        }

        assertNull(scanner(appFolder(root)).findInstall())
    }

    @Test
    fun `tolerates roots that do not exist`() {
        val missing = File(temporaryFolder.root, "does-not-exist")

        assertNull(scanner(appFolder(missing)).findInstall())
    }

    @Test
    fun `finds an installation in an external folder`() {
        val root = externalFolder(
            file("Vampire.exe"),
            dir("Vampire", file("pak0.vpk")),
        )

        val install = checkNotNull(scanner(root).findInstall())

        assertEquals(LocationKind.EXTERNAL_FOLDER, install.location.kind)
        assertEquals("content://provider/tree/1/document/1", install.location.reference)
        assertTrue(install.isComplete)
    }

    @Test
    fun `reports the sub folder of an external tree that holds the game`() {
        val root = externalFolder(
            dir(
                "Bloodlines",
                file("Vampire.exe"),
                dir("Vampire", file("pak0.vpk")),
            ),
        )

        val install = checkNotNull(scanner(root).findInstall())

        assertEquals("Bloodlines/Bloodlines", install.location.displayPath)
    }

    @Test
    fun `prefers the external folder over app storage`() {
        val appRoot = temporaryFolder.newFolder("app").apply {
            File(this, "Vampire.exe").createNewFile()
            File(this, "Vampire").mkdirs()
        }
        val external = externalFolder(file("Vampire.exe"), dir("Vampire"))

        val install = checkNotNull(scanner(external, appFolder(appRoot)).findInstall())

        assertEquals(LocationKind.EXTERNAL_FOLDER, install.location.kind)
    }

    @Test
    fun `skips folders that cannot be read`() {
        val broken = BrokenFolder(
            GameLocation(LocationKind.EXTERNAL_FOLDER, "content://gone", "gone"),
        )
        val working = temporaryFolder.newFolder("working").apply {
            File(this, "Vampire.exe").createNewFile()
            File(this, "Vampire").mkdirs()
        }

        val install = checkNotNull(scanner(broken, appFolder(working)).findInstall())

        assertEquals(LocationKind.APP_STORAGE, install.location.kind)
    }
}
