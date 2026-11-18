
package com.pocketpngtube.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class PocketService : Service() {

    companion object {

        const val ACTION_START =
            "com.pocketpngtube.app.ACTION_START_MICROPHONE"

        const val ACTION_STOP =
            "com.pocketpngtube.app.ACTION_STOP_MICROPHONE"

        const val ACTION_UPDATE =
            "com.pocketpngtube.app.ACTION_MICROPHONE_UPDATE"

        const val EXTRA_TALKING = "talking"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_ERROR = "error"
        const val EXTRA_RUNNING = "running"

        private const val CHANNEL_ID = "pocketpngtube_microphone"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    @Volatile
    private var recording = false

    private var microphoneThread: Thread? = null

    private var audioRecord: AudioRecord? = null

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

            ACTION_STOP -> {
                stopMicrophoneService()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                startMicrophoneService()
            }
        }

        return START_NOT_STICKY
    }

    // ==========================================
    // START SERVICE
    // ==========================================

    private fun startMicrophoneService() {

        if (recording) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendUpdate(
                running = false,
                error = "Microphone permission is missing."
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            } else {

                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            recording = true
            isRunning = true

            sendUpdate(running = true)

            microphoneThread = Thread {
                runMicrophoneDetection()
            }.apply {
                name = "PocketPNGTube-Microphone"
                start()
            }

        } catch (exception: SecurityException) {

            Log.e(
                "PocketService",
                "Unable to start microphone service",
                exception
            )

            recording = false
            isRunning = false

            sendUpdate(
                running = false,
                error = "Android blocked microphone startup: ${exception.message}"
            )

            stopSelf()

        } catch (exception: Exception) {

            Log.e(
                "PocketService",
                "Service startup failed",
                exception
            )

            recording = false
            isRunning = false

            sendUpdate(
                running = false,
                error = "Service startup failed: ${exception.message}"
            )

            stopSelf()
        }
    }

    // ==========================================
    // MICROPHONE DETECTION AND AUDIO SHARING
    // ==========================================

    private fun runMicrophoneDetection() {

        val sampleRate = 16000

        val minimumBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimumBuffer <= 0) {
            microphoneError("Could not initialize microphone audio.")
            return
        }

        val bufferSize = maxOf(minimumBuffer, 4096)

        var recorder: AudioRecord? = null

        try {

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "Microphone initialization failed."
                )
            }

            audioRecord = recorder

            recorder.startRecording()

            val samples = ShortArray(1024)
            val audioTimestamp = AudioTimestamp()

            var totalFramesRead = 0L

            // Fixed timestamp origin for this microphone stream.
            var timelineOriginNanos: Long? = null

            var aboveThresholdCount = 0
            var belowThresholdCount = 0
            var isTalkingLocally = false

            var lastLevelBroadcast = 0L

            while (recording) {

                val count = recorder.read(
                    samples,
                    0,
                    samples.size,
                    AudioRecord.READ_NON_BLOCKING
                )

                val readCompletedNanos =
                    SystemClock.elapsedRealtimeNanos()

                if (count > 0) {

                    // Each mono sample represents one 16 kHz frame.
                    totalFramesRead += count.toLong()

                    // ======================================
                    // ESTABLISH MICROPHONE TIMELINE ONCE
                    // ======================================

                    if (timelineOriginNanos == null) {

                        var timestampStatus = -1

                        // AudioRecord hardware timestamps are available
                        // on Android API 24 and newer.
                        if (
                            Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.N
                        ) {
                            timestampStatus = try {
                                recorder.getTimestamp(
                                    audioTimestamp,
                                    AudioTimestamp.TIMEBASE_BOOTTIME
                                )
                            } catch (exception: Exception) {
                                Log.w(
                                    "PocketService",
                                    "Microphone timestamp unavailable",
                                    exception
                                )
                                -1
                            }
                        }

                        timelineOriginNanos =
                            if (timestampStatus == 0) {

                                Log.i(
                                    "PocketService",
                                    "Using hardware microphone timestamp anchor."
                                )

                                audioTimestamp.nanoTime -
                                    (
                                        audioTimestamp.framePosition *
                                            1_000_000_000L /
                                            sampleRate
                                    )

                            } else {

                                Log.w(
                                    "PocketService",
                                    "Hardware timestamp unavailable. " +
                                        "Using fixed read-completion fallback."
                                )

                                readCompletedNanos -
                                    (
                                        totalFramesRead *
                                            1_000_000_000L /
                                            sampleRate
                                    )
                            }
                    }

                    // ======================================
                    // CALCULATE CONTIGUOUS CHUNK TIMESTAMP
                    // ======================================

                    val chunkEndTimestampNanos =
                        timelineOriginNanos!! +
                            (
                                totalFramesRead *
                                    1_000_000_000L /
                                    sampleRate
                            )

                    // Share the microphone samples with
                    // other components without opening
                    // another microphone connection.
                    AudioFrameHub.publish(
                        samples,
                        count,
                        chunkEndTimestampNanos
                    )

                    // ======================================
                    // CALCULATE MICROPHONE VOLUME
                    // ======================================

                    var sum = 0.0

                    for (i in 0 until count) {
                        val sample = samples[i].toDouble()
                        sum += sample * sample
                    }

                    val rms = sqrt(sum / count)

                    val currentSensitivity = getSensitivity()

                    val threshold =
                        3000.0 - ((currentSensitivity - 1) * 25.25)

                    val talkingDetected = rms > threshold

                    if (talkingDetected) {
                        aboveThresholdCount++
                        belowThresholdCount = 0
                    } else {
                        belowThresholdCount++
                        aboveThresholdCount = 0
                    }

                    if (
                        !isTalkingLocally &&
                        aboveThresholdCount >= 2
                    ) {
                        isTalkingLocally = true

                        sendUpdate(
                            talking = true,
                            running = true
                        )
                    }

                    if (
                        isTalkingLocally &&
                        belowThresholdCount >= 3
                    ) {
                        isTalkingLocally = false

                        sendUpdate(
                            talking = false,
                            running = true
                        )
                    }

                    // Send microphone level updates five times per second.
                    val now = SystemClock.elapsedRealtime()

                    if (now - lastLevelBroadcast >= 200) {

                        lastLevelBroadcast = now

                        sendUpdate(
                            level = rms.toInt(),
                            running = true
                        )
                    }
                }

                Thread.sleep(20)
            }

        } catch (exception: InterruptedException) {

            Thread.currentThread().interrupt()

        } catch (exception: Exception) {

            if (recording) {
                Log.e(
                    "PocketService",
                    "Microphone detection failed",
                    exception
                )

                microphoneError(
                    "Microphone error: ${exception.message}"
                )
            }

        } finally {

            try {
                if (
                    recorder?.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            } catch (_: Exception) {
            }

            recorder?.release()

            audioRecord = null
        }
    }

    // ==========================================
    // UPDATE SENSITIVITY
    // ==========================================

    private fun getSensitivity(): Int {

        return getSharedPreferences(
            "PocketPNGTube",
            MODE_PRIVATE
        ).getInt("sensitivity", 60).coerceIn(1, 100)
    }

    // ==========================================
    // SEND UPDATES TO MAIN ACTIVITY
    // ==========================================

    private fun sendUpdate(
        talking: Boolean? = null,
        level: Int? = null,
        running: Boolean,
        error: String? = null
    ) {

        val intent = Intent(ACTION_UPDATE).apply {

            setPackage(packageName)

            putExtra(EXTRA_RUNNING, running)

            if (talking != null) {
                putExtra(EXTRA_TALKING, talking)
            }

            if (level != null) {
                putExtra(EXTRA_LEVEL, level)
            }

            if (error != null) {
                putExtra(EXTRA_ERROR, error)
            }
        }

        sendBroadcast(intent)
    }

    // ==========================================
    // MICROPHONE ERROR
    // ==========================================

    private fun microphoneError(message: String) {

        recording = false
        isRunning = false

        sendUpdate(
            running = false,
            error = message
        )

        stopSelf()
    }

    // ==========================================
    // STOP SERVICE
    // ==========================================

    private fun stopMicrophoneService() {

        recording = false
        isRunning = false

        try {
            audioRecord?.let { recorder ->

                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            }
        } catch (_: Exception) {
        }

        sendUpdate(
            talking = false,
            running = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    // ==========================================
    // NOTIFICATION CHANNEL
    // ==========================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketPNGTube Microphone",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Notification shown while PocketPNGTube listens."
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    // ==========================================
    // FOREGROUND NOTIFICATION
    // ==========================================

    private fun createNotification(): Notification {

        val openAppIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(
            this,
            PocketService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketPNGTube is running")
            .setContentText("Listening for character expressions")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
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

        recording = false
        isRunning = false

        try {
            audioRecord?.let { recorder ->
                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
