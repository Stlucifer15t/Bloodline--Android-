package com.bloodline.android.port

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android half of the port integration.
 *
 * Everything that needs a `Context` lives here and nowhere else, so the classes
 * that make decisions — settings parsing, presets, the GPU gate — stay plain
 * Kotlin and stay unit testable on the JVM. That split is the same one the
 * runtime package uses, and it exists for the same reason: the only tests
 * available for code that talks to a GPU are the ones that do not need one.
 */
object PortProviders {

    /** The GL renderer, probed once per process and remembered. */
    @Volatile
    private var cachedRenderer: String? = null

    @Volatile
    private var rendererProbed = false

    /** Where downloaded driver packages are kept, under the app's own storage. */
    fun driverCache(context: Context): File =
        File(context.filesDir, "drivers").apply { mkdirs() }

    /**
     * Reads the device facts a driver decision needs.
     *
     * [glRenderer] is passed in rather than probed here: creating an EGL
     * context is slow enough to freeze a screen, so the caller asks
     * [probeRenderer] once and holds the answer. Null is honest — it means the
     * caller has not probed yet, not that the device has no GPU.
     */
    fun gpuIdentity(context: Context, glRenderer: String? = cachedRenderer): GpuIdentity =
        DeviceProfile.read(
            vendor = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            vulkanPacked = vulkanVersionCode(context),
            glRenderer = glRenderer,
        )

    /**
     * The Vulkan version the platform advertises, or null.
     *
     * `PackageManager` exposes it only through the feature list: there is no
     * direct getter. The version is `FeatureInfo.version` on the
     * `android.hardware.vulkan.version` feature, packed as
     * `major << 22 | minor << 12 | patch`.
     */
    private fun vulkanVersionCode(context: Context): Int? = runCatching {
        context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.version
            ?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * Downloads [packageToFetch] over HTTPS.
     *
     * A plain `HttpURLConnection` on purpose: this is one GET of a file with a
     * known length and digest, and the dependency that would be added to do it
     * "properly" would be larger than the download. Redirects are followed
     * because GitHub's release assets redirect to a signed URL, which is the
     * normal behaviour and not an error.
     */
    fun openDownload(url: String): InputStream {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Bloodlines-Android")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw java.io.IOException("HTTP $code from the driver host")
        }
        return connection.inputStream
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000


    /**
     * Probes `GL_RENDERER` on a background thread and hands the result back on
     * the calling thread's executor.
     *
     * Separate from [gpuIdentity] because creating an EGL context takes long
     * enough to be visible as a freeze, and the settings screen must not do it
     * while it is drawing. The result is cached: the GPU does not change.
     */
    fun probeRenderer(onResult: (String?) -> Unit) {
        if (rendererProbed) {
            onResult(cachedRenderer)
            return
        }
        Thread({
            val renderer = runCatching {
                com.bloodline.android.diagnostics.GpuInfo.probe().renderer
            }.getOrNull()
            cachedRenderer = renderer
            rendererProbed = true
            onResult(renderer)
        }, "bloodline-gpu-probe").apply { isDaemon = true }.start()
    }
}
