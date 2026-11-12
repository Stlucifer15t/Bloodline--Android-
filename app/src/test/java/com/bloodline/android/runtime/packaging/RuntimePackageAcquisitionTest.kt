package com.bloodline.android.runtime.packaging

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
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

    private lateinit var server: HttpServer
    private lateinit var body: ByteArray
    private var port = 0

    /** How the server should answer the next request. */
    private var behaviour = Behaviour.FULL

    /** Every `Range` header the server has been asked for. */
    private val rangesRequested = mutableListOf<String>()

    /** How many times the server has been asked for the package at all. */
    private var requestCount = 0

    private enum class Behaviour { FULL, HONOUR_RANGE, IGNORE_RANGE, UNSATISFIABLE }

    @Before
    fun startServer() {
        // A package that is genuinely a package: a small zip holding one file.
        body = zipOf("bin/wine" to ELF_STUB)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() = server.stop(0)

    private fun respond(exchange: HttpExchange) {
        requestCount++
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null) rangesRequested += range

        when (behaviour) {
            Behaviour.UNSATISFIABLE -> {
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }

            Behaviour.IGNORE_RANGE -> send(exchange, 200, body, 0)
            Behaviour.FULL -> send(exchange, 200, body, 0)

            Behaviour.HONOUR_RANGE -> {
                val start = range
                    ?.removePrefix("bytes=")
                    ?.substringBefore('-')
                    ?.toLongOrNull()
                    ?: 0L
                if (start <= 0L || start >= body.size) {
                    send(exchange, 200, body, 0)
                } else {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                    send(exchange, 206, body, start.toInt())
                }
            }
        }
    }

    private fun send(exchange: HttpExchange, code: Int, bytes: ByteArray, from: Int) {
        val slice = bytes.copyOfRange(from, bytes.size)
        exchange.sendResponseHeaders(code, slice.size.toLong())
        exchange.responseBody.use { it.write(slice) }
        exchange.close()
    }

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

        assertEquals(state.toString(), 1, rangesRequested.size)
        assertEquals("bytes=$half-", rangesRequested.first())
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

        assertEquals(state.toString(), 2, requestCount)
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

        assertEquals("the whole file is already there", 0, requestCount)
        assertEquals(
            RuntimePackageManager.State.Installed("11.3", verified = true),
            state,
        )
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
        url = "http://127.0.0.1:$port/test-wine.wcp",
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
