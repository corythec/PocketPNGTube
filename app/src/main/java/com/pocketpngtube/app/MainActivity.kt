
package com.pocketpngtube.app

import android.Manifest
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pocketpngtube.app.databinding.ActivityMainBinding
import java.io.File
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val preferences by lazy {
        getSharedPreferences("PocketPNGTube", MODE_PRIVATE)
    }

    private val characterImages = mutableMapOf<String, Uri>()

    private var selectedImageState = "idle"

    private var listening = false
    private var sensitivity = 60
    private var bounceIntensity = 18

    // Recording gain values are percentages from 0 to 100.
    private var microphoneRecordingGain = 40
    private var playbackRecordingGain = 40

    private var bounceAnimator: ObjectAnimator? = null
    private var overlayBounceAnimator: ObjectAnimator? = null

    // ==========================================
    // MICROPHONE SERVICE RECEIVER
    // ==========================================

    private var receiverRegistered = false

    private val microphoneUpdateReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: android.content.Context?,
            intent: Intent?
        ) {

            if (intent?.action != PocketService.ACTION_UPDATE) return

            val running = intent.getBooleanExtra(
                PocketService.EXTRA_RUNNING,
                false
            )

            listening = running

            if (running) {

                binding.microphoneButton.text = "Stop Microphone"
                binding.statusText.text = "Status: Listening..."

            } else {

                stopAutomaticBlinking()

                isTalking = false
                isBlinking = false

                stopBounce()

                binding.microphoneButton.text = "Enable Microphone"
                binding.microphoneLevelText.text = "Microphone level: --"

                val idleUri = characterImages["idle"]

                if (idleUri != null) {
                    showPreview(idleUri, "idle")
                }
            }

            if (intent.hasExtra(PocketService.EXTRA_TALKING)) {

                val talking = intent.getBooleanExtra(
                    PocketService.EXTRA_TALKING,
                    false
                )

                if (running) {
                    updateExpressionFromMicrophone(talking)
                }
            }

            if (intent.hasExtra(PocketService.EXTRA_LEVEL)) {

                val level = intent.getIntExtra(
                    PocketService.EXTRA_LEVEL,
                    0
                )

                binding.microphoneLevelText.text =
                    "Microphone level: $level"
            }

            val error = intent.getStringExtra(
                PocketService.EXTRA_ERROR
            )

            if (error != null) {
                binding.statusText.text = error
            }
        }
    }

    private fun registerMicrophoneReceiver() {

        if (receiverRegistered) return

        val filter = IntentFilter(PocketService.ACTION_UPDATE)

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                microphoneUpdateReceiver,
                filter,
                RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(
                microphoneUpdateReceiver,
                filter
            )
        }

        receiverRegistered = true
    }

    private fun unregisterMicrophoneReceiver() {

        if (!receiverRegistered) return

        try {
            unregisterReceiver(microphoneUpdateReceiver)
        } catch (_: Exception) {
        }

        receiverRegistered = false
    }

    // ==========================================
    // SCREEN RECORDING
    // ==========================================

    private var recordingReceiverRegistered = false
    private var recordingRequestPending = false

    private var latestDiagnosticReport: String? = null

    private val screenRecordingLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            recordingRequestPending = false

            if (
                result.resultCode == RESULT_OK &&
                result.data != null
            ) {

                startScreenRecording(
                    result.resultCode,
                    result.data!!
                )

            } else {

                updateRecordingButtons(false)

                binding.recordingStatusText.text =
                    "Recording: Screen capture permission denied."
            }
        }

    private val screenRecordingReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: android.content.Context?,
            intent: Intent?
        ) {

            if (intent?.action != ScreenRecordingService.ACTION_STATUS) {
                return
            }

            val recording = intent.getBooleanExtra(
                ScreenRecordingService.EXTRA_RECORDING,
                false
            )

            val message = intent.getStringExtra(
                ScreenRecordingService.EXTRA_MESSAGE
            ) ?: "Recording status updated."

            updateRecordingButtons(recording)

            binding.recordingStatusText.text =
                "Recording: $message"

            if (message.contains("AUDIO DIAGNOSTICS")) {

                val diagnosticStart =
                    message.indexOf("AUDIO DIAGNOSTICS")

                latestDiagnosticReport =
                    message.substring(diagnosticStart)

                preferences.edit()
                    .putString(
                        "latestAudioDiagnostics",
                        latestDiagnosticReport
                    )
                    .apply()

                binding.copyDiagnosticsButton.isEnabled = true
                wavExportsReady = true
                updateWavExportButtons()
            }
        }
    }

    private fun registerScreenRecordingReceiver() {

        if (recordingReceiverRegistered) return

        val filter = IntentFilter(
            ScreenRecordingService.ACTION_STATUS
        )

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                screenRecordingReceiver,
                filter,
                RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(
                screenRecordingReceiver,
                filter
            )
        }

        recordingReceiverRegistered = true
    }

    private fun unregisterScreenRecordingReceiver() {

        if (!recordingReceiverRegistered) return

        try {
            unregisterReceiver(screenRecordingReceiver)
        } catch (_: Exception) {
        }

        recordingReceiverRegistered = false
    }

    // ==========================================
    // AUDIO RECORDING GAIN CONTROLS
    // ==========================================

    private fun setupAudioGainControls() {

        microphoneRecordingGain = preferences.getInt(
            "microphoneRecordingGain",
            40
        ).coerceIn(0, 100)

        playbackRecordingGain = preferences.getInt(
            "playbackRecordingGain",
            40
        ).coerceIn(0, 100)

        binding.microphoneGainSlider.progress =
            microphoneRecordingGain

        binding.playbackGainSlider.progress =
            playbackRecordingGain

        updateMicrophoneGainLabel()
        updatePlaybackGainLabel()

        binding.microphoneGainSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    microphoneRecordingGain =
                        progress.coerceIn(0, 100)

                    preferences.edit()
                        .putInt(
                            "microphoneRecordingGain",
                            microphoneRecordingGain
                        )
                        .apply()

                    updateMicrophoneGainLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        binding.playbackGainSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    playbackRecordingGain =
                        progress.coerceIn(0, 100)

                    preferences.edit()
                        .putInt(
                            "playbackRecordingGain",
                            playbackRecordingGain
                        )
                        .apply()

                    updatePlaybackGainLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
    }

    private fun updateMicrophoneGainLabel() {

        binding.microphoneGainValueText.text =
            "Microphone recording gain: $microphoneRecordingGain%"
    }

    private fun updatePlaybackGainLabel() {

        binding.playbackGainValueText.text =
            "Phone audio recording gain: $playbackRecordingGain%"
    }

    // ==========================================
    // SCREEN RECORDING CONTROLS
    // ==========================================

    private fun setupScreenRecordingControls() {

        latestDiagnosticReport = preferences.getString(
            "latestAudioDiagnostics",
            null
        )

        binding.copyDiagnosticsButton.isEnabled =
            !latestDiagnosticReport.isNullOrBlank()

        binding.copyDiagnosticsButton.setOnClickListener {
            copyDiagnosticsToClipboard()
        }

        binding.startRecordingButton.setOnClickListener {

            if (
                ScreenRecordingService.isRecording ||
                recordingRequestPending
            ) {
                return@setOnClickListener
            }

            requestScreenCapturePermission()
        }

        binding.stopRecordingButton.setOnClickListener {

            val stopIntent = Intent(
                this,
                ScreenRecordingService::class.java
            ).apply {
                action = ScreenRecordingService.ACTION_STOP
            }

            try {

                startService(stopIntent)

                binding.recordingStatusText.text =
                    "Recording: Stopping..."

                binding.stopRecordingButton.isEnabled = false

            } catch (exception: Exception) {

                binding.recordingStatusText.text =
                    "Recording: Couldn't stop recording: ${exception.message}"
            }
        }

        updateRecordingButtons(
            ScreenRecordingService.isRecording
        )

        binding.recordingStatusText.text =
            if (ScreenRecordingService.isRecording) {
                "Recording: Active"
            } else {
                "Recording: Not started"
            }
    }

   
private fun copyDiagnosticsToClipboard() {

    val report = latestDiagnosticReport

    if (report.isNullOrBlank()) {

        Toast.makeText(
            this,
            "No diagnostic report available yet.",
            Toast.LENGTH_SHORT
        ).show()

        return
    }

    try {

        // Analyze the newest WAV files locally.
        val musicDirectory = getExternalFilesDir(
            Environment.DIRECTORY_MUSIC
        )

        val wavAnalysis =
            WavAudioAnalyzer.analyzeLatest(musicDirectory)

        val completeReport =
            report + "\n\n" + wavAnalysis

        val clipboard = getSystemService(
            CLIPBOARD_SERVICE
        ) as ClipboardManager

        val clip = ClipData.newPlainText(
            "PocketPNGTube Complete Audio Diagnostics",
            completeReport
        )

        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            this,
            "Audio diagnostics and WAV analysis copied!",
            Toast.LENGTH_LONG
        ).show()

    } catch (exception: Exception) {

        Toast.makeText(
            this,
            "Couldn't analyze audio: ${exception.message}",
            Toast.LENGTH_LONG
        ).show()
    }
}


    // ==========================================
    // WAV DIAGNOSTIC EXPORT
    // ==========================================

    private var wavExportsReady = false

    private fun setupWavExportButtons() {

        wavExportsReady =
            !latestDiagnosticReport.isNullOrBlank()

        binding.exportRawWavButton.setOnClickListener {
            exportWavDiagnostic(
                "PocketPNGTube_RawPlayback_",
                "Pre-Mixer WAV"
            )
        }

        binding.exportPostMixerWavButton.setOnClickListener {
            exportWavDiagnostic(
                "PocketPNGTube_PostMixer_",
                "Post-Mixer WAV"
            )
        }

        updateWavExportButtons()
    }

    // Each WAV export button is enabled independently.
    private fun updateWavExportButtons() {

        val canExport =
            wavExportsReady &&
            !ScreenRecordingService.isRecording

        binding.exportRawWavButton.isEnabled =
            canExport &&
            findNewestDiagnosticWav(
                "PocketPNGTube_RawPlayback_"
            ) != null

        binding.exportPostMixerWavButton.isEnabled =
            canExport &&
            findNewestDiagnosticWav(
                "PocketPNGTube_PostMixer_"
            ) != null
    }

    private fun findNewestDiagnosticWav(
        filePrefix: String
    ): File? {

        val musicDirectory = getExternalFilesDir(
            Environment.DIRECTORY_MUSIC
        ) ?: return null

        val diagnosticsDirectory = File(
            musicDirectory,
            "Diagnostics"
        )

        if (!diagnosticsDirectory.exists()) {
            return null
        }

        return diagnosticsDirectory
            .listFiles()
            ?.filter { file ->

                file.isFile &&
                    file.name.startsWith(filePrefix) &&
                    file.extension.equals(
                        "wav",
                        ignoreCase = true
                    ) &&
                    file.length() > 44
            }
            ?.maxByOrNull {
                it.lastModified()
            }
    }

    private fun exportWavDiagnostic(
        filePrefix: String,
        diagnosticName: String
    ) {

        if (
            ScreenRecordingService.isRecording ||
            !wavExportsReady
        ) {

            Toast.makeText(
                this,
                "Wait until recording has completely finished.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        try {

            val wavFile =
                findNewestDiagnosticWav(filePrefix)

            if (wavFile == null) {

                Toast.makeText(
                    this,
                    "$diagnosticName not found. Record a test first.",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                wavFile
            )

            val shareIntent = Intent(
                Intent.ACTION_SEND
            ).apply {

                type = "audio/wav"

                putExtra(
                    Intent.EXTRA_STREAM,
                    contentUri
                )

                clipData = ClipData.newUri(
                    contentResolver,
                    wavFile.name,
                    contentUri
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            startActivity(
                Intent.createChooser(
                    shareIntent,
                    "Share $diagnosticName"
                )
            )

        } catch (exception: Exception) {

            Toast.makeText(
                this,
                "Couldn't export $diagnosticName: ${exception.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestScreenCapturePermission() {

        try {

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            recordingRequestPending = true

            binding.startRecordingButton.isEnabled = false

            binding.recordingStatusText.text =
                "Recording: Waiting for screen capture permission..."

            latestDiagnosticReport = null

            wavExportsReady = false
            updateWavExportButtons()

            binding.copyDiagnosticsButton.isEnabled = false

            screenRecordingLauncher.launch(
                projectionManager.createScreenCaptureIntent()
            )

        } catch (exception: Exception) {

            recordingRequestPending = false

            updateRecordingButtons(false)

            binding.recordingStatusText.text =
                "Recording: Couldn't request permission: ${exception.message}"
        }
    }

    private fun startScreenRecording(
        resultCode: Int,
        resultData: Intent
    ) {

        val serviceIntent = Intent(
            this,
            ScreenRecordingService::class.java
        ).apply {

            action = ScreenRecordingService.ACTION_START

            putExtra(
                ScreenRecordingService.EXTRA_RESULT_CODE,
                resultCode
            )

            putExtra(
                ScreenRecordingService.EXTRA_RESULT_DATA,
                resultData
            )

            // Pass the saved gain settings to the service.
            putExtra(
                ScreenRecordingService.EXTRA_MICROPHONE_GAIN,
                microphoneRecordingGain / 100f
            )

            putExtra(
                ScreenRecordingService.EXTRA_PLAYBACK_GAIN,
                playbackRecordingGain / 100f
            )
        }

        try {

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            binding.recordingStatusText.text =
                "Recording: Starting screen capture..."

            binding.startRecordingButton.isEnabled = false
            binding.stopRecordingButton.isEnabled = true

        } catch (exception: Exception) {

            updateRecordingButtons(false)

            binding.recordingStatusText.text =
                "Recording: Couldn't start: ${exception.message}"
        }
    }

    private fun updateRecordingButtons(recording: Boolean) {

        binding.startRecordingButton.isEnabled =
            !recording && !recordingRequestPending

        binding.stopRecordingButton.isEnabled =
            recording || recordingRequestPending
    }

    // ==========================================
    // FLOATING OVERLAY
    // ==========================================

    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null
    private var overlayImageView: ImageView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var pendingOverlayStart = false

    private val defaultOverlayWidth = 200
    private val defaultOverlayHeight = 240

    private val minimumOverlayWidth = 100
    private val maximumOverlayWidth = 600

    // ==========================================
    // AUTOMATIC BLINKING
    // ==========================================

    private val blinkHandler = Handler(Looper.getMainLooper())

    private var isTalking = false
    private var isBlinking = false

    private val blinkRunnable = Runnable {
        performBlink()
    }

    private val finishBlinkRunnable = Runnable {

        if (listening) {

            isBlinking = false

            displayCurrentExpression()

            scheduleNextBlink()
        }
    }

    // ==========================================
    // IMAGE PICKER
    // ==========================================

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                try {

                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    characterImages[selectedImageState] = uri

                    preferences.edit()
                        .putString(selectedImageState, uri.toString())
                        .apply()

                    binding.statusText.text =
                        "Saved ${getStateName(selectedImageState)} image!"

                } catch (exception: Exception) {

                    binding.statusText.text =
                        "Couldn't save that image. Please try again."
                }
            }
        }

    // ==========================================
    // MICROPHONE PERMISSION
    // ==========================================

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startMicrophone()
            } else {

                binding.statusText.text =
                    "Microphone permission was denied."
            }
        }

    // ==========================================
    // ACTIVITY SETUP
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->

            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )

            insets
        }

        setupImportButtons()
        setupPreviewButtons()
        setupMicrophoneControls()
        setupBounceControls()
        setupAudioGainControls()
        setupOverlayControls()
        setupScreenRecordingControls()
        setupWavExportButtons()

        loadSavedImages()

        registerMicrophoneReceiver()
        registerScreenRecordingReceiver()
    }

    override fun onStart() {
        super.onStart()

        if (PocketService.isRunning) {

            listening = true

            binding.microphoneButton.text = "Stop Microphone"
            binding.statusText.text = "Status: Listening..."

            startAutomaticBlinking()

        } else {

            listening = false

            binding.microphoneButton.text = "Enable Microphone"
        }

        updateRecordingButtons(
            ScreenRecordingService.isRecording
        )
    }

    override fun onResume() {
        super.onResume()

        if (pendingOverlayStart && Settings.canDrawOverlays(this)) {

            pendingOverlayStart = false

            startFloatingOverlay()
        }
    }

    // IMPORTANT:
    // We deliberately do not unregister the receivers in onStop().
    // They must continue receiving updates while minimized.

    // ==========================================
    // IMAGE IMPORTING
    // ==========================================

    private fun setupImportButtons() {

        binding.importIdleButton.setOnClickListener {
            openImagePicker("idle")
        }

        binding.importTalkingButton.setOnClickListener {
            openImagePicker("talking")
        }

        binding.importBlinkButton.setOnClickListener {
            openImagePicker("blinking")
        }

        binding.importTalkingBlinkButton.setOnClickListener {
            openImagePicker("talking_blinking")
        }
    }

    private fun openImagePicker(state: String) {

        selectedImageState = state

        imagePicker.launch(arrayOf("image/png", "image/*"))
    }

    // ==========================================
    // MANUAL PREVIEW
    // ==========================================

    private fun setupPreviewButtons() {

        binding.previewIdleButton.setOnClickListener {

            stopAutomaticBlinking()

            isTalking = false
            isBlinking = false

            stopBounce()

            previewState("idle")
        }

        binding.previewTalkingButton.setOnClickListener {

            stopAutomaticBlinking()

            isTalking = true
            isBlinking = false

            previewState("talking")

            startBounce()
        }

        binding.previewBlinkButton.setOnClickListener {

            stopAutomaticBlinking()

            isTalking = false
            isBlinking = false

            stopBounce()

            previewState("blinking")
        }

        binding.previewTalkingBlinkButton.setOnClickListener {

            stopAutomaticBlinking()

            isTalking = true
            isBlinking = true

            previewState("talking_blinking")

            startBounce()
        }
    }

    private fun previewState(state: String) {

        val uri = characterImages[state]

        if (uri == null) {

            stopBounce()

            binding.statusText.text =
                "${getStateName(state)} image hasn't been imported yet."

            return
        }

        showPreview(uri, state)
    }

    private fun showPreview(uri: Uri, state: String) {

        try {

            binding.characterPreview.setImageURI(uri)

            binding.characterPreview.visibility = View.VISIBLE
            binding.previewPlaceholder.visibility = View.GONE

            binding.selectedExpressionText.text =
                "Previewing: ${getStateName(state)}"

            binding.statusText.text =
                "Status: ${getStateName(state)} preview loaded"

            updateOverlayImage(uri)

        } catch (exception: Exception) {

            stopBounce()

            binding.characterPreview.visibility = View.GONE

            binding.previewPlaceholder.text =
                "Unable to display this image. Please import it again."

            binding.previewPlaceholder.visibility = View.VISIBLE

            binding.statusText.text =
                "Error loading ${getStateName(state)} image."
        }
    }

    // ==========================================
    // OVERLAY CONTROLS
    // ==========================================

    private fun setupOverlayControls() {

        binding.startOverlayButton.setOnClickListener {

            if (overlayView != null) {

                binding.statusText.text =
                    "Floating character is already running."

                return@setOnClickListener
            }

            if (characterImages["idle"] == null) {

                binding.statusText.text =
                    "Import an Idle image before starting the overlay."

                return@setOnClickListener
            }

            requestOverlayPermissionOrStart()
        }
    }

    private fun requestOverlayPermissionOrStart() {

        if (Settings.canDrawOverlays(this)) {

            startFloatingOverlay()

        } else {

            pendingOverlayStart = true

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            startActivity(intent)

            binding.statusText.text =
                "Please allow PocketPNGTube to display over other apps."
        }
    }

    private fun startFloatingOverlay() {

        if (overlayView != null) return

        if (!Settings.canDrawOverlays(this)) {

            binding.statusText.text =
                "Overlay permission is required."

            return
        }

        val idleUri = characterImages["idle"]

        if (idleUri == null) {

            binding.statusText.text =
                "Please import an Idle image first."

            return
        }

        try {

            val view = LayoutInflater.from(this)
                .inflate(R.layout.overlay_character, null)

            val imageView =
                view.findViewById<ImageView>(R.id.overlayCharacterImage)

            val closeButton =
                view.findViewById<TextView>(R.id.overlayCloseButton)

            val resizeHandle =
                view.findViewById<View>(R.id.overlayResizeHandle)

            val initialState = when {

                isBlinking && isTalking -> "talking_blinking"
                isBlinking -> "blinking"
                isTalking -> "talking"
                else -> "idle"
            }

            imageView.setImageURI(
                characterImages[initialState] ?: idleUri
            )

            val savedWidth = preferences.getInt(
                "overlayWidth",
                dpToPixels(defaultOverlayWidth)
            ).coerceIn(
                dpToPixels(minimumOverlayWidth),
                dpToPixels(maximumOverlayWidth)
            )

            val savedHeight = preferences.getInt(
                "overlayHeight",
                (savedWidth * 1.2f).toInt()
            )

            val savedX = preferences.getInt(
                "overlayX",
                dpToPixels(20)
            )

            val savedY = preferences.getInt(
                "overlayY",
                dpToPixels(100)
            )

            val params = WindowManager.LayoutParams(
                savedWidth,
                savedHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity = Gravity.TOP or Gravity.START

                x = savedX
                y = savedY
            }

            windowManager.addView(view, params)

            overlayView = view
            overlayImageView = imageView
            overlayParams = params

            closeButton.setOnClickListener {
                stopFloatingOverlay()
            }

            setupOverlayDragging(imageView)
            setupOverlayResizing(resizeHandle)

            if (isTalking && bounceIntensity > 0) {

                val bouncePixels = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    bounceIntensity.toFloat(),
                    resources.displayMetrics
                )

                startOverlayBounce(bouncePixels)
            }

            binding.statusText.text =
                "Floating character is running!"

        } catch (exception: Exception) {

            overlayView = null
            overlayImageView = null
            overlayParams = null

            binding.statusText.text =
                "Couldn't start overlay: ${exception.message}"
        }
    }

    // ==========================================
    // SAVE OVERLAY SETTINGS
    // ==========================================

    private fun saveOverlaySettings() {

        val params = overlayParams ?: return

        preferences.edit()
            .putInt("overlayWidth", params.width)
            .putInt("overlayHeight", params.height)
            .putInt("overlayX", params.x)
            .putInt("overlayY", params.y)
            .apply()
    }

    // ==========================================
    // OVERLAY DRAGGING
    // ==========================================

    private fun setupOverlayDragging(dragView: View) {

        var initialX = 0
        var initialY = 0

        var initialTouchX = 0f
        var initialTouchY = 0f

        dragView.setOnTouchListener { _, event ->

            val params = overlayParams
                ?: return@setOnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    initialX = params.x
                    initialY = params.y

                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    params.x = initialX +
                        (event.rawX - initialTouchX).toInt()

                    params.y = initialY +
                        (event.rawY - initialTouchY).toInt()

                    try {

                        overlayView?.let {
                            windowManager.updateViewLayout(it, params)
                        }

                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    saveOverlaySettings()

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    saveOverlaySettings()

                    true
                }

                else -> false
            }
        }
    }

    // ==========================================
    // OVERLAY RESIZING
    // ==========================================

    private fun setupOverlayResizing(resizeHandle: View) {

        var initialWidth = 0
        var initialTouchX = 0f

        resizeHandle.setOnTouchListener { _, event ->

            val params = overlayParams
                ?: return@setOnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    initialWidth = params.width
                    initialTouchX = event.rawX

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val movement =
                        (event.rawX - initialTouchX).toInt()

                    val minimumWidth = dpToPixels(minimumOverlayWidth)
                    val maximumWidth = dpToPixels(maximumOverlayWidth)

                    val newWidth = (initialWidth + movement)
                        .coerceIn(minimumWidth, maximumWidth)

                    val newHeight = (newWidth * 1.2f).toInt()

                    params.width = newWidth
                    params.height = newHeight

                    try {

                        overlayView?.let {
                            windowManager.updateViewLayout(it, params)
                        }

                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    saveOverlaySettings()

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    saveOverlaySettings()

                    true
                }

                else -> false
            }
        }
    }

    // ==========================================
    // UPDATE OVERLAY IMAGE
    // ==========================================

    private fun updateOverlayImage(uri: Uri) {

        try {
            overlayImageView?.setImageURI(uri)
        } catch (_: Exception) {
        }
    }

    // ==========================================
    // STOP OVERLAY
    // ==========================================

    private fun stopFloatingOverlay() {

        saveOverlaySettings()

        overlayBounceAnimator?.cancel()
        overlayBounceAnimator = null

        overlayImageView?.translationY = 0f

        val view = overlayView

        if (view != null) {

            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayImageView = null
        overlayParams = null

        if (::binding.isInitialized) {

            binding.statusText.text =
                "Floating character closed."
        }
    }

    private fun dpToPixels(dp: Int): Int {

        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    // ==========================================
    // BOUNCE INTENSITY
    // ==========================================

    private fun setupBounceControls() {

        bounceIntensity = preferences.getInt("bounceIntensity", 18)

        binding.bounceIntensitySlider.progress = bounceIntensity

        updateBounceLabel()

        binding.bounceIntensitySlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    bounceIntensity = progress

                    preferences.edit()
                        .putInt("bounceIntensity", bounceIntensity)
                        .apply()

                    updateBounceLabel()

                    if (
                        bounceAnimator?.isRunning == true ||
                        overlayBounceAnimator?.isRunning == true
                    ) {

                        stopBounce()

                        if (isTalking) {
                            startBounce()
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
    }

    private fun updateBounceLabel() {

        binding.bounceIntensityValueText.text =
            "Bounce intensity: $bounceIntensity"
    }

    // ==========================================
    // CHARACTER BOUNCE ANIMATION
    // ==========================================

    private fun startBounce() {

        if (bounceIntensity <= 0) {

            stopBounce()

            return
        }

        val bouncePixels = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            bounceIntensity.toFloat(),
            resources.displayMetrics
        )

        if (bounceAnimator?.isRunning != true) {

            bounceAnimator?.cancel()

            bounceAnimator = ObjectAnimator.ofFloat(
                binding.characterPreview,
                View.TRANSLATION_Y,
                0f,
                -bouncePixels,
                0f
            ).apply {

                duration = 450

                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART

                interpolator = AccelerateDecelerateInterpolator()

                start()
            }
        }

        startOverlayBounce(bouncePixels)
    }

    private fun startOverlayBounce(bouncePixels: Float) {

        val imageView = overlayImageView ?: return

        if (bouncePixels <= 0f) return

        if (overlayBounceAnimator?.isRunning == true) return

        overlayBounceAnimator?.cancel()

        overlayBounceAnimator = ObjectAnimator.ofFloat(
            imageView,
            View.TRANSLATION_Y,
            0f,
            -bouncePixels,
            0f
        ).apply {

            duration = 450

            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART

            interpolator = AccelerateDecelerateInterpolator()

            start()
        }
    }

    private fun stopBounce() {

        bounceAnimator?.cancel()
        bounceAnimator = null

        overlayBounceAnimator?.cancel()
        overlayBounceAnimator = null

        if (::binding.isInitialized) {
            binding.characterPreview.translationY = 0f
        }

        overlayImageView?.translationY = 0f
    }

    // ==========================================
    // AUTOMATIC BLINKING ENGINE
    // ==========================================

    private fun startAutomaticBlinking() {

        stopAutomaticBlinking()

        isBlinking = false

        scheduleNextBlink()
    }

    private fun stopAutomaticBlinking() {

        blinkHandler.removeCallbacks(blinkRunnable)
        blinkHandler.removeCallbacks(finishBlinkRunnable)

        isBlinking = false
    }

    private fun scheduleNextBlink() {

        if (!listening) return

        blinkHandler.removeCallbacks(blinkRunnable)

        val delay = Random.nextLong(2500L, 6001L)

        blinkHandler.postDelayed(blinkRunnable, delay)
    }

    private fun performBlink() {

        if (!listening) return

        val blinkState = if (isTalking) {
            "talking_blinking"
        } else {
            "blinking"
        }

        if (characterImages[blinkState] == null) {

            scheduleNextBlink()

            return
        }

        isBlinking = true

        displayCurrentExpression()

        blinkHandler.removeCallbacks(finishBlinkRunnable)

        blinkHandler.postDelayed(
            finishBlinkRunnable,
            140L
        )
    }

    private fun displayCurrentExpression() {

        val state = when {

            isBlinking && isTalking -> "talking_blinking"
            isBlinking -> "blinking"
            isTalking -> "talking"
            else -> "idle"
        }

        val uri = characterImages[state]
            ?: characterImages[
                if (isTalking) "talking" else "idle"
            ]

        if (uri != null) {

            try {

                binding.characterPreview.setImageURI(uri)

                binding.characterPreview.visibility = View.VISIBLE
                binding.previewPlaceholder.visibility = View.GONE

                binding.selectedExpressionText.text =
                    "Previewing: ${getStateName(state)}"

                updateOverlayImage(uri)

            } catch (exception: Exception) {

                binding.statusText.text =
                    "Error displaying ${getStateName(state)} image."
            }
        }
    }

    // ==========================================
    // MICROPHONE CONTROLS
    // ==========================================

    private fun setupMicrophoneControls() {

        sensitivity = preferences.getInt("sensitivity", 60)

        binding.sensitivitySlider.progress = sensitivity

        updateSensitivityLabel()

        binding.sensitivitySlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    sensitivity = progress.coerceAtLeast(1)

                    preferences.edit()
                        .putInt("sensitivity", sensitivity)
                        .apply()

                    updateSensitivityLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        binding.microphoneButton.setOnClickListener {

            if (listening) {

                stopMicrophone()

            } else {

                checkMicrophonePermission()
            }
        }
    }

    private fun updateSensitivityLabel() {

        binding.sensitivityValueText.text =
            "Sensitivity: $sensitivity%"
    }

    private fun checkMicrophonePermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            startMicrophone()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    // ==========================================
    // START MICROPHONE SERVICE
    // ==========================================

    private fun startMicrophone() {

        if (listening || PocketService.isRunning) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            binding.statusText.text =
                "Microphone permission is required."

            return
        }

        isTalking = false
        isBlinking = false

        listening = true

        binding.microphoneButton.text = "Stop Microphone"

        binding.statusText.text = "Status: Starting microphone service..."

        binding.microphoneLevelText.text =
            "Microphone level: Starting..."

        startAutomaticBlinking()

        val serviceIntent = Intent(
            this,
            PocketService::class.java
        ).apply {
            action = PocketService.ACTION_START
        }

        try {

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

        } catch (exception: Exception) {

            listening = false

            stopAutomaticBlinking()
            stopBounce()

            binding.microphoneButton.text = "Enable Microphone"

            binding.statusText.text =
                "Couldn't start microphone service: ${exception.message}"
        }
    }

    // ==========================================
    // STOP MICROPHONE SERVICE
    // ==========================================

    private fun stopMicrophone() {

        listening = false

        stopAutomaticBlinking()

        isTalking = false
        isBlinking = false

        stopBounce()

        val serviceIntent = Intent(
            this,
            PocketService::class.java
        ).apply {
            action = PocketService.ACTION_STOP
        }

        try {
            startService(serviceIntent)
        } catch (_: Exception) {
        }

        binding.microphoneButton.text = "Enable Microphone"

        binding.statusText.text =
            "Status: Microphone stopped"

        binding.microphoneLevelText.text =
            "Microphone level: --"

        val idleUri = characterImages["idle"]

        if (idleUri != null) {
            showPreview(idleUri, "idle")
        }
    }

    // ==========================================
    // MICROPHONE EXPRESSION UPDATES
    // ==========================================

    private fun updateExpressionFromMicrophone(talking: Boolean) {

        if (!listening) return

        isTalking = talking

        displayCurrentExpression()

        if (talking) {
            startBounce()
        } else {
            stopBounce()
        }
    }

    // ==========================================
    // RESTORE SAVED IMAGES
    // ==========================================

    private fun loadSavedImages() {

        val states = listOf(
            "idle",
            "talking",
            "blinking",
            "talking_blinking"
        )

        for (state in states) {

            val savedUri = preferences.getString(state, null)

            if (savedUri != null) {

                try {

                    val uri = Uri.parse(savedUri)

                    val stream = contentResolver.openInputStream(uri)
                        ?: throw Exception("Image unavailable")

                    stream.close()

                    characterImages[state] = uri

                } catch (exception: Exception) {

                    preferences.edit()
                        .remove(state)
                        .apply()
                }
            }
        }

        val idleUri = characterImages["idle"]

        if (idleUri != null) {

            showPreview(idleUri, "idle")

        } else {

            binding.statusText.text =
                "Status: Waiting for character images"
        }
    }

    // ==========================================
    // HELPER FUNCTIONS
    // ==========================================

    private fun getStateName(state: String): String {

        return when (state) {

            "idle" -> "Idle"
            "talking" -> "Talking"
            "blinking" -> "Blinking"
            "talking_blinking" -> "Talking + Blinking"

            else -> state
        }
    }

    // ==========================================
    // CLEANUP
    // ==========================================

    override fun onDestroy() {

        stopAutomaticBlinking()

        stopBounce()

        stopFloatingOverlay()

        unregisterMicrophoneReceiver()
        unregisterScreenRecordingReceiver()

        // Do not stop PocketService here.
        // The microphone service must survive Activity destruction.

        super.onDestroy()
    }
}
