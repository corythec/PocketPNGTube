package com.pocketpngtube.app

import java.io.File
import java.io.RandomAccessFile

/**
 * Saves raw 48 kHz stereo PCM as a WAV file.
 * Stops accepting samples after the configured duration.
 */
class RawPcmWavWriter(
    private val file: File,
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    private val maximumSeconds: Int = 10
) {
    private val maximumBytes =
        sampleRate.toLong() *
            channels *
            2L *
            maximumSeconds

    private lateinit var output: RandomAccessFile

    private var dataBytes = 0L
    private var closed = false

    init {
        val directory = file.parentFile

        if (
            directory != null &&
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create WAV diagnostic directory."
            )
        }

        output = RandomAccessFile(file, "rw")
        output.setLength(0)

        writeHeader()
    }

    @Synchronized
    fun write(samples: ShortArray, count: Int) {
        if (closed || dataBytes >= maximumBytes) return

        val safeCount = minOf(
            count.coerceAtMost(samples.size),
            ((maximumBytes - dataBytes) / 2).toInt()
        )

        if (safeCount <= 0) return

        val bytes = ByteArray(safeCount * 2)

        for (i in 0 until safeCount) {
            val value = samples[i].toInt()

            bytes[i * 2] =
                (value and 0xFF).toByte()

            bytes[i * 2 + 1] =
                ((value shr 8) and 0xFF).toByte()
        }

        output.write(bytes)
        dataBytes += bytes.size
    }

    @Synchronized
    fun close() {
        if (closed) return

        closed = true

        // Update RIFF chunk size.
        output.seek(4)
        writeIntLE((36L + dataBytes).toInt())

        // Update data chunk size.
        output.seek(40)
        writeIntLE(dataBytes.toInt())

        output.close()
    }

    private fun writeHeader() {
        output.writeBytes("RIFF")
        writeIntLE(0)
        output.writeBytes("WAVE")

        output.writeBytes("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * channels * 2)
        writeShortLE(channels * 2)
        writeShortLE(16)

        output.writeBytes("data")
        writeIntLE(0)
    }

    private fun writeIntLE(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }
}