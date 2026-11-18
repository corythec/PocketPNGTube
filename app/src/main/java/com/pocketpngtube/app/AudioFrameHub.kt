
package com.pocketpngtube.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shares microphone audio samples between services.
 *
 * PocketService remains the only owner of AudioRecord.
 * Other components can subscribe to receive copies
 * of the microphone samples.
 */
object AudioFrameHub {

    fun interface AudioFrameListener {
        fun onAudioFrame(
            samples: ShortArray,
            timestampNanos: Long
        )
    }

    private val listeners =
        CopyOnWriteArrayList<AudioFrameListener>()

    /**
     * Subscribe to microphone audio.
     */
    fun addListener(listener: AudioFrameListener) {
        listeners.addIfAbsent(listener)
    }

    /**
     * Stop receiving microphone audio.
     */
    fun removeListener(listener: AudioFrameListener) {
        listeners.remove(listener)
    }

    /**
     * Publish a copy of the samples.
     *
     * The original AudioRecord buffer is reused by
     * PocketService, so listeners must receive their
     * own copy.
     */
    fun publish(
        samples: ShortArray,
        count: Int,
        timestampNanos: Long
    ) {

        if (count <= 0 || listeners.isEmpty()) {
            return
        }

        val safeCount = count.coerceAtMost(samples.size)
        val copy = samples.copyOf(safeCount)

        for (listener in listeners) {
            try {
                listener.onAudioFrame(
                    copy,
                    timestampNanos
                )
            } catch (exception: Exception) {
                android.util.Log.e(
                    "AudioFrameHub",
                    "Audio listener failed",
                    exception
                )
            }
        }
    }
}
