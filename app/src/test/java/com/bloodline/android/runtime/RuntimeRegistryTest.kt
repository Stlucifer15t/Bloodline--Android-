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
        // Every other component is unaffected by the measurement. The container
        // is the exception by design: it reports READY because it is not needed.
        assertFalse(
            components.filter {
                it.id != ComponentIds.NATIVE_HOSTING && it.id != ComponentIds.CONTAINER
            }.any { it.status == IntegrationStatus.READY },
        )
    }

    @Test
    fun `the container reports ready, because there is no container`() {
        // The Bionic builds run Wine against Android's own libc. There is no
        // glibc userland and no PRoot, so there is nothing to be missing —
        // and reporting it as NOT_INTEGRATED would send the wrong message.
        val container = components.first { it.id == ComponentIds.CONTAINER }

        assertEquals(IntegrationStatus.READY, container.status)
        assertTrue(container.detail.contains("Bionic"))
        assertFalse(container.detail.contains("PRoot is required"))
    }

    @Test
    fun `overall status is ready only when every component is`() {
        assertEquals(
            IntegrationStatus.PARTIAL,
            RuntimeRegistry.overallStatus(nativeHosting = IntegrationStatus.READY),
        )
    }

    @Test
    fun `nothing reports ready before the runtime has been acquired`() {
        // Honesty guard. The container is excluded because there is genuinely
        // no container in this architecture; everything else has to be
        // installed before it may claim anything.
        assertFalse(
            "A component reported READY even though nothing is installed yet",
            components.filter { it.id != ComponentIds.CONTAINER }
                .any { it.status == IntegrationStatus.READY },
        )
    }

    @Test
    fun `a provider with nothing installed changes nothing`() {
        val withProvider = RuntimeRegistry.forProvider(
            provider = StaticRuntimeProvider(installed = emptySet()),
            capabilities = RuntimeCapabilities(measured = true),
            nativeHosting = IntegrationStatus.READY,
        )

        assertEquals(
            IntegrationStatus.NOT_INTEGRATED,
            withProvider.first { it.id == ComponentIds.WINE }.status,
        )
    }

    @Test
    fun `installed and usable packages report ready`() {
        val withProvider = RuntimeRegistry.forProvider(
            provider = StaticRuntimeProvider(installed = setOf("wine-11.3-arm64ec", "wowbox64")),
            capabilities = RuntimeCapabilities(measured = true),
            nativeHosting = IntegrationStatus.READY,
        )

        assertEquals(
            IntegrationStatus.READY,
            withProvider.first { it.id == ComponentIds.WINE }.status,
        )
        assertEquals(
            IntegrationStatus.READY,
            withProvider.first { it.id == ComponentIds.CPU_TRANSLATION }.status,
        )
        assertEquals(
            IntegrationStatus.READY,
            withProvider.first { it.id == ComponentIds.GRAPHICS }.status,
        )
    }

    @Test
    fun `installed but broken files report partial, not ready and not missing`() {
        // A user can act on all three of these; collapsing PARTIAL into either
        // of the others throws that information away.
        val withProvider = RuntimeRegistry.forProvider(
            provider = StaticRuntimeProvider(
                installed = setOf("wine-11.3-arm64ec", "wowbox64"),
                missingFiles = listOf("The WoW64 layer was not found."),
            ),
            capabilities = RuntimeCapabilities(measured = true),
            nativeHosting = IntegrationStatus.READY,
        )

        assertEquals(
            IntegrationStatus.PARTIAL,
            withProvider.first { it.id == ComponentIds.WINE }.status,
        )
        assertTrue(withProvider.first { it.id == ComponentIds.WINE }.detail.contains("could not be found"))
    }

    @Test
    fun `graphics is only ready once the device has been measured`() {
        // Choosing a backend for a device we know nothing about is a guess, so
        // an unmeasured device does not get a READY from the graphics row.
        val unmeasured = RuntimeRegistry.forProvider(
            provider = StaticRuntimeProvider(installed = setOf("wine-11.3-arm64ec", "wowbox64")),
            capabilities = RuntimeCapabilities(),
            nativeHosting = IntegrationStatus.READY,
        )

        assertEquals(
            IntegrationStatus.PARTIAL,
            unmeasured.first { it.id == ComponentIds.GRAPHICS }.status,
        )
    }

    @Test
    fun `no provider means nothing is installed`() {
        val without = RuntimeRegistry.forProvider(
            provider = null,
            capabilities = RuntimeCapabilities(measured = true),
            nativeHosting = IntegrationStatus.READY,
        )

        assertEquals(
            IntegrationStatus.NOT_INTEGRATED,
            without.first { it.id == ComponentIds.WINE }.status,
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
