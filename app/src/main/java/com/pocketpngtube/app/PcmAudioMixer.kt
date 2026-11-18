
package com.pocketpngtube.app

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * One mixed stereo audio frame ready for the AAC encoder.
 */
data class MixedAudioFrame(
    val samples: ShortArray,
    val startSampleIndex: Long
)

/**
 * Statistics for timing and lock contention.
 */
private class TimingStats {

    var calls = 0L

    var totalWaitNanos = 0L
    var maximumWaitNanos = 0L

    var totalWorkNanos = 0L
    var maximumWorkNanos = 0L

    var waitsOver2Ms = 0L
    var waitsOver5Ms = 0L
    var waitsOver10Ms = 0L

    var workOver2Ms = 0L
    var workOver5Ms = 0L
    var workOver10Ms = 0L

    fun record(
        waitNanos: Long,
        workNanos: Long
    ) {
        calls++

        totalWaitNanos += waitNanos
        maximumWaitNanos =
            maxOf(maximumWaitNanos, waitNanos)

        totalWorkNanos += workNanos
        maximumWorkNanos =
            maxOf(maximumWorkNanos, workNanos)

        if (waitNanos > 2_000_000L) waitsOver2Ms++
        if (waitNanos > 5_000_000L) waitsOver5Ms++
        if (waitNanos > 10_000_000L) waitsOver10Ms++

        if (workNanos > 2_000_000L) workOver2Ms++
        if (workNanos > 5_000_000L) workOver5Ms++
        if (workNanos > 10_000_000L) workOver10Ms++
    }

    fun reset() {
        calls = 0L

        totalWaitNanos = 0L
        maximumWaitNanos = 0L

        totalWorkNanos = 0L
        maximumWorkNanos = 0L

        waitsOver2Ms = 0L
        waitsOver5Ms = 0L
        waitsOver10Ms = 0L

        workOver2Ms = 0L
        workOver5Ms = 0L
        workOver10Ms = 0L
    }

    fun report(name: String): String {

        val averageWaitMs =
            if (calls > 0) {
                totalWaitNanos.toDouble() /
                    calls / 1_000_000.0
            } else {
                0.0
            }

        val averageWorkMs =
            if (calls > 0) {
                totalWorkNanos.toDouble() /
                    calls / 1_000_000.0
            } else {
                0.0
            }

        return """
            $name

            Calls: $calls

            Average lock wait: ${"%.3f".format(averageWaitMs)} ms
            Maximum lock wait: ${maximumWaitNanos / 1_000_000.0} ms

            Average operation time: ${"%.3f".format(averageWorkMs)} ms
            Maximum operation time: ${maximumWorkNanos / 1_000_000.0} ms

            Lock waits over 2 ms: $waitsOver2Ms
            Lock waits over 5 ms: $waitsOver5Ms
            Lock waits over 10 ms: $waitsOver10Ms

            Operations over 2 ms: $workOver2Ms
            Operations over 5 ms: $workOver5Ms
            Operations over 10 ms: $workOver10Ms
        """.trimIndent()
    }
}

/**
 * Timestamp-aligned 48 kHz stereo PCM mixer.
 *
 * Microphone input: 16 kHz mono.
 * Playback input: 48 kHz stereo.
 */
class PcmAudioMixer {

    companion object {
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val MIC_SAMPLE_RATE = 16000

        private const val AUDIO_FRAME_SIZE = 1024

        private const val MIX_BUFFER_SECONDS = 2
        private const val MIX_LATENCY_MS = 250

        private const val AUDIO_PEAK_CEILING = 23197

        private const val EMPTY_TAG = Long.MIN_VALUE
    }

    private val capacityFrames =
        AUDIO_SAMPLE_RATE * MIX_BUFFER_SECONDS

    private val leftSamples =
        IntArray(capacityFrames)

    private val rightSamples =
        IntArray(capacityFrames)

    private val sampleTags =
        LongArray(capacityFrames) {
            EMPTY_TAG
        }

    private val playbackFrameTags =
        LongArray(capacityFrames) {
            EMPTY_TAG
        }

    private val microphoneFrameTags =
        LongArray(capacityFrames) {
            EMPTY_TAG
        }

    private var clockOriginNanos = 0L
    private var nextSampleIndex = 0L
    private var highestSampleExclusive = 0L

    // ==========================================
    // OPERATION TIMING DIAGNOSTICS
    // ==========================================

    private var playbackTiming = TimingStats()
    private var microphoneTiming = TimingStats()
    private var encoderTiming = TimingStats()

    private inline fun <T> timedSynchronized(
        stats: TimingStats,
        block: () -> T
    ): T {

        val waitStart =
            SystemClock.elapsedRealtimeNanos()

        synchronized(this) {

            val acquiredAt =
                SystemClock.elapsedRealtimeNanos()

            val waitNanos =
                acquiredAt - waitStart

            try {

                return block()

            } finally {

                val finishedAt =
                    SystemClock.elapsedRealtimeNanos()

                val workNanos =
                    finishedAt - acquiredAt

                stats.record(
                    waitNanos,
                    workNanos
                )
            }
        }
    }

    // ==========================================
    // PLAYBACK DIAGNOSTICS
    // ==========================================

    private var playbackChunksReceived = 0L
    private var playbackFramesAccepted = 0L
    private var playbackFramesTooEarly = 0L
    private var playbackFramesTooFarAhead = 0L

    private var playbackDuplicateFrames = 0L

    private var lastPlaybackStartFrame = 0L
    private var lastPlaybackEndFrame = 0L

    // ==========================================
    // PLAYBACK TIMESTAMP CONTINUITY DIAGNOSTICS
    // ==========================================

    private var hasPreviousPlaybackChunk = false

    private var previousPlaybackStartFrame = 0L
    private var previousPlaybackFrameCount = 0L

    private var playbackOverlapChunks = 0L
    private var playbackGapChunks = 0L

    private var totalPlaybackOverlapFrames = 0L
    private var totalPlaybackGapFrames = 0L

    private var largestPlaybackOverlapFrames = 0L
    private var largestPlaybackGapFrames = 0L

    private var smallestPlaybackStartDelta = Long.MAX_VALUE
    private var largestPlaybackStartDelta = Long.MIN_VALUE

    private var lastPlaybackStartDelta = 0L

    // ==========================================
    // MICROPHONE DIAGNOSTICS
    // ==========================================

    private var microphoneDuplicateFrames = 0L

    // ==========================================
    // OUTPUT GAP DIAGNOSTICS
    // ==========================================

    private var outputFramesExamined = 0L
    private var outputFramesWithInput = 0L
    private var outputFramesWithoutInput = 0L

    private var currentMissingFrameRun = 0L
    private var longestMissingFrameRun = 0L

    private var hasSeenInputFrame = false
    private var pendingMissingFrames = 0L

    private var internalGapRuns = 0L
    private var internalMissingFrames = 0L
    private var longestInternalGap = 0L

    private var firstOutputFrameWithInput = -1L
    private var lastOutputFrameWithInput = -1L

    private var mixedPeakBeforeLimiter = 0

    // ==========================================
    // RESET
    // ==========================================

    @Synchronized
    fun reset(originNanos: Long) {

        leftSamples.fill(0)
        rightSamples.fill(0)

        sampleTags.fill(EMPTY_TAG)
        playbackFrameTags.fill(EMPTY_TAG)
        microphoneFrameTags.fill(EMPTY_TAG)

        clockOriginNanos = originNanos
        nextSampleIndex = 0L
        highestSampleExclusive = 0L

        playbackChunksReceived = 0L
        playbackFramesAccepted = 0L
        playbackFramesTooEarly = 0L
        playbackFramesTooFarAhead = 0L
        playbackDuplicateFrames = 0L

        lastPlaybackStartFrame = 0L
        lastPlaybackEndFrame = 0L

        hasPreviousPlaybackChunk = false

        previousPlaybackStartFrame = 0L
        previousPlaybackFrameCount = 0L

        playbackOverlapChunks = 0L
        playbackGapChunks = 0L

        totalPlaybackOverlapFrames = 0L
        totalPlaybackGapFrames = 0L

        largestPlaybackOverlapFrames = 0L
        largestPlaybackGapFrames = 0L

        smallestPlaybackStartDelta = Long.MAX_VALUE
        largestPlaybackStartDelta = Long.MIN_VALUE

        lastPlaybackStartDelta = 0L

        microphoneDuplicateFrames = 0L

        outputFramesExamined = 0L
        outputFramesWithInput = 0L
        outputFramesWithoutInput = 0L

        currentMissingFrameRun = 0L
        longestMissingFrameRun = 0L

        hasSeenInputFrame = false
        pendingMissingFrames = 0L

        internalGapRuns = 0L
        internalMissingFrames = 0L
        longestInternalGap = 0L

        firstOutputFrameWithInput = -1L
        lastOutputFrameWithInput = -1L

        mixedPeakBeforeLimiter = 0

        playbackTiming.reset()
        microphoneTiming.reset()
        encoderTiming.reset()
    }

    // ==========================================
    // PREPARE A RING BUFFER SLOT
    // ==========================================

    private fun prepareSlot(
        sampleIndex: Long
    ): Int {

        val slot =
            (sampleIndex % capacityFrames).toInt()

        if (sampleTags[slot] != sampleIndex) {

            sampleTags[slot] = sampleIndex

            leftSamples[slot] = 0
            rightSamples[slot] = 0

            playbackFrameTags[slot] = EMPTY_TAG
            microphoneFrameTags[slot] = EMPTY_TAG
        }

        return slot
    }

    // ==========================================
    // ADD MICROPHONE AUDIO
    // ==========================================

    fun addMicrophoneSamples(
        samples: ShortArray,
        timestampNanos: Long,
        gain: Float
    ): Int = timedSynchronized(microphoneTiming) {

        if (
            samples.isEmpty() ||
            clockOriginNanos == 0L
        ) {
            return@timedSynchronized samples.size
        }

        val durationNanos =
            samples.size.toLong() *
                1_000_000_000L /
                MIC_SAMPLE_RATE

        val chunkStartNanos =
            timestampNanos - durationNanos

        val relativeStart =
            chunkStartNanos - clockOriginNanos

        val startFrameIndex =
            relativeStart *
                AUDIO_SAMPLE_RATE /
                1_000_000_000L

        var dropped = 0

        for (i in samples.indices) {

            val scaledValue =
                (samples[i].toInt() * gain).roundToInt()

            val firstFrame =
                startFrameIndex + i * 3L

            for (repeat in 0 until 3) {

                val sampleIndex =
                    firstFrame + repeat

                if (sampleIndex < nextSampleIndex) {
                    dropped++
                    continue
                }

                if (
                    sampleIndex >=
                    nextSampleIndex + capacityFrames
                ) {
                    dropped++
                    continue
                }

                val slot =
                    prepareSlot(sampleIndex)

                if (
                    microphoneFrameTags[slot] == sampleIndex
                ) {
                    microphoneDuplicateFrames++
                    continue
                }

                microphoneFrameTags[slot] =
                    sampleIndex

                addFrame(
                    sampleIndex,
                    scaledValue,
                    scaledValue
                )
            }
        }

        dropped
    }

    // ==========================================
    // ADD PHONE PLAYBACK AUDIO
    // ==========================================

    fun addPlaybackSamples(
        samples: ShortArray,
        count: Int,
        timestampNanos: Long,
        gain: Float
    ): Int = timedSynchronized(playbackTiming) {

        if (
            count <= 1 ||
            clockOriginNanos == 0L
        ) {
            return@timedSynchronized count
        }

        val safeCount =
            count.coerceAtMost(samples.size)

        val frameCount =
            safeCount / 2

        if (frameCount <= 0) {
            return@timedSynchronized 0
        }

        val durationNanos =
            frameCount.toLong() *
                1_000_000_000L /
                AUDIO_SAMPLE_RATE

        val chunkStartNanos =
            timestampNanos - durationNanos

        val relativeStart =
            chunkStartNanos - clockOriginNanos

        val startFrameIndex =
            relativeStart *
                AUDIO_SAMPLE_RATE /
                1_000_000_000L

        // ======================================
        // TIMESTAMP CONTINUITY MEASUREMENT
        // ======================================

        if (hasPreviousPlaybackChunk) {

            val expectedNextStart =
                previousPlaybackStartFrame +
                    previousPlaybackFrameCount

            val startDelta =
                startFrameIndex - expectedNextStart

            lastPlaybackStartDelta = startDelta

            smallestPlaybackStartDelta =
                minOf(
                    smallestPlaybackStartDelta,
                    startDelta
                )

            largestPlaybackStartDelta =
                maxOf(
                    largestPlaybackStartDelta,
                    startDelta
                )

            if (startDelta < 0L) {

                val overlapFrames =
                    -startDelta

                playbackOverlapChunks++

                totalPlaybackOverlapFrames +=
                    overlapFrames

                largestPlaybackOverlapFrames =
                    maxOf(
                        largestPlaybackOverlapFrames,
                        overlapFrames
                    )

            } else if (startDelta > 0L) {

                val gapFrames =
                    startDelta

                playbackGapChunks++

                totalPlaybackGapFrames +=
                    gapFrames

                largestPlaybackGapFrames =
                    maxOf(
                        largestPlaybackGapFrames,
                        gapFrames
                    )
            }
        }

        previousPlaybackStartFrame =
            startFrameIndex

        previousPlaybackFrameCount =
            frameCount.toLong()

        hasPreviousPlaybackChunk = true

        // ======================================
        // ORIGINAL PLAYBACK MIXING
        // ======================================

        playbackChunksReceived++

        lastPlaybackStartFrame =
            startFrameIndex

        lastPlaybackEndFrame =
            startFrameIndex + frameCount

        var dropped = 0

        for (i in 0 until frameCount) {

            val sampleIndex =
                startFrameIndex + i

            if (sampleIndex < nextSampleIndex) {
                playbackFramesTooEarly++
                dropped++
                continue
            }

            if (
                sampleIndex >=
                nextSampleIndex + capacityFrames
            ) {
                playbackFramesTooFarAhead++
                dropped++
                continue
            }

            val slot =
                prepareSlot(sampleIndex)

            if (
                playbackFrameTags[slot] == sampleIndex
            ) {
                playbackDuplicateFrames++
                continue
            }

            playbackFrameTags[slot] =
                sampleIndex

            val left =
                (samples[i * 2].toInt() * gain).roundToInt()

            val right =
                (samples[i * 2 + 1].toInt() * gain).roundToInt()

            addFrame(
                sampleIndex,
                left,
                right
            )

            playbackFramesAccepted++
        }

        dropped
    }

    // ==========================================
    // READ PLAYBACK DIAGNOSTICS
    // ==========================================

    @Synchronized
    fun getDiagnostics(): String {

        val totalPlaybackFrames =
            playbackFramesAccepted +
                playbackFramesTooEarly +
                playbackFramesTooFarAhead +
                playbackDuplicateFrames

        val smallestDeltaText =
            if (smallestPlaybackStartDelta == Long.MAX_VALUE) {
                "N/A"
            } else {
                smallestPlaybackStartDelta.toString()
            }

        val largestDeltaText =
            if (largestPlaybackStartDelta == Long.MIN_VALUE) {
                "N/A"
            } else {
                largestPlaybackStartDelta.toString()
            }

        return """

            MIXER PLAYBACK TIMING

            Playback chunks processed: $playbackChunksReceived
            Playback frames examined: $totalPlaybackFrames
            Playback frames accepted: $playbackFramesAccepted
            Frames too early: $playbackFramesTooEarly
            Frames too far ahead: $playbackFramesTooFarAhead
            Duplicate playback frames ignored: $playbackDuplicateFrames

            ========================================
            PLAYBACK TIMESTAMP CONTINUITY
            ========================================

            Chunks with overlap: $playbackOverlapChunks
            Chunks with gaps: $playbackGapChunks

            Total overlap frames: $totalPlaybackOverlapFrames
            Total gap frames: $totalPlaybackGapFrames

            Largest overlap: $largestPlaybackOverlapFrames frames
            Largest gap: $largestPlaybackGapFrames frames

            Smallest start delta: $smallestDeltaText frames
            Largest start delta: $largestDeltaText frames

            Last start delta: $lastPlaybackStartDelta frames

            Expected start delta: 0 frames

            ========================================

            Duplicate microphone frames ignored: $microphoneDuplicateFrames

            Encoder next sample index: $nextSampleIndex
            Last playback start index: $lastPlaybackStartFrame
            Last playback end index: $lastPlaybackEndFrame

            Buffer capacity: $capacityFrames frames

            MIXER OUTPUT GAP DIAGNOSTICS

            Output frames examined: $outputFramesExamined
            Output frames with input data: $outputFramesWithInput
            Output frames without input data: $outputFramesWithoutInput

            Longest missing frames: $longestMissingFrameRun
            Current missing frames: $currentMissingFrameRun

            INTERNAL GAP DIAGNOSTICS

            Internal gap runs: $internalGapRuns
            Internal missing frames: $internalMissingFrames
            Longest internal gap: $longestInternalGap
            First frame with input: $firstOutputFrameWithInput
            Last frame with input: $lastOutputFrameWithInput

            Longest missing gap duration:
                ${longestMissingFrameRun * 1000 / AUDIO_SAMPLE_RATE} ms

            Longest internal gap duration:
                ${longestInternalGap * 1000 / AUDIO_SAMPLE_RATE} ms

            Mixed peak before limiter: $mixedPeakBeforeLimiter

            ========================================
            MIXER OPERATION TIMING
            ========================================

            ${playbackTiming.report("PLAYBACK INPUT")}

            ${microphoneTiming.report("MICROPHONE INPUT")}

            ${encoderTiming.report("ENCODER OUTPUT")}

        """.trimIndent()
    }

    // ==========================================
    // ADD ONE STEREO FRAME TO THE MIX
    // ==========================================

    private fun addFrame(
        sampleIndex: Long,
        left: Int,
        right: Int
    ) {

        val slot =
            prepareSlot(sampleIndex)

        leftSamples[slot] += left
        rightSamples[slot] += right

        highestSampleExclusive =
            maxOf(
                highestSampleExclusive,
                sampleIndex + 1
            )
    }

    // ==========================================
    // SOFT LIMITER
    // ==========================================

    private fun limitSample(value: Int): Short {

        val magnitude =
            abs(value.toLong()).toDouble()

        val knee = 18000.0
        val maximumMix = 65534.0
        val ceiling = AUDIO_PEAK_CEILING.toDouble()

        val limited =
            if (magnitude <= knee) {

                magnitude

            } else {

                val range =
                    maximumMix - knee

                val normalized =
                    (
                        1.0 -
                        exp(
                            -(magnitude - knee) / range
                        )
                    ) /
                    (1.0 - exp(-1.0))

                knee +
                    (ceiling - knee) *
                    normalized.coerceIn(0.0, 1.0)
            }

        val signed =
            if (value < 0) -limited else limited

        return signed
            .toInt()
            .coerceIn(
                -AUDIO_PEAK_CEILING,
                AUDIO_PEAK_CEILING
            )
            .toShort()
    }

    // ==========================================
    // TAKE NEXT AUDIO FRAME
    // ==========================================

    fun takeNextFrame(
        force: Boolean
    ): MixedAudioFrame? = timedSynchronized(encoderTiming) {

        val frameEndIndex =
            nextSampleIndex + AUDIO_FRAME_SIZE

        if (force) {

            if (nextSampleIndex >= highestSampleExclusive) {
                return@timedSynchronized null
            }

        } else {

            val frameEndNanos =
                clockOriginNanos +
                    frameEndIndex *
                    1_000_000_000L /
                    AUDIO_SAMPLE_RATE

            val readyAt =
                frameEndNanos +
                    MIX_LATENCY_MS * 1_000_000L

            if (
                SystemClock.elapsedRealtimeNanos() < readyAt
            ) {
                return@timedSynchronized null
            }
        }

        val frameStart =
            nextSampleIndex

        val output =
            ShortArray(AUDIO_FRAME_SIZE * 2)

        for (i in 0 until AUDIO_FRAME_SIZE) {

            val sampleIndex =
                frameStart + i

            val slot =
                (sampleIndex % capacityFrames).toInt()

            val hasSample =
                sampleTags[slot] == sampleIndex

            if (hasSample) {

                outputFramesWithInput++
                currentMissingFrameRun = 0

                if (hasSeenInputFrame && pendingMissingFrames > 0) {

                    internalGapRuns++

                    internalMissingFrames +=
                        pendingMissingFrames

                    longestInternalGap =
                        maxOf(
                            longestInternalGap,
                            pendingMissingFrames
                        )

                    pendingMissingFrames = 0
                }

                if (!hasSeenInputFrame) {
                    firstOutputFrameWithInput = sampleIndex
                }

                hasSeenInputFrame = true
                lastOutputFrameWithInput = sampleIndex

            } else {

                outputFramesWithoutInput++
                currentMissingFrameRun++

                longestMissingFrameRun =
                    maxOf(
                        longestMissingFrameRun,
                        currentMissingFrameRun
                    )

                if (hasSeenInputFrame) {
                    pendingMissingFrames++
                }
            }

            outputFramesExamined++

            val left =
                if (hasSample) leftSamples[slot] else 0

            val right =
                if (hasSample) rightSamples[slot] else 0

            mixedPeakBeforeLimiter =
                maxOf(
                    mixedPeakBeforeLimiter,
                    abs(left),
                    abs(right)
                )

            output[i * 2] =
                limitSample(left)

            output[i * 2 + 1] =
                limitSample(right)

            if (hasSample) {

                leftSamples[slot] = 0
                rightSamples[slot] = 0

                sampleTags[slot] = EMPTY_TAG
                playbackFrameTags[slot] = EMPTY_TAG
                microphoneFrameTags[slot] = EMPTY_TAG
            }
        }

        nextSampleIndex += AUDIO_FRAME_SIZE

        MixedAudioFrame(
            output,
            frameStart
        )
    }
}
