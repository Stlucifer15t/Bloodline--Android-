package com.bloodline.android.diagnostics

import android.opengl.EGL14
import android.opengl.GLES20

/**
 * Asks the device which GPU it is actually using.
 *
 * Android exposes no API for this, so it is read from an EGL context created on
 * a background thread. Everything is best effort: a device that refuses the
 * context simply reports nothing rather than breaking the report.
 */
object GpuInfo {

    data class Info(
        val vendor: String,
        val renderer: String,
        val glVersion: String,
        val shadingLanguageVersion: String,
        val eglVersion: String,
    )

    /** Must run off the main thread: an EGL context belongs to its thread. */
    fun probe(): Info? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0)) {
            return null
        }
        val config = configs[0] ?: return null

        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return null

        val surfaceAttributes = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttributes, 0)

        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            return Info(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
                renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
                shadingLanguageVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
                    .orEmpty(),
                eglVersion = EGL14.eglQueryString(display, EGL14.EGL_VERSION).orEmpty(),
            )
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }.getOrNull()
}
