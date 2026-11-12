package com.bloodline.android.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.getSystemService
import java.io.File
import java.util.Locale

/**
 * Facts about the device, for a diagnostics report.
 *
 * Deliberately limited to what identifies hardware and software versions.
 * Nothing here identifies the person using the device.
 */
object DeviceInfo {

    fun collect(context: Context): Map<String, String> {
        val facts = linkedMapOf<String, String>()

        facts["Manufacturer"] = Build.MANUFACTURER
        facts["Model"] = Build.MODEL
        facts["Device"] = Build.DEVICE
        facts["Board"] = Build.BOARD
        facts["Hardware"] = Build.HARDWARE
        facts["Product"] = Build.PRODUCT
        facts["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        facts["Build"] = Build.DISPLAY
        facts["Supported ABIs"] = Build.SUPPORTED_ABIS.joinToString(", ")
        facts["Locale"] = Locale.getDefault().toLanguageTag()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService<WindowManager>()?.defaultDisplay?.getMetrics(metrics)
        if (metrics.widthPixels > 0) {
            facts["Screen"] = "${metrics.widthPixels} x ${metrics.heightPixels} @ " +
                "${metrics.densityDpi}dpi (${metrics.density}x)"
        }

        context.getSystemService<ActivityManager>()?.let { manager ->
            val memory = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memory)
            facts["RAM"] = "${formatMegabytes(memory.totalMem)} total, " +
                "${formatMegabytes(memory.availMem)} available"
        }

        facts["Storage free"] = formatMegabytes(context.filesDir.usableSpace)

        GpuInfo.probe()?.let { gpu ->
            facts["GPU vendor"] = gpu.vendor
            facts["GPU renderer"] = gpu.renderer
            facts["OpenGL ES"] = gpu.glVersion
            facts["GLSL"] = gpu.shadingLanguageVersion
            facts["EGL"] = gpu.eglVersion
        } ?: run {
            facts["GPU renderer"] = "not reported by the device"
        }

        return facts
    }

    private fun formatMegabytes(bytes: Long): String {
        if (bytes <= 0) return "unknown"
        return "${bytes / (1024 * 1024)} MB"
    }

    /** Free space where reports are written, so a full disk is visible. */
    fun freeSpace(directory: File): Long = directory.usableSpace
}
