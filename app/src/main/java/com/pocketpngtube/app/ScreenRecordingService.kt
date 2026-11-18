
package com.pocketpngtube.app

import android.media.AudioTimestamp
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class ScreenRecordingService : Service() {

    companion object {
        const val ACTION_START =
            "com.pocketpngtube.app.START_SCREEN_RECORDING"
        const val ACTION_STOP =
            "com.pocketpngtube.app.STOP_SCREEN_RECORDING"
        const val ACTION_STATUS =
            "com.pocketpngtube.app.SCREEN_RECORDING_STATUS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_MICROPHONE_GAIN = "microphone_gain"
        const val EXTRA_PLAYBACK_GAIN = "playback_gain"

        private const val CHANNEL_ID = "pocketpngtube_screen_recording"
        private const val NOTIFICATION_ID = 2001

        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val AUDIO_BIT_RATE = 192000
        private const val AUDIO_FRAME_SIZE = 1024
        private const val AUDIO_PEAK_CEILING = 23197
        private const val DIAGNOSTIC_WAV_SECONDS = 20

        // Number of successful AudioTimestamp readings used to
        // calculate the stable frames-ahead offset.
        private const val TIMESTAMP_OFFSET_CALIBRATION_SAMPLES = 32

        @Volatile
        var isRecording = false
            private set
    }

    private data class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    // ==========================================
    // TIMING DIAGNOSTICS
    // ==========================================

    private class TimingStats {

        private val calls = AtomicLong(0)
        private val totalNanos = AtomicLong(0)
        private val maximumNanos = AtomicLong(0)
        private val over2Ms = AtomicLong(0)
        private val over5Ms = AtomicLong(0)
        private val over10Ms = AtomicLong(0)

        fun record(durationNanos: Long) {
            val safeDuration = durationNanos.coerceAtLeast(0L)

            calls.incrementAndGet()
            totalNanos.addAndGet(safeDuration)

            maximumNanos.updateAndGet {
                maxOf(it, safeDuration)
            }

            if (safeDuration > 2_000_000L) over2Ms.incrementAndGet()
            if (safeDuration > 5_000_000L) over5Ms.incrementAndGet()
            if (safeDuration > 10_000_000L) over10Ms.incrementAndGet()
        }

        fun reset() {
            calls.set(0)
            totalNanos.set(0)
            maximumNanos.set(0)
            over2Ms.set(0)
            over5Ms.set(0)
            over10Ms.set(0)
        }

        fun report(name: String): String {
            val count = calls.get()
            val total = totalNanos.get()
            val maximum = maximumNanos.get()

            val averageMs =
                if (count > 0) {
                    total.toDouble() / count / 1_000_000.0
                } else {
                    0.0
                }

            val maximumMs = maximum.toDouble() / 1_000_000.0

            return """
                $name
                Calls: $count
                Average: ${"%.3f".format(averageMs)} ms
                Maximum: ${"%.3f".format(maximumMs)} ms
                Over 2 ms: ${over2Ms.get()}
                Over 5 ms: ${over5Ms.get()}
                Over 10 ms: ${over10Ms.get()}
            """.trimIndent()
        }
    }

    /**
     * Diagnostic-only tracking of Android's AudioTimestamp frame counter.
     *
     * This class does not modify audio samples or timestamps.
     */
    private class FrameTimestampDiagnostics {

        private var sampleCount = 0L

        private var firstOffset = 0L
        private var lastOffset = 0L
        private var minimumOffset = Long.MAX_VALUE
        private var maximumOffset = Long.MIN_VALUE

        private var previousOffset: Long? = null
        private var maximumOffsetChange = 0L

        private var offsetChangesOver1 = 0L
        private var offsetChangesOver4 = 0L
        private var offsetChangesOver16 = 0L
        private var offsetChangesOver128 = 0L
        private var offsetChangesOver512 = 0L

        private var previousTimestampFramePosition: Long? = null
        private var previousTotalFramesRead: Long? = null

        private var frameDeltaSamples = 0L
        private var minimumFrameDeltaDifference = Long.MAX_VALUE
        private var maximumFrameDeltaDifference = Long.MIN_VALUE
        private var maximumAbsoluteFrameDeltaDifference = 0L

        private var frameDeltaDifferencesOver1 = 0L
        private var frameDeltaDifferencesOver4 = 0L
        private var frameDeltaDifferencesOver16 = 0L
        private var frameDeltaDifferencesOver128 = 0L
        private var frameDeltaDifferencesOver512 = 0L

        private var calibrationSamples = 0
        private var calibratedOffset: Long? = null

        @Synchronized
        fun reset() {
            sampleCount = 0

            firstOffset = 0
            lastOffset = 0
            minimumOffset = Long.MAX_VALUE
            maximumOffset = Long.MIN_VALUE

            previousOffset = null
            maximumOffsetChange = 0

            offsetChangesOver1 = 0
            offsetChangesOver4 = 0
            offsetChangesOver16 = 0
            offsetChangesOver128 = 0
            offsetChangesOver512 = 0

            previousTimestampFramePosition = null
            previousTotalFramesRead = null

            frameDeltaSamples = 0
            minimumFrameDeltaDifference = Long.MAX_VALUE
            maximumFrameDeltaDifference = Long.MIN_VALUE
            maximumAbsoluteFrameDeltaDifference = 0

            frameDeltaDifferencesOver1 = 0
            frameDeltaDifferencesOver4 = 0
            frameDeltaDifferencesOver16 = 0
            frameDeltaDifferencesOver128 = 0
            frameDeltaDifferencesOver512 = 0

            calibrationSamples = 0
            calibratedOffset = null
        }

        @Synchronized
        fun recordCalibration(
            samplesUsed: Int,
            offset: Long
        ) {
            calibrationSamples = samplesUsed
            calibratedOffset = offset
        }

        @Synchronized
        fun record(
            timestampFramePosition: Long,
            totalFramesRead: Long,
            framesAhead: Long
        ) {
            sampleCount++

            if (sampleCount == 1L) {
                firstOffset = framesAhead
            }

            lastOffset = framesAhead
            minimumOffset = minOf(minimumOffset, framesAhead)
            maximumOffset = maxOf(maximumOffset, framesAhead)

            val oldOffset = previousOffset

            if (oldOffset != null) {
                val offsetChange = framesAhead - oldOffset
                val absoluteChange = abs(offsetChange)

                maximumOffsetChange =
                    maxOf(maximumOffsetChange, absoluteChange)

                if (absoluteChange > 1) offsetChangesOver1++
                if (absoluteChange > 4) offsetChangesOver4++
                if (absoluteChange > 16) offsetChangesOver16++
                if (absoluteChange > 128) offsetChangesOver128++
                if (absoluteChange > 512) offsetChangesOver512++
            }

            previousOffset = framesAhead

            val oldTimestampPosition =
                previousTimestampFramePosition

            val oldReadPosition =
                previousTotalFramesRead

            if (
                oldTimestampPosition != null &&
                oldReadPosition != null
            ) {
                val timestampFrameDelta =
                    timestampFramePosition - oldTimestampPosition

                val readFrameDelta =
                    totalFramesRead - oldReadPosition

                val difference =
                    timestampFrameDelta - readFrameDelta

                val absoluteDifference = abs(difference)

                frameDeltaSamples++

                minimumFrameDeltaDifference =
                    minOf(minimumFrameDeltaDifference, difference)

                maximumFrameDeltaDifference =
                    maxOf(maximumFrameDeltaDifference, difference)

                maximumAbsoluteFrameDeltaDifference =
                    maxOf(
                        maximumAbsoluteFrameDeltaDifference,
                        absoluteDifference
                    )

                if (absoluteDifference > 1) frameDeltaDifferencesOver1++
                if (absoluteDifference > 4) frameDeltaDifferencesOver4++
                if (absoluteDifference > 16) frameDeltaDifferencesOver16++
                if (absoluteDifference > 128) frameDeltaDifferencesOver128++
                if (absoluteDifference > 512) frameDeltaDifferencesOver512++
            }

            previousTimestampFramePosition =
                timestampFramePosition

            previousTotalFramesRead =
                totalFramesRead
        }

        @Synchronized
        fun report(): String {

            if (sampleCount == 0L) {
                return """
                    PLAYBACK TIMESTAMP CONTINUITY

                    No successful timestamp samples were recorded.

                    Calibration samples: $calibrationSamples
                    Calibrated offset: ${calibratedOffset ?: "Not calibrated"}
                """.trimIndent()
            }

            val minimumFrameDelta =
                if (frameDeltaSamples > 0) {
                    minimumFrameDeltaDifference.toString()
                } else {
                    "N/A"
                }

            val maximumFrameDelta =
                if (frameDeltaSamples > 0) {
                    maximumFrameDeltaDifference.toString()
                } else {
                    "N/A"
                }

            return """
                PLAYBACK TIMESTAMP CONTINUITY

                Successful timestamp samples: $sampleCount

                FRAMES AHEAD OFFSET
                Definition: timestamp.framePosition - totalFramesRead

                First offset: $firstOffset frames
                Last offset: $lastOffset frames
                Minimum offset: $minimumOffset frames
                Maximum offset: $maximumOffset frames

                STABLE OFFSET CALIBRATION

                Calibration samples used: $calibrationSamples
                Calibrated offset: ${calibratedOffset ?: "Not calibrated"}
                Required samples: $TIMESTAMP_OFFSET_CALIBRATION_SAMPLES

                OFFSET CHANGE BETWEEN READS
                Maximum absolute change: $maximumOffsetChange frames

                Changes greater than 1 frame: $offsetChangesOver1
                Changes greater than 4 frames: $offsetChangesOver4
                Changes greater than 16 frames: $offsetChangesOver16
                Changes greater than 128 frames: $offsetChangesOver128
                Changes greater than 512 frames: $offsetChangesOver512

                FRAME COUNTER ADVANCEMENT

                Comparisons: $frameDeltaSamples
                Difference = timestamp frame advance - read frame advance

                Minimum difference: $minimumFrameDelta frames
                Maximum difference: $maximumFrameDelta frames
                Maximum absolute difference: $maximumAbsoluteFrameDeltaDifference frames

                Differences greater than 1 frame: $frameDeltaDifferencesOver1
                Differences greater than 4 frames: $frameDeltaDifferencesOver4
                Differences greater than 16 frames: $frameDeltaDifferencesOver16
                Differences greater than 128 frames: $frameDeltaDifferencesOver128
                Differences greater than 512 frames: $frameDeltaDifferencesOver512
            """.trimIndent()
        }
    }

    private val playbackReadDurationTiming = TimingStats()
    private val playbackReadIntervalTiming = TimingStats()
    private val playbackReadOverrunTiming = TimingStats()
    private val playbackProcessingTiming = TimingStats()

    private val encoderTakeFrameTiming = TimingStats()
    private val encoderInputWaitTiming = TimingStats()
    private val encoderQueueInputTiming = TimingStats()
    private val encoderFrameAgeTiming = TimingStats()
    private val encoderDrainTiming = TimingStats()

    private val playbackFrameTimestampDiagnostics =
        FrameTimestampDiagnostics()

    private val encoderNullFramePolls = AtomicLong(0)
    private val encoderFramesReceived = AtomicLong(0)
    private val encoderFramesQueued = AtomicLong(0)

    private fun resetServiceTimingDiagnostics() {
        playbackReadDurationTiming.reset()
        playbackReadIntervalTiming.reset()
        playbackReadOverrunTiming.reset()
        playbackProcessingTiming.reset()

        encoderTakeFrameTiming.reset()
        encoderInputWaitTiming.reset()
        encoderQueueInputTiming.reset()
        encoderFrameAgeTiming.reset()
        encoderDrainTiming.reset()

        playbackFrameTimestampDiagnostics.reset()

        encoderNullFramePolls.set(0)
        encoderFramesReceived.set(0)
        encoderFramesQueued.set(0)
    }

    private fun buildServiceTimingDiagnostics(): String {
        return """
            ========================================
            SERVICE AUDIO TIMING DIAGNOSTICS
            ========================================

            PLAYBACK CAPTURE

            ${playbackReadDurationTiming.report("AudioRecord.read duration")}

            ${playbackReadIntervalTiming.report("Time between completed reads")}

            ${playbackReadOverrunTiming.report("Read interval excess beyond expected chunk duration")}

            ${playbackProcessingTiming.report("Mixer submission processing duration")}

            ${playbackFrameTimestampDiagnostics.report()}

            ========================================
            AUDIO ENCODER
            ========================================

            Mixer frame requests: ${encoderTakeFrameTimingCount()}
            Mixer frames received: ${encoderFramesReceived.get()}
            Mixer frames queued: ${encoderFramesQueued.get()}
            Empty mixer polls: ${encoderNullFramePolls.get()}

            ${encoderTakeFrameTiming.report("Mixer takeNextFrame duration")}

            ${encoderInputWaitTiming.report("AAC input buffer wait duration")}

            ${encoderQueueInputTiming.report("AAC queueInputBuffer duration")}

            ${encoderFrameAgeTiming.report("Frame ready-to-queue age")}

            ${encoderDrainTiming.report("AAC output drain duration")}

            ========================================
        """.trimIndent()
    }

    private fun encoderTakeFrameTimingCount(): Long {
        return encoderFramesReceived.get() +
            encoderNullFramePolls.get()
    }

    // ==========================================
    // FIELDS
    // ==========================================

    private val muxerLock = Any()
    private val stopLock = Any()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null

    private var videoInputSurface: Surface? = null

    private var mediaMuxer: MediaMuxer? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    private var outputUri: Uri? = null
    private var outputFile: File? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var playbackThread: Thread? = null
    private var cleanupThread: Thread? = null

    private val audioMixer = PcmAudioMixer()

    private var playbackAudioRecord: AudioRecord? = null

    // ==========================================
    // TEMPORARY WAV DIAGNOSTICS
    // ==========================================

    @Volatile
    private var rawPlaybackWavWriter: RawPcmWavWriter? = null

    @Volatile
    private var postMixerWavWriter: RawPcmWavWriter? = null

    @Volatile
    private var rawPlaybackWavPath = "Not created"

    @Volatile
    private var postMixerWavPath = "Not created"

    @Volatile
    private var playbackCaptureRunning = false

    @Volatile
    private var playbackCaptureError = "Not started"

    @Volatile
    private var microphoneGain = 0.40f

    @Volatile
    private var playbackGain = 0.40f

    private val pendingSamples = ArrayList<PendingSample>()

    // ==========================================
    // AUDIO DIAGNOSTICS
    // ==========================================

    private val micChunksReceived = AtomicLong(0)
    private val micChunksDropped = AtomicLong(0)

    private val micSamplesReceived = AtomicLong(0)
    private val micNonZeroSamples = AtomicLong(0)
    private val micPeakAmplitude = AtomicLong(0)

    private val playbackChunksReceived = AtomicLong(0)
    private val playbackChunksDropped = AtomicLong(0)

    private val playbackSamplesReceived = AtomicLong(0)
    private val playbackNonZeroSamples = AtomicLong(0)
    private val playbackPeakAmplitude = AtomicLong(0)

    private val playbackTimestampSuccess = AtomicLong(0)
    private val playbackTimestampFailures = AtomicLong(0)

    private val audioInputBuffersQueued = AtomicLong(0)
    private val audioSamplesQueued = AtomicLong(0)

    private val audioOutputBuffers = AtomicLong(0)
    private val audioEncodedBytes = AtomicLong(0)

    private val audioMuxerWrites = AtomicLong(0)

    @Volatile
    private var audioTrackFormat = "Not registered"

    private var videoTrack = -1
    private var audioTrack = -1

    private var muxerStarted = false

    private var recordingStartNanoTime = 0L
    private var recordingStartElapsedNanos = 0L

    private var lastVideoPresentationTimeUs = -1L
    private var lastAudioInputPresentationTimeUs = -1L

    @Volatile
    private var audioStopping = false

    @Volatile
    private var stopping = false

    private var failureMessage: String? = null

    // ==========================================
    // MICROPHONE LISTENER
    // ==========================================

    private val audioListener =
        AudioFrameHub.AudioFrameListener {
                samples,
                timestampNanos ->

            if (isRecording && !stopping) {

                var nonZero = 0L
                var peak = 0

                for (sample in samples) {
                    val amplitude = abs(sample.toInt())

                    if (amplitude > 0) nonZero++

                    peak = maxOf(peak, amplitude)
                }

                micChunksReceived.incrementAndGet()

                micSamplesReceived.addAndGet(
                    samples.size.toLong()
                )

                micNonZeroSamples.addAndGet(nonZero)

                micPeakAmplitude.updateAndGet {
                    maxOf(it, peak.toLong())
                }

                val dropped = audioMixer.addMicrophoneSamples(
                    samples,
                    timestampNanos,
                    microphoneGain
                )

                if (dropped > 0) {
                    micChunksDropped.incrementAndGet()
                }
            }
        }

    // ==========================================
    // SERVICE LIFECYCLE
    // ==========================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_START -> {
                if (!isRecording && !stopping) {
                    startRecording(intent)
                }
            }

            ACTION_STOP -> {
                stopRecording()
            }
        }

        return START_NOT_STICKY
    }

    // ==========================================
    // START RECORDING
    // ==========================================

    private fun startRecording(intent: Intent) {

        microphoneGain = intent.getFloatExtra(
            EXTRA_MICROPHONE_GAIN,
            0.40f
        ).coerceIn(0f, 1f)

        playbackGain = intent.getFloatExtra(
            EXTRA_PLAYBACK_GAIN,
            0.40f
        ).coerceIn(0f, 1f)

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            0
        )

        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            sendStatus(
                false,
                "Screen recording permission data is missing."
            )

            stopSelf()
            return
        }

        try {
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(
                resultCode,
                resultData
            )

            val projection = mediaProjection
                ?: throw IllegalStateException(
                    "Android did not provide a screen capture session."
                )

            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(
                            "ScreenRecordingService",
                            "MediaProjection stopped."
                        )

                        stopRecording()
                    }
                },
                android.os.Handler(mainLooper)
            )

            prepareRecording()

            isRecording = true

            AudioFrameHub.addListener(audioListener)

            startPlaybackCapture(projection)

            sendStatus(
                true,
                "Screen, microphone, and eligible phone audio recording started. Mic gain: ${(microphoneGain * 100).toInt()}%. Phone gain: ${(playbackGain * 100).toInt()}%."
            )

        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Recording startup failed",
                exception
            )

            failureMessage = "Recording failed: ${exception.message}"

            stopRecording()
        }
    }

    // ==========================================
    // PREPARE RECORDING
    // ==========================================

    private fun prepareRecording() {

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)

        val originalWidth = metrics.widthPixels
        val originalHeight = metrics.heightPixels

        if (originalWidth <= 0 || originalHeight <= 0) {
            throw IllegalStateException(
                "Unable to determine screen dimensions."
            )
        }

        val scale = minOf(
            1f,
            1280f / maxOf(originalWidth, originalHeight)
        )

        val width = (
            originalWidth * scale
        ).toInt().coerceAtLeast(2) / 2 * 2

        val height = (
            originalHeight * scale
        ).toInt().coerceAtLeast(2) / 2 * 2

        prepareOutput()
        prepareMuxer()

        prepareVideoEncoder(width, height)
        prepareAudioEncoder()

        val projection = mediaProjection
            ?: throw IllegalStateException(
                "Screen capture session is unavailable."
            )

        val inputSurface = videoInputSurface
            ?: throw IllegalStateException(
                "Video encoder input surface was not created."
            )

        recordingStartNanoTime = System.nanoTime()

        recordingStartElapsedNanos =
            SystemClock.elapsedRealtimeNanos()

        lastVideoPresentationTimeUs = -1L
        lastAudioInputPresentationTimeUs = -1L

        resetAudioDiagnostics()
        resetServiceTimingDiagnostics()

        audioMixer.reset(recordingStartElapsedNanos)

        startEncoderThreads()

        virtualDisplay = projection.createVirtualDisplay(
            "PocketPNGTubeRecording",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
    }

    private fun resetAudioDiagnostics() {

        micChunksReceived.set(0)
        micChunksDropped.set(0)
        micSamplesReceived.set(0)
        micNonZeroSamples.set(0)
        micPeakAmplitude.set(0)

        playbackChunksReceived.set(0)
        playbackChunksDropped.set(0)
        playbackSamplesReceived.set(0)
        playbackNonZeroSamples.set(0)
        playbackPeakAmplitude.set(0)

        playbackTimestampSuccess.set(0)
        playbackTimestampFailures.set(0)

        audioInputBuffersQueued.set(0)
        audioSamplesQueued.set(0)
        audioOutputBuffers.set(0)
        audioEncodedBytes.set(0)
        audioMuxerWrites.set(0)

        audioTrackFormat = "Not registered"
        playbackCaptureError = "Not started"

        rawPlaybackWavPath = "Not created"
        postMixerWavPath = "Not created"
    }

    // ==========================================
    // PHONE PLAYBACK CAPTURE
    // ==========================================

    private fun startPlaybackCapture(
        projection: MediaProjection
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            playbackCaptureError =
                "Unavailable: Android 10 or newer is required."
            return
        }

        try {
            val captureConfig =
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

            val audioFormat =
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

            val minimumBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minimumBufferSize <= 0) {
                throw IllegalStateException(
                    "Android returned an invalid playback audio buffer size."
                )
            }

            val bufferSize = maxOf(
                minimumBufferSize * 2,
                16384
            )

            val recorder =
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()

                throw IllegalStateException(
                    "Playback AudioRecord could not initialize."
                )
            }

            playbackAudioRecord = recorder

            recorder.startRecording()

            if (
                recorder.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                throw IllegalStateException(
                    "Playback AudioRecord did not start."
                )
            }

            playbackCaptureRunning = true
            playbackCaptureError = "Running"

            // Create raw playback WAV diagnostic.
            try {
                val directory = File(
                    getExternalFilesDir(
                        android.os.Environment.DIRECTORY_MUSIC
                    ),
                    "Diagnostics"
                )

                val diagnosticFile = File(
                    directory,
                    "PocketPNGTube_RawPlayback_${System.currentTimeMillis()}.wav"
                )

                rawPlaybackWavWriter = RawPcmWavWriter(
                    file = diagnosticFile,
                    sampleRate = AUDIO_SAMPLE_RATE,
                    channels = AUDIO_CHANNEL_COUNT,
                    maximumSeconds = DIAGNOSTIC_WAV_SECONDS
                )

                rawPlaybackWavPath = diagnosticFile.absolutePath

                Log.i(
                    "ScreenRecordingService",
                    "Raw playback diagnostic: $rawPlaybackWavPath"
                )

            } catch (exception: Exception) {
                rawPlaybackWavPath =
                    "WAV diagnostic failed: ${exception.message}"

                Log.e(
                    "ScreenRecordingService",
                    "Could not create raw playback WAV.",
                    exception
                )
            }

            // Create post-mixer WAV diagnostic.
            try {
                val directory = File(
                    getExternalFilesDir(
                        android.os.Environment.DIRECTORY_MUSIC
                    ),
                    "Diagnostics"
                )

                val diagnosticFile = File(
                    directory,
                    "PocketPNGTube_PostMixer_${System.currentTimeMillis()}.wav"
                )

                postMixerWavWriter = RawPcmWavWriter(
                    file = diagnosticFile,
                    sampleRate = AUDIO_SAMPLE_RATE,
                    channels = AUDIO_CHANNEL_COUNT,
                    maximumSeconds = DIAGNOSTIC_WAV_SECONDS
                )

                postMixerWavPath = diagnosticFile.absolutePath

                Log.i(
                    "ScreenRecordingService",
                    "Post-mixer diagnostic: $postMixerWavPath"
                )

            } catch (exception: Exception) {
                postMixerWavPath =
                    "WAV diagnostic failed: ${exception.message}"

                Log.e(
                    "ScreenRecordingService",
                    "Could not create post-mixer WAV.",
                    exception
                )
            }

            playbackThread = Thread {
                readPlaybackAudio()
            }.apply {
                name = "PocketPNGTube-PlaybackCapture"
                start()
            }

            Log.i(
                "ScreenRecordingService",
                "48 kHz stereo phone playback capture started."
            )

        } catch (exception: Exception) {
            playbackCaptureRunning = false

            playbackCaptureError =
                "${exception.javaClass.simpleName}: ${exception.message}"

            Log.e(
                "ScreenRecordingService",
                "Phone playback capture failed to start.",
                exception
            )

            releasePlaybackRecorder()
        }
    }

    // ==========================================
    // PLAYBACK AUDIO READING
    // ==========================================

private fun readPlaybackAudio() {

    val recorder = playbackAudioRecord ?: return
    val samples = ShortArray(2048)
    val audioTimestamp = AudioTimestamp()

    var totalFramesRead = 0L
    var previousReadCompletionNanos = 0L

    // ==========================================
    // FIXED PLAYBACK TIMELINE
    // ==========================================

    // This is established once, using the first successful
    // Android AudioTimestamp reading.
    //
    // All subsequent chunks are positioned using cumulative
    // frames read rather than changing timestamp readings.
    var sequentialTimelineOriginNanos: Long? = null

    // Keep the original calibration measurements for
    // diagnostics, but do not use the median to position audio.
    val timestampOffsetSamples =
        ArrayList<Long>(TIMESTAMP_OFFSET_CALIBRATION_SAMPLES)

    var stableFramesAheadOffset: Long? = null

    try {
        while (
            playbackCaptureRunning &&
            isRecording &&
            !stopping
        ) {
            val readStartedNanos =
                SystemClock.elapsedRealtimeNanos()

            val count = recorder.read(
                samples,
                0,
                samples.size,
                AudioRecord.READ_BLOCKING
            )

            val readCompletedNanos =
                SystemClock.elapsedRealtimeNanos()

            playbackReadDurationTiming.record(
                readCompletedNanos - readStartedNanos
            )

            if (count > 1) {

                val safeCount =
                    count - (count % 2)

                val framesInChunk =
                    safeCount / 2

                if (previousReadCompletionNanos != 0L) {

                    val actualInterval =
                        readCompletedNanos -
                            previousReadCompletionNanos

                    val expectedInterval =
                        framesInChunk.toLong() *
                            1_000_000_000L /
                            AUDIO_SAMPLE_RATE

                    playbackReadIntervalTiming.record(
                        actualInterval
                    )

                    playbackReadOverrunTiming.record(
                        (actualInterval - expectedInterval)
                            .coerceAtLeast(0L)
                    )
                }

                previousReadCompletionNanos =
                    readCompletedNanos

                totalFramesRead += framesInChunk

                // ======================================
                // READ AND RECORD ANDROID TIMESTAMP
                // ======================================

                val timestampStatus =
                    recorder.getTimestamp(
                        audioTimestamp,
                        AudioTimestamp.TIMEBASE_BOOTTIME
                    )

                if (timestampStatus == 0) {

                    playbackTimestampSuccess.incrementAndGet()

                    val framesAhead =
                        audioTimestamp.framePosition -
                            totalFramesRead

                    // Keep the original timestamp diagnostics.
                    playbackFrameTimestampDiagnostics.record(
                        timestampFramePosition =
                            audioTimestamp.framePosition,
                        totalFramesRead =
                            totalFramesRead,
                        framesAhead =
                            framesAhead
                    )

                    // Retain the original calibration measurement
                    // for comparison in the diagnostics.
                    if (
                        stableFramesAheadOffset == null &&
                        timestampOffsetSamples.size <
                            TIMESTAMP_OFFSET_CALIBRATION_SAMPLES
                    ) {
                        timestampOffsetSamples.add(framesAhead)

                        if (
                            timestampOffsetSamples.size ==
                            TIMESTAMP_OFFSET_CALIBRATION_SAMPLES
                        ) {
                            val sortedOffsets =
                                timestampOffsetSamples.sorted()

                            stableFramesAheadOffset =
                                sortedOffsets[sortedOffsets.size / 2]

                            playbackFrameTimestampDiagnostics.recordCalibration(
                                samplesUsed =
                                    timestampOffsetSamples.size,
                                offset =
                                    stableFramesAheadOffset!!
                            )

                            Log.i(
                                "ScreenRecordingService",
                                "Playback timestamp diagnostic calibration: " +
                                    "$stableFramesAheadOffset frames."
                            )
                        }
                    }

                    // Establish the timeline origin only once.
                    // AudioTimestamp.nanoTime corresponds to
                    // AudioTimestamp.framePosition.
                    if (sequentialTimelineOriginNanos == null) {

                        sequentialTimelineOriginNanos =
                            audioTimestamp.nanoTime -
                                (
                                    audioTimestamp.framePosition *
                                        1_000_000_000L /
                                        AUDIO_SAMPLE_RATE
                                )

                        Log.i(
                            "ScreenRecordingService",
                            "Sequential playback timeline established."
                        )
                    }

                } else {
                    playbackTimestampFailures.incrementAndGet()
                }

                // ======================================
                // CALCULATE CONTINUOUS CHUNK TIMESTAMP
                // ======================================

                // If Android has not provided a successful timestamp
                // yet, establish an approximate fixed origin using
                // the first read completion.
                //
                // Once established, this origin never changes.
                if (sequentialTimelineOriginNanos == null) {

                    sequentialTimelineOriginNanos =
                        readCompletedNanos -
                            (
                                totalFramesRead *
                                    1_000_000_000L /
                                    AUDIO_SAMPLE_RATE
                            )

                    Log.w(
                        "ScreenRecordingService",
                        "Using read-completion fallback for playback timeline origin."
                    )
                }

                // Each chunk receives a position based on the
                // cumulative number of frames captured.
                //
                // The mixer subtracts this chunk's duration to
                // calculate its start position.
                val chunkEndTimestampNanos =
                    sequentialTimelineOriginNanos!! +
                        (
                            totalFramesRead *
                                1_000_000_000L /
                                AUDIO_SAMPLE_RATE
                        )

                // ======================================
                // WRITE RAW PLAYBACK WAV
                // ======================================

                try {
                    rawPlaybackWavWriter?.write(
                        samples,
                        safeCount
                    )
                } catch (exception: Exception) {
                    Log.e(
                        "ScreenRecordingService",
                        "Pre-mixer WAV write failed.",
                        exception
                    )
                }

                // ======================================
                // PLAYBACK INPUT DIAGNOSTICS
                // ======================================

                var nonZero = 0L
                var peak = 0

                for (i in 0 until safeCount) {

                    val amplitude =
                        abs(samples[i].toInt())

                    if (amplitude > 0) nonZero++

                    peak = maxOf(peak, amplitude)
                }

                playbackChunksReceived.incrementAndGet()

                playbackSamplesReceived.addAndGet(
                    safeCount.toLong()
                )

                playbackNonZeroSamples.addAndGet(nonZero)

                playbackPeakAmplitude.updateAndGet {
                    maxOf(it, peak.toLong())
                }

                // ======================================
                // SUBMIT TO MIXER
                // ======================================

                val chunk =
                    samples.copyOf(safeCount)

                val submissionStartedNanos =
                    SystemClock.elapsedRealtimeNanos()

                val dropped = audioMixer.addPlaybackSamples(
                    chunk,
                    safeCount,
                    chunkEndTimestampNanos,
                    playbackGain
                )

                if (dropped > 0) {
                    playbackChunksDropped.incrementAndGet()
                }

                playbackProcessingTiming.record(
                    SystemClock.elapsedRealtimeNanos() -
                        submissionStartedNanos
                )

            } else if (
                count < 0 &&
                playbackCaptureRunning &&
                !stopping
            ) {

                playbackCaptureError =
                    "AudioRecord read failed: $count"

                Log.e(
                    "ScreenRecordingService",
                    playbackCaptureError
                )

                break
            }
        }

    } catch (exception: Exception) {

        if (!stopping) {

            playbackCaptureError =
                "${exception.javaClass.simpleName}: ${exception.message}"

            Log.e(
                "ScreenRecordingService",
                "Playback audio read failed.",
                exception
            )
        }

    } finally {

        playbackCaptureRunning = false

        if (stableFramesAheadOffset == null) {

            Log.w(
                "ScreenRecordingService",
                "Timestamp diagnostic calibration did not complete. " +
                    "Successful samples: " +
                    timestampOffsetSamples.size
            )
        }

        try {
            rawPlaybackWavWriter?.close()
        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Could not finalize raw playback WAV.",
                exception
            )
        } finally {
            rawPlaybackWavWriter = null
        }
    }
}

    private fun stopPlaybackCapture() {
        playbackCaptureRunning = false

        try {
            playbackAudioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun releasePlaybackRecorder() {
        try {
            playbackAudioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            playbackAudioRecord?.release()
        } catch (_: Exception) {
        }

        playbackAudioRecord = null
    }

    // ==========================================
    // POST-MIXER WAV DIAGNOSTIC HELPERS
    // ==========================================

    private fun writePostMixerDiagnostic(
        samples: ShortArray,
        sampleCount: Int
    ) {
        try {
            postMixerWavWriter?.write(
                samples,
                sampleCount
            )
        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Post-mixer WAV write failed.",
                exception
            )
        }
    }

    private fun closePostMixerDiagnostic() {
        try {
            postMixerWavWriter?.close()
        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Could not finalize post-mixer WAV.",
                exception
            )
        } finally {
            postMixerWavWriter = null
        }
    }

    // ==========================================
    // OUTPUT FILE
    // ==========================================

    private fun prepareOutput() {
        val fileName =
            "PocketPNGTube_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    fileName
                )

                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
                )

                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/PocketPNGTube"
                )

                put(
                    MediaStore.Video.Media.IS_PENDING,
                    1
                )
            }

            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException(
                "Could not create recording file."
            )

            outputDescriptor =
                contentResolver.openFileDescriptor(
                    outputUri!!,
                    "w"
                ) ?: throw IllegalStateException(
                    "Could not open recording file."
                )

        } else {
            val directory = File(
                getExternalFilesDir(null),
                "Recordings"
            )

            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException(
                    "Could not create recordings directory."
                )
            }

            outputFile = File(
                directory,
                fileName
            )
        }
    }

    // ==========================================
    // MEDIA MUXER
    // ==========================================

    private fun prepareMuxer() {
        mediaMuxer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaMuxer(
                    outputDescriptor!!.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            } else {
                MediaMuxer(
                    outputFile!!.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            }
    }

    // ==========================================
    // VIDEO ENCODER
    // ==========================================

    private fun prepareVideoEncoder(
        width: Int,
        height: Int
    ) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        )

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )

        format.setInteger(
            MediaFormat.KEY_BIT_RATE,
            3_000_000
        )

        format.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            24
        )

        format.setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            2
        )

        val encoder =
            MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            )

        videoEncoder = encoder

        encoder.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        videoInputSurface = encoder.createInputSurface()

        encoder.start()
    }

    // ==========================================
    // AUDIO ENCODER
    // ==========================================

    private fun prepareAudioEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT
        )

        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )

        format.setInteger(
            MediaFormat.KEY_BIT_RATE,
            AUDIO_BIT_RATE
        )

        format.setInteger(
            MediaFormat.KEY_MAX_INPUT_SIZE,
            8192
        )

        val encoder =
            MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_AUDIO_AAC
            )

        audioEncoder = encoder

        encoder.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        encoder.start()
    }

    // ==========================================
    // ENCODER THREADS
    // ==========================================

    private fun startEncoderThreads() {
        audioStopping = false

        videoThread = Thread {
            drainVideoEncoder()
        }.apply {
            name = "PocketPNGTube-VideoEncoder"
            start()
        }

        audioThread = Thread {
            runAudioEncoder()
        }.apply {
            name = "PocketPNGTube-AudioEncoder"
            start()
        }
    }

    // ==========================================
    // VIDEO ENCODING
    // ==========================================

    private fun drainVideoEncoder() {
        val encoder = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()

        try {
            var finished = false

            while (!finished) {
                val index = encoder.dequeueOutputBuffer(
                    info,
                    10_000
                )

                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        continue
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        registerTrack(
                            true,
                            encoder.outputFormat
                        )
                    }

                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)

                        if (
                            buffer != null &&
                            info.size > 0 &&
                            (info.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val data = copyEncodedData(buffer, info)

                            val rawPresentationTimeUs =
                                info.presentationTimeUs -
                                    recordingStartNanoTime / 1000L

                            val presentationTimeUs = maxOf(
                                0L,
                                rawPresentationTimeUs,
                                lastVideoPresentationTimeUs + 1L
                            )

                            lastVideoPresentationTimeUs =
                                presentationTimeUs

                            writeEncodedSample(
                                true,
                                data,
                                presentationTimeUs,
                                info.flags
                            )
                        }

                        finished =
                            (info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        encoder.releaseOutputBuffer(
                            index,
                            false
                        )
                    }
                }
            }

        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Video encoder failed.",
                exception
            )

            if (!stopping) {
                failureMessage =
                    "Video encoding failed: ${exception.message}"

                stopRecording()
            }
        }
    }

    // ==========================================
    // MIXED AUDIO ENCODING
    // ==========================================

    private fun runAudioEncoder() {
        val encoder = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()

        var currentFrame: MixedAudioFrame? = null
        var currentFrameReadyNanos = 0L

        var endOfStreamQueued = false
        var finished = false

        try {
            while (!finished) {
                if (currentFrame == null && !endOfStreamQueued) {
                    val takeStartedNanos =
                        SystemClock.elapsedRealtimeNanos()

                    currentFrame = audioMixer.takeNextFrame(
                        force = audioStopping
                    )

                    encoderTakeFrameTiming.record(
                        SystemClock.elapsedRealtimeNanos() -
                            takeStartedNanos
                    )

                    if (currentFrame == null) {
                        encoderNullFramePolls.incrementAndGet()
                    } else {
                        encoderFramesReceived.incrementAndGet()

                        currentFrameReadyNanos =
                            SystemClock.elapsedRealtimeNanos()
                    }
                }

                val shouldQueueEndOfStream =
                    audioStopping &&
                    currentFrame == null &&
                    !endOfStreamQueued

                if (
                    currentFrame != null ||
                    shouldQueueEndOfStream
                ) {
                    val inputWaitStartedNanos =
                        SystemClock.elapsedRealtimeNanos()

                    val inputIndex = encoder.dequeueInputBuffer(10_000)

                    encoderInputWaitTiming.record(
                        SystemClock.elapsedRealtimeNanos() -
                            inputWaitStartedNanos
                    )

                    if (inputIndex >= 0) {
                        val inputBuffer =
                            encoder.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException(
                                    "Audio encoder input buffer unavailable."
                                )

                        inputBuffer.clear()
                        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val frame = currentFrame

                        if (frame != null) {
                            val samplesToWrite = minOf(
                                frame.samples.size,
                                inputBuffer.remaining() / 2
                            )

                            val safeSamplesToWrite =
                                samplesToWrite - (samplesToWrite % 2)

                            writePostMixerDiagnostic(
                                frame.samples,
                                safeSamplesToWrite
                            )

                            for (i in 0 until safeSamplesToWrite) {
                                inputBuffer.putShort(frame.samples[i])
                            }

                            val presentationTimeUs =
                                frame.startSampleIndex *
                                    1_000_000L /
                                    AUDIO_SAMPLE_RATE

                            val safePresentationTimeUs = maxOf(
                                presentationTimeUs,
                                lastAudioInputPresentationTimeUs + 1L
                            )

                            val queueStartedNanos =
                                SystemClock.elapsedRealtimeNanos()

                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                safeSamplesToWrite * 2,
                                safePresentationTimeUs,
                                0
                            )

                            val queuedAtNanos =
                                SystemClock.elapsedRealtimeNanos()

                            encoderQueueInputTiming.record(
                                queuedAtNanos - queueStartedNanos
                            )

                            encoderFrameAgeTiming.record(
                                queuedAtNanos - currentFrameReadyNanos
                            )

                            lastAudioInputPresentationTimeUs =
                                safePresentationTimeUs

                            audioInputBuffersQueued.incrementAndGet()

                            audioSamplesQueued.addAndGet(
                                safeSamplesToWrite.toLong()
                            )

                            encoderFramesQueued.incrementAndGet()

                            currentFrame = null
                            currentFrameReadyNanos = 0L

                        } else if (shouldQueueEndOfStream) {
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                maxOf(
                                    0L,
                                    lastAudioInputPresentationTimeUs + 1L
                                ),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )

                            endOfStreamQueued = true
                        }
                    }

                } else if (!audioStopping) {
                    Thread.sleep(2)
                }

                val drainStartedNanos =
                    SystemClock.elapsedRealtimeNanos()

                finished = drainAudioOutput(
                    encoder,
                    info
                )

                encoderDrainTiming.record(
                    SystemClock.elapsedRealtimeNanos() -
                        drainStartedNanos
                )
            }

        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Audio encoder failed.",
                exception
            )

            if (!stopping) {
                failureMessage =
                    "Audio encoding failed: ${exception.message}"

                stopRecording()
            }
        }
    }

    // ==========================================
    // DRAIN AUDIO OUTPUT
    // ==========================================

    private fun drainAudioOutput(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo
    ): Boolean {
        var finished = false

        while (true) {
            val index = encoder.dequeueOutputBuffer(
                info,
                0
            )

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    registerTrack(
                        false,
                        encoder.outputFormat
                    )
                }

                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)

                    if (
                        buffer != null &&
                        info.size > 0 &&
                        (info.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val data = copyEncodedData(buffer, info)

                        audioOutputBuffers.incrementAndGet()

                        audioEncodedBytes.addAndGet(
                            data.size.toLong()
                        )

                        writeEncodedSample(
                            false,
                            data,
                            info.presentationTimeUs,
                            info.flags
                        )
                    }

                    finished =
                        (info.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    encoder.releaseOutputBuffer(
                        index,
                        false
                    )

                    if (finished) break
                }
            }
        }

        return finished
    }

    // ==========================================
    // COPY ENCODED DATA
    // ==========================================

    private fun copyEncodedData(
        source: ByteBuffer,
        info: MediaCodec.BufferInfo
    ): ByteArray {
        val buffer = source.duplicate()

        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val result = ByteArray(info.size)

        buffer.get(result)

        return result
    }

    // ==========================================
    // REGISTER MUXER TRACKS
    // ==========================================

    private fun registerTrack(
        isVideo: Boolean,
        format: MediaFormat
    ) {
        synchronized(muxerLock) {
            val muxer = mediaMuxer
                ?: throw IllegalStateException(
                    "Muxer is unavailable."
                )

            if (isVideo) {
                if (videoTrack == -1) {
                    videoTrack = muxer.addTrack(format)
                }
            } else {
                if (audioTrack == -1) {
                    audioTrack = muxer.addTrack(format)
                    audioTrackFormat = format.toString()
                }
            }

            if (
                !muxerStarted &&
                videoTrack >= 0 &&
                audioTrack >= 0
            ) {
                muxer.start()
                muxerStarted = true

                for (sample in pendingSamples) {
                    writeSampleLocked(sample)
                }

                pendingSamples.clear()

                Log.i(
                    "ScreenRecordingService",
                    "Audio/video muxer started."
                )
            }
        }
    }

    // ==========================================
    // WRITE ENCODED SAMPLES
    // ==========================================

    private fun writeEncodedSample(
        isVideo: Boolean,
        data: ByteArray,
        presentationTimeUs: Long,
        flags: Int
    ) {
        synchronized(muxerLock) {
            val sample = PendingSample(
                isVideo,
                data,
                presentationTimeUs,
                flags
            )

            if (!muxerStarted) {
                pendingSamples.add(sample)
            } else {
                writeSampleLocked(sample)
            }
        }
    }

    private fun writeSampleLocked(sample: PendingSample) {
        val muxer = mediaMuxer
            ?: throw IllegalStateException(
                "Muxer is unavailable."
            )

        val track =
            if (sample.isVideo) videoTrack else audioTrack

        if (track < 0) return

        val buffer = ByteBuffer.wrap(sample.data)

        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                sample.data.size,
                sample.presentationTimeUs,
                sample.flags
            )
        }

        muxer.writeSampleData(
            track,
            buffer,
            info
        )

        if (!sample.isVideo) {
            audioMuxerWrites.incrementAndGet()
        }
    }

    // ==========================================
    // STOP RECORDING
    // ==========================================

    private fun stopRecording() {
        synchronized(stopLock) {
            if (stopping) return

            stopping = true
            isRecording = false
        }

        AudioFrameHub.removeListener(audioListener)

        stopPlaybackCapture()

        cleanupThread = Thread {
            finishRecording()
        }.apply {
            name = "PocketPNGTube-RecordingCleanup"
            start()
        }
    }

    // ==========================================
    // FINALIZE RECORDING
    // ==========================================

    private fun finishRecording() {
        var saveSuccessful = false

        try {
            try {
                playbackThread?.join(5000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            playbackThread = null

            releasePlaybackRecorder()

            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }

            virtualDisplay = null

            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (exception: Exception) {
                Log.e(
                    "ScreenRecordingService",
                    "Could not signal video encoder end.",
                    exception
                )
            }

            audioStopping = true

            joinEncoderThread(videoThread)
            joinEncoderThread(audioThread)

            videoThread = null
            audioThread = null

            closePostMixerDiagnostic()

            try {
                videoEncoder?.stop()
            } catch (_: Exception) {
            }

            try {
                videoEncoder?.release()
            } catch (_: Exception) {
            }

            videoEncoder = null

            try {
                videoInputSurface?.release()
            } catch (_: Exception) {
            }

            videoInputSurface = null

            try {
                audioEncoder?.stop()
            } catch (_: Exception) {
            }

            try {
                audioEncoder?.release()
            } catch (_: Exception) {
            }

            audioEncoder = null

            synchronized(muxerLock) {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                    saveSuccessful = true
                }

                mediaMuxer?.release()
                mediaMuxer = null

                muxerStarted = false
                pendingSamples.clear()
            }

            val projection = mediaProjection
            mediaProjection = null

            try {
                projection?.stop()
            } catch (_: Exception) {
            }

            try {
                outputDescriptor?.close()
            } catch (_: Exception) {
            }

            outputDescriptor = null

            // ==========================================
            // AUDIO DIAGNOSTICS
            // ==========================================

            val diagnosticMessage = """
                AUDIO DIAGNOSTICS

                Audio track registered: ${audioTrack >= 0}
                Audio track format: $audioTrackFormat

                OUTPUT AUDIO

                Sample rate: ${AUDIO_SAMPLE_RATE} Hz
                Channels: Stereo
                AAC bitrate: ${AUDIO_BIT_RATE} bps
                Final peak ceiling: $AUDIO_PEAK_CEILING / 32767 (approximately -3 dBFS)

                RECORDING GAINS

                Microphone gain: ${(microphoneGain * 100).toInt()}%
                Phone playback gain: ${(playbackGain * 100).toInt()}%

                MICROPHONE

                Input format: 16 kHz mono

                Chunks received: ${micChunksReceived.get()}
                Chunks dropped: ${micChunksDropped.get()}

                PCM samples received: ${micSamplesReceived.get()}
                Non-zero PCM samples: ${micNonZeroSamples.get()}
                Peak amplitude before recording gain: ${micPeakAmplitude.get()}

                PHONE PLAYBACK AUDIO

                Input format: 48 kHz stereo

                Capture status: $playbackCaptureError
                Chunks received: ${playbackChunksReceived.get()}
                Chunks dropped: ${playbackChunksDropped.get()}

                Successful timestamps: ${playbackTimestampSuccess.get()}
                Failed timestamps: ${playbackTimestampFailures.get()}

                PCM samples received: ${playbackSamplesReceived.get()}
                Non-zero PCM samples: ${playbackNonZeroSamples.get()}
                Peak amplitude before recording gain: ${playbackPeakAmplitude.get()}

                Raw playback WAV: $rawPlaybackWavPath

                Post-mixer WAV: $postMixerWavPath

                MIXED AUDIO ENCODING

                AAC input buffers: ${audioInputBuffersQueued.get()}
                Mixed PCM samples queued: ${audioSamplesQueued.get()}

                AAC output buffers: ${audioOutputBuffers.get()}
                AAC encoded bytes: ${audioEncodedBytes.get()}

                Audio samples written to MP4: ${audioMuxerWrites.get()}

                ${audioMixer.getDiagnostics()}

                ${buildServiceTimingDiagnostics()}
            """.trimIndent()

            Log.i(
                "ScreenRecordingService",
                diagnosticMessage
            )

            if (saveSuccessful && failureMessage == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    outputUri?.let { uri ->
                        val values = ContentValues().apply {
                            put(
                                MediaStore.Video.Media.IS_PENDING,
                                0
                            )
                        }

                        val updated = contentResolver.update(
                            uri,
                            values,
                            null,
                            null
                        )

                        if (updated <= 0) {
                            throw IllegalStateException(
                                "Could not publish recording."
                            )
                        }
                    }
                }

                sendStatus(
                    false,
                    "Recording saved.\n\n$diagnosticMessage"
                )
            } else {
                deleteFailedOutput()

                sendStatus(
                    false,
                    failureMessage
                        ?: "Recording could not be finalized.\n\n$diagnosticMessage"
                )
            }

        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Recording finalization failed",
                exception
            )

            deleteFailedOutput()

            sendStatus(
                false,
                "Error saving recording: ${exception.message}"
            )

        } finally {
            AudioFrameHub.removeListener(audioListener)

            stopPlaybackCapture()
            releasePlaybackRecorder()

            try {
                rawPlaybackWavWriter?.close()
            } catch (_: Exception) {
            }

            rawPlaybackWavWriter = null

            closePostMixerDiagnostic()

            try {
                outputDescriptor?.close()
            } catch (_: Exception) {
            }

            outputDescriptor = null

            outputUri = null
            outputFile = null

            try {
                mediaProjection?.stop()
            } catch (_: Exception) {
            }

            mediaProjection = null

            try {
                mediaMuxer?.release()
            } catch (_: Exception) {
            }

            mediaMuxer = null

            isRecording = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            stopSelf()
        }
    }

    private fun joinEncoderThread(thread: Thread?) {
        if (thread == null) return
        if (thread == Thread.currentThread()) return

        try {
            thread.join(10000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (thread.isAlive) {
            Log.e(
                "ScreenRecordingService",
                "Encoder thread did not finish in time: ${thread.name}"
            )
        }
    }

    // ==========================================
    // DELETE FAILED OUTPUT
    // ==========================================

    private fun deleteFailedOutput() {
        try {
            outputUri?.let { uri ->
                contentResolver.delete(
                    uri,
                    null,
                    null
                )
            }

            outputFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
        } catch (exception: Exception) {
            Log.e(
                "ScreenRecordingService",
                "Could not delete incomplete output.",
                exception
            )
        }
    }

    // ==========================================
    // SEND RECORDING STATUS
    // ==========================================

    private fun sendStatus(
        recording: Boolean,
        message: String
    ) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)

            putExtra(
                EXTRA_RECORDING,
                recording
            )

            putExtra(
                EXTRA_MESSAGE,
                message
            )
        }

        sendBroadcast(intent)
    }

    // ==========================================
    // NOTIFICATION CHANNEL
    // ==========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketPNGTube Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shown while PocketPNGTube records your screen."
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    // ==========================================
    // RECORDING NOTIFICATION
    // ==========================================

    private fun createNotification(): Notification {
        val openIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(
            this,
            ScreenRecordingService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("PocketPNGTube is recording")
            .setContentText(
                "Screen, microphone, and eligible phone audio are being recorded."
            )
            .setSmallIcon(
                android.R.drawable.presence_video_online
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        if (
            isRecording ||
            mediaProjection != null
        ) {
            stopRecording()
        }

        super.onDestroy()
    }
}
