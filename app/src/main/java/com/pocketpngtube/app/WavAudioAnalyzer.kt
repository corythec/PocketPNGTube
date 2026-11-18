
package com.pocketpngtube.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Locally analyzes PocketPNGTube diagnostic WAV files.
 *
 * Supports PCM 16-bit WAV files.
 * Does not upload or share audio.
 */
object WavAudioAnalyzer {

    private const val RAW_PREFIX =
        "PocketPNGTube_RawPlayback_"

    private const val MIXED_PREFIX =
        "PocketPNGTube_PostMixer_"

    private const val SILENCE_THRESHOLD = 8
    private const val JUMP_THRESHOLD_1 = 5000
    private const val JUMP_THRESHOLD_2 = 10000

    // This comparison is for the current controlled test:
    // playback gain = 40%, microphone gain = 0%.
    private const val EXPECTED_PLAYBACK_GAIN = 0.40

    private data class WavSummary(
        val fileName: String,
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
        val durationSeconds: Double,
        val peak: Int,
        val rms: Double,
        val activeRms: Double,
        val averageAbsolute: Double,
        val dcOffset: Double,
        val silentFrames: Long,
        val longestSilence: Long,
        val firstActiveFrame: Long,
        val lastActiveFrame: Long,
        val jumps5000: Long,
        val jumps10000: Long,
        val maximumJump: Int,
        val repeatedSamples: Long,
        val samples: ShortArray
    )

    private data class CorrelationResult(
        val lagFrames: Int,
        val correlation: Double,
        val comparedSamples: Long
    )

    /**
     * Finds and analyzes the newest raw and post-mixer WAV files.
     */
    fun analyzeLatest(musicDirectory: File?): String {

        if (musicDirectory == null) {
            return "WAV ANALYSIS\nMusic directory unavailable."
        }

        val diagnosticsDirectory = File(
            musicDirectory,
            "Diagnostics"
        )

        if (!diagnosticsDirectory.exists()) {
            return "WAV ANALYSIS\nDiagnostics directory not found."
        }

        val rawFile = findNewest(
            diagnosticsDirectory,
            RAW_PREFIX
        )

        val mixedFile = findNewest(
            diagnosticsDirectory,
            MIXED_PREFIX
        )

        if (rawFile == null && mixedFile == null) {
            return "WAV ANALYSIS\nNo diagnostic WAV files found."
        }

        val rawResult = rawFile?.let {
            analyzeFile(it)
        }

        val mixedResult = mixedFile?.let {
            analyzeFile(it)
        }

        return buildString {

            appendLine("================================")
            appendLine("LOCAL WAV AUDIO ANALYSIS")
            appendLine("================================")

            appendLine()

            appendLine(
                rawResult?.let {
                    formatSummary("RAW PLAYBACK", it)
                } ?: "RAW PLAYBACK: File not found or unsupported."
            )

            appendLine()

            appendLine(
                mixedResult?.let {
                    formatSummary("POST MIXER", it)
                } ?: "POST MIXER: File not found or unsupported."
            )

            appendLine()

            if (rawResult != null && mixedResult != null) {
                appendLine(
                    compareResults(rawResult, mixedResult)
                )
            }
        }
    }

    private fun findNewest(
        directory: File,
        prefix: String
    ): File? {

        return directory.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(prefix) &&
                    it.extension.equals("wav", true) &&
                    it.length() > 44
            }
            ?.maxByOrNull {
                it.lastModified()
            }
    }

    /**
     * Reads a PCM 16-bit WAV file and calculates audio statistics.
     */
    private fun analyzeFile(file: File): WavSummary? {

        return try {

            RandomAccessFile(file, "r").use { input ->

                if (input.length() < 44) {
                    return null
                }

                input.seek(0)

                if (readText(input, 4) != "RIFF") {
                    return null
                }

                input.skipBytes(4)

                if (readText(input, 4) != "WAVE") {
                    return null
                }

                var audioFormat = -1
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0

                var dataOffset = -1L
                var dataSize = -1L

                // Locate the WAV format and data chunks.
                while (input.filePointer + 8 <= input.length()) {

                    val chunkName = readText(input, 4)
                    val chunkSize = readUInt32(input)
                    val chunkStart = input.filePointer

                    if (
                        chunkSize < 0 ||
                        chunkSize > input.length() - chunkStart
                    ) {
                        break
                    }

                    when (chunkName) {

                        "fmt " -> {

                            if (chunkSize >= 16) {

                                audioFormat =
                                    readUInt16(input)

                                channels =
                                    readUInt16(input)

                                sampleRate =
                                    readUInt32(input).toInt()

                                input.skipBytes(6)

                                bitsPerSample =
                                    readUInt16(input)
                            }
                        }

                        "data" -> {

                            dataOffset = chunkStart
                            dataSize = chunkSize
                        }
                    }

                    input.seek(
                        chunkStart +
                            chunkSize +
                            (chunkSize % 2)
                    )

                    if (
                        audioFormat >= 0 &&
                        dataOffset >= 0
                    ) {
                        break
                    }
                }

                if (
                    audioFormat != 1 ||
                    bitsPerSample != 16 ||
                    channels !in 1..2 ||
                    sampleRate <= 0 ||
                    dataOffset < 0 ||
                    dataSize <= 0 ||
                    dataSize > Int.MAX_VALUE
                ) {
                    return null
                }

                val bytesPerFrame = channels * 2

                val frameCount =
                    dataSize / bytesPerFrame

                if (
                    frameCount <= 0 ||
                    frameCount * channels > Int.MAX_VALUE
                ) {
                    return null
                }

                val audioBytes =
                    ByteArray(dataSize.toInt())

                input.seek(dataOffset)
                input.readFully(audioBytes)

                val frameCountInt = frameCount.toInt()

                val samples =
                    ShortArray(
                        (frameCount * channels).toInt()
                    )

                // Convert little-endian PCM bytes into samples.
                for (index in samples.indices) {

                    val byteOffset = index * 2

                    samples[index] = readSample(
                        audioBytes,
                        byteOffset
                    ).toShort()
                }

                var peak = 0
                var sampleCount = 0L
                var sum = 0L
                var sumSquares = 0.0
                var absoluteSum = 0.0

                var activeSampleCount = 0L
                var activeSumSquares = 0.0

                var silentFrames = 0L
                var currentSilence = 0L
                var longestSilence = 0L

                var firstActiveFrame = -1L
                var lastActiveFrame = -1L

                var jumps5000 = 0L
                var jumps10000 = 0L
                var maximumJump = 0

                var repeatedSamples = 0L

                var previousLeft: Int? = null
                var previousRight: Int? = null

                for (frame in 0 until frameCountInt) {

                    val offset = frame * channels

                    val left = samples[offset].toInt()

                    val right =
                        if (channels == 2) {
                            samples[offset + 1].toInt()
                        } else {
                            left
                        }

                    val framePeak = maxOf(
                        abs(left),
                        abs(right)
                    )

                    peak = maxOf(peak, framePeak)

                    val frameIsSilent =
                        framePeak <= SILENCE_THRESHOLD

                    if (frameIsSilent) {

                        silentFrames++
                        currentSilence++

                        longestSilence =
                            maxOf(
                                longestSilence,
                                currentSilence
                            )

                    } else {

                        currentSilence = 0

                        if (firstActiveFrame < 0) {
                            firstActiveFrame = frame.toLong()
                        }

                        lastActiveFrame = frame.toLong()
                    }

                    for (channel in 0 until channels) {

                        val value =
                            samples[offset + channel].toInt()

                        sampleCount++

                        sum += value
                        sumSquares += value.toDouble() * value

                        absoluteSum += abs(value)

                        if (abs(value) > SILENCE_THRESHOLD) {

                            activeSampleCount++

                            activeSumSquares +=
                                value.toDouble() * value
                        }

                        val previous =
                            if (channel == 0) {
                                previousLeft
                            } else {
                                previousRight
                            }

                        if (previous != null) {

                            val difference =
                                abs(value - previous)

                            maximumJump =
                                maxOf(
                                    maximumJump,
                                    difference
                                )

                            if (difference > JUMP_THRESHOLD_1) {
                                jumps5000++
                            }

                            if (difference > JUMP_THRESHOLD_2) {
                                jumps10000++
                            }

                            if (difference == 0) {
                                repeatedSamples++
                            }
                        }

                        if (channel == 0) {
                            previousLeft = value
                        } else {
                            previousRight = value
                        }
                    }
                }

                val rms =
                    if (sampleCount > 0) {
                        sqrt(sumSquares / sampleCount)
                    } else {
                        0.0
                    }

                val activeRms =
                    if (activeSampleCount > 0) {
                        sqrt(
                            activeSumSquares /
                                activeSampleCount
                        )
                    } else {
                        0.0
                    }

                val averageAbsolute =
                    if (sampleCount > 0) {
                        absoluteSum / sampleCount
                    } else {
                        0.0
                    }

                val dcOffset =
                    if (sampleCount > 0) {
                        sum.toDouble() / sampleCount
                    } else {
                        0.0
                    }

                WavSummary(
                    fileName = file.name,
                    sampleRate = sampleRate,
                    channels = channels,
                    frames = frameCount,
                    durationSeconds =
                        frameCount.toDouble() / sampleRate,
                    peak = peak,
                    rms = rms,
                    activeRms = activeRms,
                    averageAbsolute = averageAbsolute,
                    dcOffset = dcOffset,
                    silentFrames = silentFrames,
                    longestSilence = longestSilence,
                    firstActiveFrame = firstActiveFrame,
                    lastActiveFrame = lastActiveFrame,
                    jumps5000 = jumps5000,
                    jumps10000 = jumps10000,
                    maximumJump = maximumJump,
                    repeatedSamples = repeatedSamples,
                    samples = samples
                )
            }

        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the time offset that produces the strongest correlation
     * between the raw and post-mixer waveforms.
     *
     * Positive lag means the post-mixer waveform is delayed.
     */
    private fun findBestAlignment(
        raw: WavSummary,
        mixed: WavSummary
    ): CorrelationResult {

        val maxLag =
            raw.sampleRate

        val coarseStep =
            maxOf(1, raw.sampleRate / 200)

        val coarseStride =
            maxOf(1, raw.sampleRate / 1000)

        var best = CorrelationResult(
            lagFrames = 0,
            correlation = -2.0,
            comparedSamples = 0
        )

        // First search in 5 ms steps.
        for (lag in -maxLag..maxLag step coarseStep) {

            val result = calculateCorrelation(
                raw,
                mixed,
                lag,
                coarseStride
            )

            if (result.correlation > best.correlation) {
                best = result
            }
        }

        // Refine the search around the best coarse result.
        val fineStart =
            maxOf(-maxLag, best.lagFrames - coarseStep)

        val fineEnd =
            minOf(maxLag, best.lagFrames + coarseStep)

        val fineStep = 4

        val fineStride =
            maxOf(1, raw.sampleRate / 4000)

        for (lag in fineStart..fineEnd step fineStep) {

            val result = calculateCorrelation(
                raw,
                mixed,
                lag,
                fineStride
            )

            if (result.correlation > best.correlation) {
                best = result
            }
        }

        return best
    }

    /**
     * Calculates Pearson correlation for a proposed frame offset.
     */
    private fun calculateCorrelation(
        raw: WavSummary,
        mixed: WavSummary,
        lag: Int,
        stride: Int
    ): CorrelationResult {

        if (
            raw.sampleRate != mixed.sampleRate ||
            raw.channels != mixed.channels
        ) {
            return CorrelationResult(
                lag,
                -2.0,
                0
            )
        }

        val rawFrames = raw.frames.toInt()
        val mixedFrames = mixed.frames.toInt()

        val startRaw =
            maxOf(0, -lag)

        val endRaw =
            minOf(rawFrames, mixedFrames - lag)

        if (endRaw <= startRaw) {
            return CorrelationResult(
                lag,
                -2.0,
                0
            )
        }

        var count = 0L

        var sumX = 0.0
        var sumY = 0.0

        var sumXX = 0.0
        var sumYY = 0.0

        var sumXY = 0.0

        for (
            rawFrame in startRaw until endRaw step stride
        ) {

            val mixedFrame = rawFrame + lag

            val rawOffset = rawFrame * raw.channels
            val mixedOffset = mixedFrame * mixed.channels

            for (channel in 0 until raw.channels) {

                val x =
                    raw.samples[rawOffset + channel].toInt()

                val y =
                    mixed.samples[mixedOffset + channel].toInt()

                // Ignore pairs that are both effectively silent.
                if (
                    abs(x) <= 32 &&
                    abs(y) <= 32
                ) {
                    continue
                }

                val xd = x.toDouble()
                val yd = y.toDouble()

                count++

                sumX += xd
                sumY += yd

                sumXX += xd * xd
                sumYY += yd * yd

                sumXY += xd * yd
            }
        }

        if (count < 100) {
            return CorrelationResult(
                lag,
                -2.0,
                count
            )
        }

        val covariance =
            sumXY - (sumX * sumY / count)

        val varianceX =
            sumXX - (sumX * sumX / count)

        val varianceY =
            sumYY - (sumY * sumY / count)

        if (
            varianceX <= 0.0 ||
            varianceY <= 0.0
        ) {
            return CorrelationResult(
                lag,
                -2.0,
                count
            )
        }

        val correlation =
            covariance /
                sqrt(varianceX * varianceY)

        return CorrelationResult(
            lagFrames = lag,
            correlation = correlation,
            comparedSamples = count
        )
    }

    /**
     * Measures how closely the aligned mixer samples match the raw
     * samples after accounting for gain.
     */
    private fun compareAlignedSamples(
        raw: WavSummary,
        mixed: WavSummary,
        lag: Int
    ): String {

        val rawFrames = raw.frames.toInt()
        val mixedFrames = mixed.frames.toInt()

        val startRaw =
            maxOf(0, -lag)

        val endRaw =
            minOf(rawFrames, mixedFrames - lag)

        if (endRaw <= startRaw) {
            return "No overlapping audio available for sample comparison."
        }

        val stride = 4

        var sumRawSquared = 0.0
        var sumRawTimesMixed = 0.0
        var comparedSamples = 0L

        // First pass: calculate the best-fit gain.
        for (
            rawFrame in startRaw until endRaw step stride
        ) {

            val mixedFrame = rawFrame + lag

            val rawOffset = rawFrame * raw.channels
            val mixedOffset = mixedFrame * mixed.channels

            for (channel in 0 until raw.channels) {

                val x =
                    raw.samples[rawOffset + channel].toInt()

                val y =
                    mixed.samples[mixedOffset + channel].toInt()

                if (
                    abs(x) <= 64 &&
                    abs(y) <= 64
                ) {
                    continue
                }

                val xd = x.toDouble()
                val yd = y.toDouble()

                sumRawSquared += xd * xd
                sumRawTimesMixed += xd * yd

                comparedSamples++
            }
        }

        if (
            comparedSamples == 0L ||
            sumRawSquared <= 0.0
            ) {
            return "Not enough active samples for comparison."
        }

        val fittedGain =
            sumRawTimesMixed / sumRawSquared

        // Second pass: calculate residual errors.
        var fittedErrorSquares = 0.0
        var expectedErrorSquares = 0.0
        var absoluteFittedError = 0.0

        var expectedErrors100 = 0L
        var expectedErrors500 = 0L
        var expectedErrors1000 = 0L

        for (
            rawFrame in startRaw until endRaw step stride
        ) {

            val mixedFrame = rawFrame + lag

            val rawOffset = rawFrame * raw.channels
            val mixedOffset = mixedFrame * mixed.channels

            for (channel in 0 until raw.channels) {

                val x =
                    raw.samples[rawOffset + channel].toInt()

                val y =
                    mixed.samples[mixedOffset + channel].toInt()

                if (
                    abs(x) <= 64 &&
                    abs(y) <= 64
                ) {
                    continue
                }

                val expected =
                    x * EXPECTED_PLAYBACK_GAIN

                val fitted =
                    x * fittedGain

                val expectedError =
                    abs(y - expected)

                val fittedError =
                    y - fitted

                expectedErrorSquares +=
                    expectedError * expectedError

                fittedErrorSquares +=
                    fittedError * fittedError

                absoluteFittedError +=
                    abs(fittedError)

                if (expectedError > 100) {
                    expectedErrors100++
                }

                if (expectedError > 500) {
                    expectedErrors500++
                }

                if (expectedError > 1000) {
                    expectedErrors1000++
                }
            }
        }

        val fittedRmsError =
            sqrt(fittedErrorSquares / comparedSamples)

        val expectedRmsError =
            sqrt(expectedErrorSquares / comparedSamples)

        val fittedMeanAbsoluteError =
            absoluteFittedError / comparedSamples

        return buildString {

            appendLine("Compared active samples: $comparedSamples")

            appendLine(
                "Expected gain: ${formatNumber(EXPECTED_PLAYBACK_GAIN)}"
            )

            appendLine(
                "Measured best-fit gain: ${formatNumber(fittedGain)}"
            )

            appendLine(
                "Best-fit gain RMS error: ${formatNumber(fittedRmsError)}"
            )

            appendLine(
                "Best-fit mean absolute error: ${formatNumber(fittedMeanAbsoluteError)}"
            )

            appendLine(
                "RMS error at expected 40% gain: ${formatNumber(expectedRmsError)}"
            )

            appendLine(
                "Samples differing from expected output by more than 100: $expectedErrors100"
            )

            appendLine(
                "Samples differing from expected output by more than 500: $expectedErrors500"
            )

            appendLine(
                "Samples differing from expected output by more than 1000: $expectedErrors1000"
            )
        }
    }

    private fun compareResults(
        raw: WavSummary,
        mixed: WavSummary
    ): String {

        val rmsRatio =
            if (raw.activeRms > 0) {
                mixed.activeRms / raw.activeRms
            } else {
                0.0
            }

        return buildString {

            appendLine("================================")
            appendLine("RAW VS POST-MIXER COMPARISON")
            appendLine("================================")

            appendLine(
                "Raw active RMS: ${formatNumber(raw.activeRms)}"
            )

            appendLine(
                "Mixed active RMS: ${formatNumber(mixed.activeRms)}"
            )

            appendLine(
                "Active RMS ratio: ${formatNumber(rmsRatio)}"
            )

            appendLine(
                "Raw peak: ${raw.peak}"
            )

            appendLine(
                "Mixed peak: ${mixed.peak}"
            )

            appendLine(
                "Raw jump count above 5000: ${raw.jumps5000}"
            )

            appendLine(
                "Mixed jump count above 5000: ${mixed.jumps5000}"
            )

            appendLine(
                "Raw jump count above 10000: ${raw.jumps10000}"
            )

            appendLine(
                "Mixed jump count above 10000: ${mixed.jumps10000}"
            )

            appendLine(
                "Raw longest silence: ${raw.longestSilence * 1000 / raw.sampleRate} ms"
            )

            appendLine(
                "Mixed longest silence: ${mixed.longestSilence * 1000 / mixed.sampleRate} ms"
            )

            appendLine()

            appendLine("================================")
            appendLine("WAVEFORM ALIGNMENT ANALYSIS")
            appendLine("================================")

            if (
                raw.sampleRate != mixed.sampleRate ||
                raw.channels != mixed.channels
            ) {

                appendLine(
                    "Cannot align: WAV formats do not match."
                )

            } else {

                val alignment = findBestAlignment(
                    raw,
                    mixed
                )

                val offsetMs =
                    alignment.lagFrames.toDouble() *
                        1000.0 /
                        raw.sampleRate

                appendLine(
                    "Estimated offset: ${formatNumber(offsetMs)} ms"
                )

                appendLine(
                    "Offset direction: ${
                        if (alignment.lagFrames > 0) {
                            "Post-mixer is delayed relative to raw"
                        } else if (alignment.lagFrames < 0) {
                            "Post-mixer leads raw"
                        } else {
                            "No measurable offset"
                        }
                    }"
                )

                appendLine(
                    "Best waveform correlation: ${formatNumber(alignment.correlation)}"
                )

                appendLine(
                    "Correlation samples examined: ${alignment.comparedSamples}"
                )

                appendLine()

                appendLine(
                    compareAlignedSamples(
                        raw,
                        mixed,
                        alignment.lagFrames
                    )
                )
            }

            appendLine()

            appendLine(
                "Important: Correlation and residual error are diagnostic measurements, not proof of audible quality. The comparison assumes the microphone is disabled and playback gain is 40%."
            )
        }
    }

    private fun readSample(
        bytes: ByteArray,
        offset: Int
    ): Int {

        val low = bytes[offset].toInt() and 0xFF

        val high =
            bytes[offset + 1].toInt() and 0xFF

        val value = low or (high shl 8)

        return if (value >= 32768) {
            value - 65536
        } else {
            value
        }
    }

    private fun readText(
        input: RandomAccessFile,
        count: Int
    ): String {

        val bytes = ByteArray(count)
        input.readFully(bytes)

        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt16(
        input: RandomAccessFile
    ): Int {

        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()

        return low or (high shl 8)
    }

    private fun readUInt32(
        input: RandomAccessFile
    ): Long {

        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()

        return b0 or
            (b1 shl 8) or
            (b2 shl 16) or
            (b3 shl 24)
    }

    private fun formatNumber(value: Double): String {
        return "%.3f".format(
            java.util.Locale.US,
            value
        )
    }

    private fun formatSummary(
        title: String,
        result: WavSummary
    ): String {

        return buildString {

            appendLine(title)
            appendLine("File: ${result.fileName}")

            appendLine(
                "Format: ${result.sampleRate} Hz, ${result.channels} channel(s)"
            )

            appendLine(
                "Duration: ${formatNumber(result.durationSeconds)} seconds"
            )

            appendLine("Frames: ${result.frames}")
            appendLine("Peak: ${result.peak} / 32767")

            appendLine(
                "RMS: ${formatNumber(result.rms)}"
            )

            appendLine(
                "Active RMS: ${formatNumber(result.activeRms)}"
            )

            appendLine(
                "Average absolute level: ${formatNumber(result.averageAbsolute)}"
            )

            appendLine(
                "DC offset: ${formatNumber(result.dcOffset)}"
            )

            appendLine("Silent frames: ${result.silentFrames}")

            appendLine(
                "Longest silence: ${result.longestSilence * 1000 / result.sampleRate} ms"
            )

            appendLine(
                "First active frame: ${result.firstActiveFrame}"
            )

            appendLine(
                "Last active frame: ${result.lastActiveFrame}"
            )

            appendLine(
                "Jumps above 5000: ${result.jumps5000}"
            )

            appendLine(
                "Jumps above 10000: ${result.jumps10000}"
            )

            appendLine(
                "Maximum sample jump: ${result.maximumJump}"
            )

            appendLine(
                "Repeated consecutive samples: ${result.repeatedSamples}"
            )
        }
    }
}
