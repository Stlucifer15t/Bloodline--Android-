package com.bloodline.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRegistryTest {

    private val components = RuntimeRegistry.components()

    @Test
    fun `component ids are unique`() {
        val ids = components.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `registry describes every planned part of the stack`() {
        val ids = components.map { it.id }.toSet()

        assertTrue(
            ids.containsAll(
                listOf(
                    ComponentIds.NATIVE_HOSTING,
                    ComponentIds.WINE,
                    ComponentIds.CPU_TRANSLATION,
                    ComponentIds.CONTAINER,
                    ComponentIds.GRAPHICS,
                    ComponentIds.AUDIO,
                    ComponentIds.STORAGE,
                )
            )
        )
    }

    @Test
    fun `native hosting reports the status measured on the device`() {
        val components = RuntimeRegistry.components(nativeHosting = IntegrationStatus.READY)
        val native = components.first { it.id == ComponentIds.NATIVE_HOSTING }

        assertEquals(IntegrationStatus.READY, native.status)
        // Every other component is unaffected by the measurement.
        assertFalse(
            components.filter { it.id != ComponentIds.NATIVE_HOSTING }
                .any { it.status == IntegrationStatus.READY },
        )
    }

    @Test
    fun `overall status is ready only when every component is`() {
        assertEquals(
            IntegrationStatus.PARTIAL,
            RuntimeRegistry.overallStatus(nativeHosting = IntegrationStatus.READY),
        )
    }

    @Test
    fun `no component claims to be ready before it ships`() {
        // Honesty guard: flip this assertion only in the milestone that
        // actually integrates the component in question.
        assertFalse(
            "A component reported READY even though nothing is integrated yet",
            components.any { it.status == IntegrationStatus.READY },
        )
    }

    @Test
    fun `overall status is partial while only storage works`() {
        assertEquals(IntegrationStatus.PARTIAL, RuntimeRegistry.overallStatus())
    }

    @Test
    fun `every component explains itself`() {
        assertTrue(components.all { it.title.isNotBlank() && it.detail.isNotBlank() })
    }
}
