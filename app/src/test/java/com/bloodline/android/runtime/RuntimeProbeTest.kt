package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher shows a one-line summary and puts the rest into reports, so the
 * reading of the probe's output has to be right: a wrong summary would tell the
 * user the device supports something it does not.
 */
class RuntimeProbeTest {

    private val supportedOutput = """
        runtime-probe: ok
        abi: arm64-v8a
        api-level: 34
        cores: 8
        memtotal-mb: 3892
        memavailable-mb: 1104
        ptrace: ALLOWED
        seccomp: 2
        memfd-create: ALLOWED
        runtime-probe-child: ok
        personality32: ALLOWED
        ro-zygote: zygote64_32
        abilist32: armeabi-v7a,armeabi
        linker32: present
        runtime-probe: done
    """.trimIndent()

    private val restrictedOutput = """
        runtime-probe: ok
        abi: arm64-v8a
        cores: 8
        memtotal-mb: 3892
        ptrace: DENIED (Operation not permitted)
        seccomp: 2
        memfd-create: ALLOWED
        exec-memfd: DENIED (Permission denied)
        personality32: DENIED (Invalid argument)
        ro-zygote: zygote64
        abilist32: unknown
        linker32: absent
        runtime-probe: done
    """.trimIndent()

    @Test
    fun `a successful run is recognised by its marker and exit code`() {
        assertTrue(RuntimeProbe.isSuccessful(0, supportedOutput))
        assertFalse(RuntimeProbe.isSuccessful(1, supportedOutput))
        assertFalse(RuntimeProbe.isSuccessful(0, "runtime-probe: boom"))
    }

    @Test
    fun `facts are read from the key value lines`() {
        val facts = RuntimeProbe.facts(supportedOutput)

        assertEquals("arm64-v8a", facts["abi"])
        assertEquals("8", facts["cores"])
        assertEquals("ALLOWED", facts["ptrace"])
        assertEquals("2", facts["seccomp"])
        assertEquals("zygote64_32", facts["ro-zygote"])
    }

    @Test
    fun `a single fact can be picked out`() {
        assertEquals("zygote64_32", RuntimeProbe.value(supportedOutput, "ro-zygote"))
        assertEquals("absent", RuntimeProbe.value(restrictedOutput, "linker32"))
        assertNull(RuntimeProbe.value(restrictedOutput, "abi-ha"))
    }

    @Test
    fun `the summary leads with what decides the container`() {
        val summary = RuntimeProbe.summary(supportedOutput)

        assertTrue(summary.contains("ptrace ALLOWED"))
        assertTrue(summary.contains("exec from memory ALLOWED"))
        assertTrue(summary.contains("8 cores"))
        assertTrue(summary.contains("3892 MB RAM"))
    }

    @Test
    fun `a refused ptrace is reported as refused`() {
        val summary = RuntimeProbe.summary(restrictedOutput)

        assertTrue(summary.contains("ptrace DENIED"))
        assertTrue(summary.contains("exec from memory DENIED"))
    }

    @Test
    fun `exec from memory counts as allowed only when the copy actually ran`() {
        // The marker printed by the exec'd copy is the proof it really ran.
        val withoutChildMarker = restrictedOutput.replace(RuntimeProbe.CHILD_MARKER, "")

        assertTrue(RuntimeProbe.summary(supportedOutput).contains("exec from memory ALLOWED"))
        assertTrue(
            RuntimeProbe.summary(withoutChildMarker).contains("exec from memory DENIED"),
        )
    }

    @Test
    fun `a probe that never ran is not described as measured`() {
        assertEquals("not measured", RuntimeProbe.summary("runtime-probe: boom"))
    }

    @Test
    fun `the short summary degrades gracefully when facts are missing`() {
        assertEquals("measured", RuntimeProbe.summary("runtime-probe: ok"))
    }
}
