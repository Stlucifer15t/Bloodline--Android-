package com.bloodline.android.settings

import android.view.Choreographer

/**
 * Measures the real on-screen frame rate.
 *
 * It counts actual Choreographer frame callbacks, so the number it reports is
 * the refresh rate Android is really delivering to this surface — not a setting
 * being echoed back. Today that is the launcher's surface; once the game surface
 * exists it will measure that instead.
 *
 * Must be started and stopped on the main thread.
 */
class FpsMeter(
    private val sampleWindowMillis: Long = 1000L,
    private val onSample: (framesPerSecond: Float) -> Unit,
) {

    private val choreographer = Choreographer.getInstance()
    private val callback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    private var running = false
    private var frames = 0
    private var windowStartNanos = 0L

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        frames = 0
        windowStartNanos = System.nanoTime()
        choreographer.postFrameCallback(callback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(callback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return

        frames++
        val elapsedMillis = (frameTimeNanos - windowStartNanos) / 1_000_000L
        if (elapsedMillis >= sampleWindowMillis) {
            onSample(frames * 1000f / elapsedMillis)
            frames = 0
            windowStartNanos = frameTimeNanos
        }
        choreographer.postFrameCallback(callback)
    }
}
