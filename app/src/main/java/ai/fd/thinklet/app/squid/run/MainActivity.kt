package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.app.squid.run.databinding.ActivityMainBinding
import ai.fd.thinklet.app.squid.run.databinding.PreviewBinding
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationEffect.DEFAULT_AMPLITUDE
import android.os.Vibrator
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ai.fd.thinklet.sdk.maintenance.power.PowerController
import ai.fd.thinklet.sdk.maintenance.launcher.Extension
import android.util.Log

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val statusReportingManager by lazy {
        StatusReportingManager(
            context = this,
            streamUrl = viewModel.streamUrl
        )
    }

    private val binding: ActivityMainBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val vibrator: Vibrator by lazy(LazyThreadSafetyMode.NONE) {
        checkNotNull(getSystemService())
    }

    private val permissionHelper: PermissionHelper by lazy(LazyThreadSafetyMode.NONE) {
        PermissionHelper(this)
    }

    private var previewBinding: PreviewBinding? = null
    private var isPreviewInflated = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var longPressRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        longPressRunnable = Runnable {
            handlePowerKeyPress()
        }

        try {
            val ext = Extension()
            val (pkg, cls) = ext.configure()
            val myPkg = this.packageName
            val myCls = this::class.java.name
            if (pkg != myPkg || cls != myCls) {
                ext.configure(myPkg, myCls)
            }
            if (!ext.isAutoLaunchMode()) {
                ext.enableAutoLaunchMode()
            }
        } catch (e: Exception) {
            Log.e("ThinkletExtension", "Failed to configure auto-launch", e)
        }

        vibrator.vibrate(VibrationEffect.createOneShot(200, DEFAULT_AMPLITUDE))
        viewModel.ttsManager.speakApplicationPrepared()

        if (viewModel.streamKey.value.isNullOrBlank()) {
            viewModel.updateStreamKey(statusReportingManager.deviceId)
        }

        binding.streamUrl.text = viewModel.streamUrl
        lifecycleScope.launch {
            viewModel.streamKey.collectLatest { streamKey ->
                binding.streamKey.setText(streamKey)
                statusReportingManager.updateStreamKey(streamKey)
            }
        }
        binding.dimension.text =
            getString(R.string.dimension_text, viewModel.width, viewModel.height)
        binding.videoBitrate.text = (viewModel.videoBitrateBps / 1024).toString()
        binding.samplingRate.text = (viewModel.audioSampleRateHz / 1000f).toString()
        binding.audioBitrate.text = (viewModel.audioBitrateBps / 1024).toString()
        binding.audioChannel.text = viewModel.audioChannel.argumentValue
        binding.echoCanceler.text = viewModel.isEchoCancelerEnabled.toString()
        binding.micMode.text = viewModel.micMode.argumentValue
        binding.permissionGranted.text = permissionHelper.areAllPermissionsGranted().toString()

        binding.buttonSaveStreamKey.setOnClickListener {
            val newStreamKey = binding.streamKey.text.toString()
            viewModel.updateStreamKey(newStreamKey)
        }
        binding.buttonSaveStreamKey.isEnabled = false

        binding.streamKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                binding.buttonSaveStreamKey.isEnabled = s.toString() != viewModel.streamKey.value
            }
        })

        // If the preview is shown by default, initialize and display it.
        if (viewModel.shouldShowPreview) {
            inflateAndSetupPreview()
            // Show the preview (this will trigger surfaceChanged and start the camera).
            showPreview()
        }

        lifecycleScope.launch {
            viewModel.connectionStatus
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.connectionStatus.text = it.name
                }
        }
        lifecycleScope.launch {
            viewModel.isPrepared
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streamPrepared.text = it.toString()
                }
        }
        lifecycleScope.launch {
            viewModel.isReadyForStreaming
                .flowWithLifecycle(lifecycle)
                .collect {
                    statusReportingManager.updateStreamingReadyStatus(it)
                }
        }
        lifecycleScope.launch {
            viewModel.isStreaming
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streaming.text = it.toString()
                    statusReportingManager.updateStreamingStatus(it)
                }
        }
        lifecycleScope.launch {
            viewModel.latestStreamingEventList
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streamingEvents.text = it.asReversed().joinToString("\n")
                }
        }
        lifecycleScope.launch {
            viewModel.isAudioMuted
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.audioMuted.text = it.toString()
                }
        }
        lifecycleScope.launch {
            viewModel.isRecording
                .flowWithLifecycle(lifecycle)
                .collect { isRecording ->
                    binding.recording.text = isRecording.toString()
                    // Update button text.
                    binding.buttonRecord.text = if (isRecording) {
                        getString(R.string.button_stop_recording)
                    } else {
                        getString(R.string.button_start_recording)
                    }
                    // Update recording status - duration now handled by frontend
                    statusReportingManager.updateRecordingStatus(
                        isRecording,
                        0L  // Duration always 0, frontend handles timing
                    )
                }
        }
        // Recording duration monitoring removed - frontend now handles timing
        // lifecycleScope.launch {
        //     viewModel.recordingDurationMs
        //         .flowWithLifecycle(lifecycle)
        //         .collect { durationMs ->
        //             statusReportingManager.updateRecordingStatus(
        //                 viewModel.isRecording.value,
        //                 durationMs
        //             )
        //         }
        // }
        lifecycleScope.launch {
            viewModel.isPreviewActive
                .flowWithLifecycle(lifecycle)
                .collect { isActive ->
                    binding.previewActive.text = isActive.toString()
                }
        }

        // Set the record button click listener.
        binding.buttonRecord.setOnClickListener {
            toggleRecording()
        }

        // Set the preview toggle button click listener.
        binding.buttonTogglePreview.setOnClickListener {
            togglePreview()
        }

        statusReportingManager.start()
        LocalBroadcastManager.getInstance(this).registerReceiver(streamingControlReceiver, IntentFilter("streaming-control"))
    }

    override fun onDestroy() {
        super.onDestroy()
        statusReportingManager.stop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(streamingControlReceiver)
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
        maybeNotifyLaunchErrors()
        viewModel.activityOnResume()
    }

    override fun onPause() {
        viewModel.activityOnPause()
        super.onPause()
    }

    private fun maybeNotifyLaunchErrors() {
        if (!permissionHelper.areAllPermissionsGranted()) {
            vibrator.vibrate(createStaccatoVibrationEffect(2))
            return
        }
        if (viewModel.streamUrl == null || viewModel.streamKey.value == null) {
            vibrator.vibrate(createStaccatoVibrationEffect(3))
            return
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                // The original physical button trigger logic is retained,
                // but now it calls the unified `toggleRecording` method.
                toggleRecording()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                toggleAudioMute()
                return true
            }
            KeyEvent.KEYCODE_POWER -> {
                return handlePowerKeyDown(event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_POWER -> handlePowerKeyUp()
            KeyEvent.KEYCODE_VOLUME_UP -> handleVolumeUpKeyPress()
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeDownKeyUp()
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun handlePowerKeyDown(event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount == 0) {
            handler.postDelayed(longPressRunnable, 2000)
        }
        return true
    }

    private fun handlePowerKeyUp(): Boolean {
        handler.removeCallbacks(longPressRunnable)
        return true
    }

    private fun handlePowerKeyPress() {
        Log.d("PowerKey", "Long press on power button detected. Testing vibration.")
        // 1. 震动反馈
        val timings = longArrayOf(0, 200, 200, 200)
        val amplitudes = intArrayOf(0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))

        // 2. TTS 语音播报
        viewModel.ttsManager.speakPowerDown()
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Log.e("PowerKey", "TTS wait interrupted", e)
        }

        // 3. 执行关机
        PowerController().shutdown(this, wait = 1000 /* max wait 1s */)
    }

    private fun handleVolumeUpKeyPress(): Boolean {
        val statusMessage = viewModel.ttsManager.getBatteryAndNetworkStatusMessage()
        viewModel.showToast(statusMessage)
        viewModel.ttsManager.speakBatteryAndNetworkStatus()
        return true
    }

    private fun handleVolumeDownKeyUp(): Boolean {
        return true
    }

    private val streamingControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("action")) {
                "start" -> {
                    if (!viewModel.isStreaming.value) {
                        viewModel.maybeStartStreaming { isStreamingStarted ->
                            if (!isStreamingStarted) {
                                vibrator.vibrate(createStaccatoVibrationEffect(2))
                            }
                        }
                    }
                }
                "stop" -> {
                    if (viewModel.isStreaming.value) {
                        viewModel.stopStreaming()
                    }
                }
            }
        }
    }

    private fun toggleAudioMute() {
        if (viewModel.isAudioMuted.value) {
            viewModel.unMuteAudio()
            vibrator.vibrate(createStaccatoVibrationEffect(1))
        } else {
            viewModel.muteAudio()
            vibrator.vibrate(createStaccatoVibrationEffect(2))
        }
    }

    private fun toggleRecording() {
        if (viewModel.isRecording.value) {
            viewModel.stopRecording()
            vibrator.vibrate(createStaccatoVibrationEffect(2))
        } else {
            viewModel.startRecording { isRecordingStarted ->
                if (isRecordingStarted) {
                    vibrator.vibrate(createStaccatoVibrationEffect(1))
                } else {
                    vibrator.vibrate(createStaccatoVibrationEffect(3))
                }
            }
        }
    }

    private fun togglePreview() {
        if (viewModel.isPreviewActive.value) {
            // Stop the preview and hide the window.
            hidePreview()
            vibrator.vibrate(createStaccatoVibrationEffect(1))
        } else {
            // Show the preview window and start the preview.
            showPreview()
            vibrator.vibrate(createStaccatoVibrationEffect(1))
        }
    }

    private fun showPreview() {
        // If not yet inflated, inflate it first.
        if (!isPreviewInflated) {
            inflateAndSetupPreview()
        } else {
            // It's already inflated, just show it and start the preview.
            previewBinding?.preview?.visibility = android.view.View.VISIBLE
            // Restart the preview (if the Surface already exists).
            previewBinding?.preview?.holder?.let { holder ->
                holder.surface?.let { surface ->
                    if (surface.isValid) {
                        val surfaceFrame = holder.surfaceFrame
                        viewModel.startPreview(surface, surfaceFrame.width(), surfaceFrame.height())
                    }
                }
            }
        }
    }

    private fun hidePreview() {
        // Stop the preview first, then hide the Surface.
        viewModel.stopPreview()
        // Use postDelayed to give the rendering thread enough time to stop.
        // 100ms is usually sufficient for the GL thread to stop rendering.
        previewBinding?.preview?.postDelayed({
            previewBinding?.preview?.visibility = android.view.View.GONE
        }, 100)
    }

    private fun inflateAndSetupPreview() {
        if (isPreviewInflated) return
        
        val localPreviewBinding = PreviewBinding.bind(binding.previewStub.inflate())
        previewBinding = localPreviewBinding
        isPreviewInflated = true
        
        localPreviewBinding.preview.updateLayoutParams<ConstraintLayout.LayoutParams> {
            dimensionRatio = "${viewModel.width}:${viewModel.height}"
        }
        
        localPreviewBinding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                // Surface is ready, now we can start the preview
                // Only start the preview if it's visible.
                if (localPreviewBinding.preview.visibility == android.view.View.VISIBLE) {
                    viewModel.startPreview(holder.surface, width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // When the Surface is destroyed by the system (e.g., Activity switch), ensure the preview is stopped.
                // Note: hidePreview() already calls stopPreview(), this mainly handles system-triggered destruction.
                if (localPreviewBinding.preview.visibility == android.view.View.VISIBLE) {
                    viewModel.stopPreview()
                }
            }
        })
    }

    private fun checkAndRequestPermissions() {
        if (!permissionHelper.areAllPermissionsGranted()) {
            if (permissionHelper.shouldShowRequestPermissionRationale()) {
                showPermissionRationaleDialog()
            } else {
                permissionHelper.requestPermissions()
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        val deniedPermissions = permissionHelper.getDeniedPermissionNames()
        val hasCameraPermission = deniedPermissions.contains("Camera")
        val message = if (hasCameraPermission) {
            "SquidRun is a live streaming app and requires the following permissions to function correctly:\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\nThe camera permission is especially required; live streaming is not possible without it. Please grant these permissions to continue using the app."
        } else {
            "SquidRun requires the following permissions to function correctly:\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\nPlease grant these permissions to continue using the app."
        }
        
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant Permissions") { _, _ ->
                permissionHelper.requestPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PermissionHelper.PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // All permissions have been granted.
                binding.permissionGranted.text = "true"
                // Do not automatically initialize the camera; wait for the user to take action (stream/record/preview).
            } else {
                // Some permissions were denied.
                binding.permissionGranted.text = "false"
                val deniedPermissions = permissionHelper.getDeniedPermissionNames()
                val hasCameraPermission = deniedPermissions.contains("Camera")
                
                val message = if (hasCameraPermission) {
                    "The following permissions were denied, and the app cannot function correctly:\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\nNote: Without camera permission, the live streaming feature will not work. Please grant these permissions manually in the system settings."
                } else {
                    "The following permissions were denied, and the app cannot function correctly:\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\nPlease grant these permissions manually in the system settings."
                }
                
                AlertDialog.Builder(this)
                    .setTitle("Permissions Denied")
                    .setMessage(message)
                    .setPositiveButton("Retry") { _, _ ->
                        checkAndRequestPermissions()
                    }
                    .setNegativeButton("Exit") { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun createStaccatoVibrationEffect(staccatoCount: Int): VibrationEffect {
        val timing = LongArray(staccatoCount * 2) { 200L }
        val amplitudes = IntArray(staccatoCount * 2) { i ->
            if (i % 2 == 0) 0 else DEFAULT_AMPLITUDE
        }
        return VibrationEffect.createWaveform(timing, amplitudes, -1)
    }
}
