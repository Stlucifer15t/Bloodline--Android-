package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for loading the libraries this app ships.
 *
 * The loader function is injected, which is what makes any of this testable:
 * `System.load` does nothing on a JVM unit test, but the *ordering* and the
 * *reporting* are pure logic and those are the parts that go wrong.
 *
 * The rule worth protecting: a library that fails to load is **reported**, not
 * swallowed. Silently continuing without the graphics probe produces a
 * diagnostics report that claims the device was measured when it was not.
 */
class NativeLibraryLoaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private class RecordingLoader {
        val calls = mutableListOf<String>()
        val broken = mutableSetOf<String>()

        fun load(path: String) {
            calls += path
            if (File(path).name in broken) {
                throw UnsatisfiedLinkError("dlopen failed: library not found")
            }
        }
    }

    private fun library(name: String, exists: Boolean = true): File =
        File(temporary.root, name).also { if (exists) it.writeText("so") }

    @Test
    fun `libraries load in the order given`() {
        // Order matters on Android: dlopen does not resolve a dependency that
        // has not been loaded yet.
        val recorder = RecordingLoader()
        val loader = NativeLibraryLoader(recorder::load)

        loader.loadAll(listOf(library("first.so"), library("second.so")))

        assertEquals(listOf("first.so", "second.so"), recorder.calls.map { File(it).name })
    }

    @Test
    fun `a missing library is skipped and named`() {
        val loader = NativeLibraryLoader { }
        val missing = File(temporary.root, "absent.so")

        val outcomes = loader.loadAll(listOf(missing))

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.first() is NativeLibraryLoader.Outcome.Skipped)
        assertTrue(loader.problems().first().contains("absent.so"))
        assertTrue(loader.loaded().isEmpty())
    }

    @Test
    fun `a library the linker refuses is a failure, not a success`() {
        val recorder = RecordingLoader().apply { broken += "broken.so" }
        val loader = NativeLibraryLoader(recorder::load)

        loader.loadAll(listOf(library("broken.so")))

        assertEquals(1, loader.problems().size)
        assertTrue(loader.problems().first().contains("dlopen failed"))
        assertTrue(loader.loaded().isEmpty())
    }

    @Test
    fun `one failure does not stop the rest from loading`() {
        // Knowing that two of three loaded is more useful than knowing that
        // one of them did not.
        val recorder = RecordingLoader().apply { broken += "middle.so" }
        val loader = NativeLibraryLoader(recorder::load)

        loader.loadAll(listOf(library("a.so"), library("middle.so"), library("c.so")))

        assertEquals(listOf("a.so", "c.so"), loader.loaded())
        assertEquals(1, loader.problems().size)
    }

    @Test
    fun `the shipped list is what the launcher needs, in dependency order`() {
        val loader = NativeLibraryLoader { }

        val shipped = NativeLibraryLoader.shipped(File("/data/app/lib/arm64"))

        assertEquals(NativeLibraryLoader.SHIPPED.size, shipped.size)
        shipped.forEach { assertTrue(it.path.startsWith("/data/app/lib/arm64")) }
        assertTrue(NativeLibraryLoader.SHIPPED.all { it.startsWith("lib") && it.endsWith(".so") })
    }
}
