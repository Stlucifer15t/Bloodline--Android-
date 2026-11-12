package com.bloodline.android.game

import com.bloodline.android.storage.InMemoryInstallMetadataStore
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository exists to stop two real bugs: importing freezing the launcher,
 * and reopening it being slow. Both come down to *when* a scan happens, which
 * is exactly what these tests pin down.
 */
class GameInstallRepositoryTest {

    // ------------------------------------------------------------ fake storage

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
        var listCount = 0
            private set

        override fun entries(): List<StorageEntry> {
            listCount++
            return nodes.map { node ->
                StorageEntry(node.name, node.isDirectory) {
                    FakeFolder(
                        location.copy(displayPath = "${location.displayPath}/${node.name}"),
                        node.children,
                    )
                }
            }
        }
    }

    /** A folder that throws, as when Android revokes access to a tree. */
    private class BrokenFolder(override val location: GameLocation) : StorageFolder {
        override fun entries(): List<StorageEntry> = error("permission revoked")
    }

    private fun gameFolder(
        reference: String = "content://tree/game",
        vararg nodes: Node,
    ) = FakeFolder(
        GameLocation(LocationKind.EXTERNAL_FOLDER, reference, "/storage/game"),
        nodes.toList(),
    )

    private val completeGame = dir(
        "Vampire",
        file("pak01.vpk"),
    )

    /** Runs the task inline, so the test sees the result deterministically. */
    private val directExecutor = Executor { it.run() }

    private fun repository(
        roots: List<StorageFolder>,
        store: InMemoryInstallMetadataStore = InMemoryInstallMetadataStore(),
        clock: () -> Long = { NOW },
        executor: Executor = directExecutor,
    ) = GameInstallRepository(
        roots = { roots },
        metadataStore = store,
        clock = clock,
        appVersion = APP_VERSION,
        executor = executor,
    )

    // ------------------------------------------------------------------- tests

    @Test
    fun `a fresh cached result is reused without scanning`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()
        val first = repository(listOf(folder), store)

        assertTrue(first.ensureInstall(force = true, onProgress = {}, onResult = {}))
        assertEquals(1, folder.listCount)

        val results = mutableListOf<InstallScanReport>()
        val second = repository(listOf(folder), store)

        second.ensureInstall(onProgress = {}, onResult = { results += it })

        assertEquals(1, results.size)
        assertEquals(ScanSource.CACHE, results.single().source)
        // Still one listing: the folder was never walked again.
        assertEquals(1, folder.listCount)
    }

    @Test
    fun `the cached result is remembered across repository instances`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()

        repository(listOf(folder), store).ensureInstall(force = true, onProgress = {}, onResult = {})

        val reloaded = repository(listOf(folder), store).cached()

        assertNotNull(reloaded)
        assertTrue(reloaded!!.hasDataDirectory)
        assertEquals(LocationKind.EXTERNAL_FOLDER, reloaded.kind)
        assertEquals("/storage/game", reloaded.displayPath)
    }

    @Test
    fun `a stale cached result is rescanned`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()

        val first = repository(listOf(folder), store, clock = { NOW })
        first.ensureInstall(force = true, onProgress = {}, onResult = {})

        val later = NOW + GameInstallRepository.STALE_AFTER_MILLIS + 1
        val results = mutableListOf<InstallScanReport>()
        val second = repository(listOf(folder), store, clock = { later })

        second.ensureInstall(onProgress = {}, onResult = { results += it })

        assertEquals(ScanSource.FRESH, results.single().source)
        assertEquals(2, folder.listCount)
    }

    @Test
    fun `picking a different folder invalidates the cached result`() {
        val original = gameFolder(reference = "content://tree/one", file("Vampire.exe"), completeGame)
        val other = gameFolder(
            reference = "content://tree/two",
            nodes = arrayOf(dir("empty", file("readme.txt"))),
        )
        val store = InMemoryInstallMetadataStore()

        repository(listOf(original), store).ensureInstall(force = true, onProgress = {}, onResult = {})

        val results = mutableListOf<InstallScanReport>()
        repository(listOf(other), store).ensureInstall(onProgress = {}, onResult = { results += it })

        assertEquals(ScanSource.FRESH, results.single().source)
        assertNull(results.single().install)
    }

    @Test
    fun `a new app version invalidates the cached result`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()

        repository(listOf(folder), store, clock = { NOW })
            .ensureInstall(force = true, onProgress = {}, onResult = {})

        val results = mutableListOf<InstallScanReport>()
        GameInstallRepository(
            roots = { listOf(folder) },
            metadataStore = store,
            clock = { NOW },
            appVersion = "0.9.0-other",
            executor = directExecutor,
        ).ensureInstall(onProgress = {}, onResult = { results += it })

        assertEquals(ScanSource.FRESH, results.single().source)
    }

    @Test
    fun `force rescans even when the cache is fresh`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()

        repository(listOf(folder), store).ensureInstall(force = true, onProgress = {}, onResult = {})
        val results = mutableListOf<InstallScanReport>()
        repository(listOf(folder), store).ensureInstall(
            force = true,
            onProgress = {},
            onResult = { results += it },
        )

        assertEquals(ScanSource.FRESH, results.single().source)
    }

    @Test
    fun `only one scan runs at a time`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()

        val repository = GameInstallRepository(
            roots = { listOf(folder) },
            metadataStore = store,
            clock = { NOW },
            appVersion = APP_VERSION,
            // Never runs the task, so the first scan stays in flight.
            executor = Executor { },
        )

        val started = repository.ensureInstall(force = true, onProgress = {}, onResult = {})
        val second = repository.ensureInstall(force = true, onProgress = {}, onResult = {})

        assertTrue(started)
        assertFalse("a second concurrent scan must be refused", second)
        assertTrue(repository.isScanning)
    }

    @Test
    fun `progress reports real entries and the real stage`() {
        val folder = gameFolder(
            nodes = arrayOf(
                file("Vampire.exe"),
                completeGame,
                file("unins000.exe"),
            )
        )
        val progress = mutableListOf<ScanProgress>()

        val results = mutableListOf<InstallScanReport>()
        repository(listOf(folder)).ensureInstall(
            force = true,
            onProgress = { progress += it },
            onResult = { results += it },
        )

        val scanning = progress.first { it.stage == ScanStage.SCANNING_FOLDERS }
        val checking = progress.first { it.stage == ScanStage.CHECKING_REQUIRED_FILES }

        // The executable is found while walking, so the required-files check
        // can only happen after at least one folder was listed.
        assertTrue(scanning.entriesExamined >= 3)
        assertTrue(checking.entriesExamined >= scanning.entriesExamined)
        assertEquals(
            results.single().entriesExamined,
            progress.filter { it.stage != ScanStage.FINISHED }.last().entriesExamined,
        )
        assertTrue(progress.last().stage == ScanStage.FINISHED)
    }

    @Test
    fun `a scan failure is reported instead of being swallowed`() {
        val broken = BrokenFolder(GameLocation(LocationKind.EXTERNAL_FOLDER, "x", "/x"))
        val results = mutableListOf<InstallScanReport>()

        repository(listOf(broken)).ensureInstall(
            force = true,
            onProgress = {},
            onResult = { results += it },
        )

        val report = results.single()
        assertNull(report.install)
        assertNotNull("the caller must be able to say what went wrong", report.error)
    }

    @Test
    fun `clearing the cache forgets the remembered result`() {
        val folder = gameFolder(nodes = arrayOf(file("Vampire.exe"), completeGame))
        val store = InMemoryInstallMetadataStore()
        val repository = repository(listOf(folder), store)

        repository.ensureInstall(force = true, onProgress = {}, onResult = {})
        assertNotNull(repository.cached())

        repository.clearCache()

        assertNull(repository.cached())
        assertFalse(repository.isCachedFresh())
    }

    @Test
    fun `a cheap scan that found nothing is repeated on the next start`() {
        val empty = gameFolder(nodes = arrayOf(dir("Docs", file("readme.txt"))))
        val store = InMemoryInstallMetadataStore()

        repository(listOf(empty), store).ensureInstall(force = true, onProgress = {}, onResult = {})

        val results = mutableListOf<InstallScanReport>()
        repository(listOf(empty), store).ensureInstall(onProgress = {}, onResult = { results += it })

        // Cheap to redo, and the user may have copied their files in since.
        assertEquals(ScanSource.FRESH, results.single().source)
    }

    @Test
    fun `an expensive scan that found nothing is not repeated`() {
        // A large folder with no game in it: walking it costs real time.
        val nodes = (0..GameInstallRepository.CHEAP_SCAN_ENTRIES).map { file("file$it.bin") }
        val huge = gameFolder(nodes = nodes.toTypedArray())
        val store = InMemoryInstallMetadataStore()

        repository(listOf(huge), store).ensureInstall(force = true, onProgress = {}, onResult = {})

        val results = mutableListOf<InstallScanReport>()
        repository(listOf(huge), store).ensureInstall(onProgress = {}, onResult = { results += it })

        assertEquals(ScanSource.CACHE, results.single().source)
        assertEquals(1, huge.listCount)
    }

    @Test
    fun `a cheap scan that found nothing is repeated on the next start`() {
        val empty = gameFolder(nodes = arrayOf(dir("Docs", file("readme.txt"))))
        val store = InMemoryInstallMetadataStore()

        repository(listOf(empty), store).ensureInstall(force = true, onProgress = {}, onResult = {})

        val results = mutableListOf<InstallScanReport>()
        repository(listOf(empty), store).ensureInstall(onProgress = {}, onResult = { results += it })

        // Cheap to redo, and the user may have copied their files in since.
        assertEquals(ScanSource.FRESH, results.single().source)
    }

    @Test
    fun `an expensive scan that found nothing is not repeated`() {
        // A large folder with no game in it: walking it costs real time.
        val nodes = (0..GameInstallRepository.CHEAP_SCAN_ENTRIES).map { file("file$it.bin") }
        val huge = gameFolder(nodes = nodes.toTypedArray())
        val store = InMemoryInstallMetadataStore()

        repository(listOf(huge), store).ensureInstall(force = true, onProgress = {}, onResult = {})

        val results = mutableListOf<InstallScanReport>()
        repository(listOf(huge), store).ensureInstall(onProgress = {}, onResult = { results += it })

        assertEquals(ScanSource.CACHE, results.single().source)
        assertEquals(1, huge.listCount)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val APP_VERSION = "0.4.0"
    }
}
