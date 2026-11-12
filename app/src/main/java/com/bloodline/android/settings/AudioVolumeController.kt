package com.bloodline.android.settings

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService

/**
 * Controls the Android music stream volume.
 *
 * This is a real effect: it is the volume the game will play at. Per-channel
 * controls (music / effects / dialogue) are *not* offered, because those are
 * separate channels inside the game's own mixer and only the runtime can reach
 * them — offering them would be a decorative slider.
 */
class AudioVolumeController(context: Context) {

    private val audioManager: AudioManager? = context.getSystemService()

    private val maxVolume: Int
        get() = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

    /** Current music-stream volume as a percentage, or -1 when unavailable. */
    fun currentPercent(): Int {
        val manager = audioManager ?: return -1
        if (maxVolume <= 0) return -1
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / maxVolume
    }

    /** Sets the music-stream volume. Returns false when the device refused. */
    fun setPercent(percent: Int): Boolean {
        val manager = audioManager ?: return false
        if (maxVolume <= 0) return false

        val target = ((percent.coerceIn(0, 100) * maxVolume) / 100).coerceIn(0, maxVolume)
        return runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }.isSuccess
    }
}
