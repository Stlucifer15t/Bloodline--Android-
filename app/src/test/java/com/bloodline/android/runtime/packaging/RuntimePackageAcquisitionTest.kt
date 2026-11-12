package com.bloodline.android.runtime.packaging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the acquisition path against a real HTTP server.
 *
 * These are not mocks. A real server answers real requests over a real socket,
 * because the behaviour under test is a conversation, not a function call:
 * what matters is whether a `Range` header is sent, what the server answers,
 * and whether the bytes that end up on disk are the bytes that were served.
 * A fake connection object can only confirm that the code calls the methods we
 * guessed it would call.
 *
 * The server is local and the only thing outside the machine is nothing, so
 * these run anywhere the unit tests run.
 */
class RuntimePackageAcquisitionTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var server: TestHttpServer

    private lateinit var body: ByteArray

    /** How the server should answer the next request. */
    private var behaviour = Behaviour.FULL



    @Before
    fun startServer() {
        // A package that is genuinely a package: a small zip holding one file.
        body = zipOf("bin/wine" to ELF_STUB)
        server = TestHttpServer(body) { behaviour }.start()
    }

    @After
    fun stopServer() = server.stop()

    /** Every `Range` header the server has been asked for. */
    private fun rangesRequested(): List<String> = server.rangesRequested()

    /** How many times the server has been asked for the package at all. */
    private fun requestCount(): Int = server.requestCount()

    private fun url(): String = "http://127.0.0.1:${server.port()}/test-wine.wcp"

    // ------------------------------------------------------------------ tests

    @Test
    fun `a package is downloaded, verified, unpacked and marked installed`() {
        val (store, manager) = manager()
        val pack = pack(sha256 = sha256Of(body))

        val state = manager.install(pack, "arm64-v8a")

        assertEquals(
            state.toString(),
            RuntimePackageManager.State.Installed(version = "11.3", verified = true),
            state,
        )
        assertTrue(File(store.packageDir(pack), "bin/wine").isFile)
        // The staged copy is not kept: it would be a second 226 MB.
        assertFalse(store.stagedFile(pack).exists())
    }

    @Test
    fun `a package with no published checksum installs, and says it is unverified`() {
        val (_, manager) = manager()
        val pack = pack(sha256 = null)

        val state = manager.install(pack, "arm64-v8a")

        assertEquals(
            RuntimePackageManager.State.Installed(version = "11.3", verified = false),
            state,
        )
    }

    @Test
    fun `a package whose checksum does not match is refused and discarded`() {
        val (store, manager) = manager()
        val pack = pack(sha256 = "0".repeat(64))

        val state = manager.install(pack, "arm64-v8a")

        assertTrue(state is RuntimePackageManager.State.Failed)
        assertTrue((state as RuntimePackageManager.State.Failed).reason.contains("checksum"))
        assertFalse(File(store.packageDir(pack), "bin/wine").exists())
    }

    @Test
    fun `a download that is short of the published length is refused`() {
        val (_, manager) = manager()
        // Published as larger than it is, which is what a truncated transfer
        // looks like from here.
        val pack = pack(sha256 = null).copy(sizeBytes = body.size + 4096L)

        val state = manager.install(pack, "arm64-v8a")

        assertTrue(state is RuntimePackageManager.State.Failed)
        assertTrue((state as RuntimePackageManager.State.Failed).reason.contains("wrong length"))
    }

    @Test
    fun `an interrupted download resumes instead of starting again`() {
        behaviour = Behaviour.HONOUR_RANGE
        val (store, manager) = manager()
        val pack = pack(sha256 = sha256Of(body))

        // What a transfer cut off at the halfway point leaves behind.
        val half = body.size / 2
        store.stagedFile(pack).parentFile?.mkdirs()
        store.stagedFile(pack).writeBytes(body.copyOfRange(0, half))

        val state = manager.install(pack, "arm64-v8a")

        assertEquals(state.toString(), 1, rangesRequested().size)
        assertEquals("bytes=$half-", rangesRequested().first())
        assertEquals(
            RuntimePackageManager.State.Installed("11.3", verified = true),
            state,
        )
        assertEquals(ELF_STUB, File(store.packageDir(pack), "bin/wine").readText())
    }

    @Test
    fun `a server that ignores the range does not get a file appended to`() {
        // The dangerous case. Appending a full 200 response to a partial file
        // produces something of plausible size and entirely wrong content,
        // which is why the partial file is discarded instead.
        behaviour = Behaviour.IGNORE_RANGE
        val (store, manager) = manager()
        val pack = pack(sha256 = sha256Of(body))

        store.stagedFile(pack).parentFile?.mkdirs()
        // Smaller than the whole package, as a real interrupted transfer
        // would be, so the resume path is the one that runs.
        store.stagedFile(pack).writeBytes(ByteArray(body.size / 3) { 0x7A })

        val state = manager.install(pack, "arm64-v8a")

        assertEquals(state.toString(), 2, requestCount())
        assertEquals(
            RuntimePackageManager.State.Installed("11.3", verified = true),
            state,
        )
        assertEquals(ELF_STUB, File(store.packageDir(pack), "bin/wine").readText())
    }

    @Test
    fun `a range the server will not satisfy restarts the download cleanly`() {
        // What happens when upstream replaces an asset underneath a pin: the
        // bytes we already have no longer correspond to what it is serving.
        behaviour = Behaviour.UNSATISFIABLE
        val (store, manager) = manager()
        val pack = pack(sha256 = null)

        store.stagedFile(pack).parentFile?.mkdirs()
        store.stagedFile(pack).writeBytes(ByteArray(64))

        val state = manager.install(pack, "arm64-v8a")

        assertTrue(state is RuntimePackageManager.State.Failed)
        assertTrue(
            (state as RuntimePackageManager.State.Failed).reason,
            (state as RuntimePackageManager.State.Failed).reason.contains("416"),
        )
    }

    @Test
    fun `a complete staged file is not downloaded a second time`() {
        val (store, manager) = manager()
        val pack = pack(sha256 = sha256Of(body))

        store.stagedFile(pack).parentFile?.mkdirs()
        store.stagedFile(pack).writeBytes(body)

        val state = manager.install(pack, "arm64-v8a")

        assertEquals("the whole file is already there", 0, requestCount())
        assertEquals(
            RuntimePackageManager.State.Installed("11.3", verified = true),
            state,
        )
    }

    // ------------------------------------------- what the caller is told

    /**
     * The regression, and the reason this file now tests the orchestrator
     * rather than just the downloader.
     *
     * `install` worked out that the checksum did not match and said so. The
     * caller threw that away, re-read an install marker that no failure ever
     * writes, and reported "The package did not install (Missing)" — the same
     * sentence for a refused socket, a truncated transfer, a bad checksum and a
     * broken archive. That is what reached the phone.
     */
    @Test
    fun `a package that fails reports the reason install worked out`() {
        val (_, manager) = manager()
        val acquisition = RuntimeAcquisition(manager, "arm64-v8a")
        val pack = pack(sha256 = "0".repeat(64))

        val outcome = acquisition.installAll(listOf(pack))

        assertTrue(outcome is RuntimeAcquisition.Outcome.Failed)
        val failed = outcome as RuntimeAcquisition.Outcome.Failed
        assertEquals("Test Wine", failed.packageName)
        assertTrue(
            "the reason install gave must reach the user, got: ${failed.reason}",
            failed.reason.contains("checksum"),
        )
    }

    @Test
    fun `a truncated download reports the length, not a generic failure`() {
        val (_, manager) = manager()
        val acquisition = RuntimeAcquisition(manager, "arm64-v8a")
        val pack = pack(sha256 = null).copy(sizeBytes = body.size + 4096L)

        val outcome = acquisition.installAll(listOf(pack))

        assertTrue(outcome is RuntimeAcquisition.Outcome.Failed)
        assertTrue(
            (outcome as RuntimeAcquisition.Outcome.Failed).reason.contains("wrong length"),
        )
    }

    @Test
    fun `a device the packages were not built for is refused, naming the device`() {
        // No network involved, so this is what a phone that cannot run Wine at
        // all reports, and it has to say which ABI it saw.
        val (_, manager) = manager()
        val acquisition = RuntimeAcquisition(manager, "x86_64")

        val outcome = acquisition.installAll(listOf(pack(sha256 = sha256Of(body))))

        assertTrue(outcome is RuntimeAcquisition.Outcome.Failed)
        val failed = outcome as RuntimeAcquisition.Outcome.Failed
        assertTrue(failed.reason.contains("x86_64"))
        assertTrue(failed.reason.contains("arm64-v8a"))
    }

    @Test
    fun `an empty set has nothing to do rather than claiming success`() {
        val (_, manager) = manager()

        assertEquals(
            RuntimeAcquisition.Outcome.NothingToDo,
            RuntimeAcquisition(manager, "arm64-v8a").installAll(emptyList()),
        )
    }

    @Test
    fun `a second run skips what is already installed and says so`() {
        // Otherwise a retry reports having downloaded 226 MB it did not touch.
        val (_, manager) = manager()
        val acquisition = RuntimeAcquisition(manager, "arm64-v8a")
        val pack = pack(sha256 = sha256Of(body))

        val first = acquisition.installAll(listOf(pack))
        val second = acquisition.installAll(listOf(pack))

        assertEquals(RuntimeAcquisition.Outcome.Installed(1, 0), first)
        assertEquals(RuntimeAcquisition.Outcome.Installed(0, 1), second)
        // Nothing was fetched the second time: the file was already there.
        assertEquals(1, requestCount())
    }

    @Test
    fun `progress counts the packages left to fetch, not every package`() {
        val (_, manager) = manager()
        val acquisition = RuntimeAcquisition(manager, "arm64-v8a")
        val first = pack(sha256 = sha256Of(body))
        val second = first.copy(id = "test-wow64", target = "packages/test-wow64")
        val seen = mutableListOf<String>()

        val outcome = acquisition.installAll(listOf(first, second)) { seen += it.toString() }

        assertEquals(RuntimeAcquisition.Outcome.Installed(2, 0), outcome)
        assertTrue(seen.isNotEmpty())
        assertTrue(
            "the first package must be reported as 1 of 2, got: ${seen.first()}",
            seen.first().contains("1 of 2"),
        )
        assertTrue(seen.last().contains("2 of 2"))
    }

    // ---------------------------------------------------------------- helpers

    private fun manager(): Pair<RuntimePackageStore, RuntimePackageManager> {
        val store = RuntimePackageStore(temporary.newFolder("runtime-acquire"))
        return store to RuntimePackageManager(store)
    }

    private fun pack(sha256: String?): RuntimePackage = RuntimePackage(
        id = "test-wine",
        type = PackageType.WINE,
        name = "Test Wine",
        version = "11.3",
        abi = "arm64-v8a",
        url = url(),
        sizeBytes = body.size.toLong(),
        sha256 = sha256,
        licence = Licence(
            spdx = "MIT",
            name = "MIT",
            referenceUrl = "https://opensource.org/licenses/MIT",
            sourceUrl = "https://example.org",
            attribution = "Test",
        ),
        target = "packages/test-wine",
        required = true,
    )

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Enough of an ELF header to be recognised as a program. */
        const val ELF_STUB = "\u007FELF"
    }
}

/**
 * A deliberately small HTTP server, speaking just enough of the protocol.
 *
 * It exists because `com.sun.net.httpserver` is not on an Android unit-test
 * classpath, and more importantly because the point of these tests is to
 * control the answer: a 206, a 200 that ignores a range, or a 416. A real
 * server library decides that for you and makes the interesting cases awkward
 * to produce.
 *
 * One request per connection, no keep-alive, one request at a time — enough
 * for a client that fetches one file, and simple enough that the server
 * cannot be the reason a test fails.
 */
private enum class Behaviour { FULL, HONOUR_RANGE, IGNORE_RANGE, UNSATISFIABLE }

private class TestHttpServer(
    private val body: ByteArray,
    private val behaviour: () -> Behaviour,
) {

    private lateinit var socket: ServerSocket
    private lateinit var thread: Thread
    private val ranges = mutableListOf<String>()
    private var requests = 0

    fun start(): TestHttpServer {
        socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread = Thread({
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (error: Exception) {
                    return@Thread
                }
                try {
                    handle(connection)
                } catch (error: Exception) {
                    // A client that hung up mid-request is not a test failure.
                } finally {
                    connection.close()
                }
            }
        }, "test-http").apply { isDaemon = true; start() }
        return this
    }

    fun port(): Int = socket.localPort

    fun stop() = socket.close()

    @Synchronized
    fun rangesRequested(): List<String> = ranges.toList()

    @Synchronized
    fun requestCount(): Int = requests

    private fun handle(connection: java.net.Socket) {
        val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.US_ASCII))

        // The request line, then headers until the blank line.
        reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                range = line.substringAfter(':').trim()
            }
        }

        synchronized(this) {
            requests++
            if (range != null) ranges += range
        }

        val start = range
            ?.removePrefix("bytes=")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()
            ?: 0L

        when (behaviour()) {
            Behaviour.UNSATISFIABLE -> {
                // What an asset replaced underneath a pin looks like: the
                // bytes we already hold no longer correspond to what exists.
                connection.getOutputStream().write(text(416, "Range Not Satisfiable", ByteArray(0)))
            }

            Behaviour.IGNORE_RANGE -> {
                connection.getOutputStream().write(full(200, body))
            }

            Behaviour.FULL -> {
                connection.getOutputStream().write(full(200, body))
            }

            Behaviour.HONOUR_RANGE -> {
                if (start <= 0L || start >= body.size) {
                    connection.getOutputStream().write(full(200, body))
                } else {
                    val from = start.toInt()
                    val slice = body.copyOfRange(from, body.size)
                    val head = ("HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Length: ${slice.size}\r\n" +
                        "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    connection.getOutputStream().apply {
                        write(head + slice)
                        flush()
                    }
                }
            }
        }
    }

    private fun full(code: Int, bytes: ByteArray): ByteArray = text(code, "OK", bytes)

    /**
     * Header and body concatenated as bytes, never through a String.
     *
     * A package is binary, and round-tripping it through a String re-encodes
     * every byte above 0x7F, which silently changes the file and makes the
     * length wrong for a reason that has nothing to do with the code under
     * test.
     */
    private fun text(code: Int, reason: String, bytes: ByteArray): ByteArray =
        ("HTTP/1.1 $code $reason\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + bytes
}
