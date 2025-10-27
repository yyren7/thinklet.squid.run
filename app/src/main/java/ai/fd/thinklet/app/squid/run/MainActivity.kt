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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ai.fd.thinklet.sdk.maintenance.power.PowerController
import ai.fd.thinklet.sdk.maintenance.launcher.Extension
import android.util.Log

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val ttsManager: SherpaOnnxTTSManager by lazy {
        (application as SquidRunApplication).ttsManager
    }

    private val statusReportingManager: StatusReportingManager by lazy {
        (application as SquidRunApplication).statusReportingManager
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
    private var lastCameraClickTime = 0L
    private val CAMERA_CLICK_DEBOUNCE_MS = 1000

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

    private val recordingControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("action")) {
                "start" -> {
                    // Remove checks from MainActivity layer, unify handling in ViewModel layer.
                    // ViewModel's concurrency protection will handle repeated requests.
                    viewModel.startRecording { isRecordingStarted ->
                        if (isRecordingStarted) {
                            vibrator.vibrate(createStaccatoVibrationEffect(1))
                        } else {
                            vibrator.vibrate(createStaccatoVibrationEffect(3))
                        }
                    }
                }
                "stop" -> {
                    // Remove checks from MainActivity layer, unify handling in ViewModel layer.
                    // ViewModel will check the status and log appropriately.
                    viewModel.stopRecording { isStopInitiated ->
                        if (isStopInitiated) {
                            vibrator.vibrate(createStaccatoVibrationEffect(2))
                        } else {
                            // Use different vibration feedback for stop failure or not recording.
                            vibrator.vibrate(createStaccatoVibrationEffect(3))
                        }
                    }
                }
            }
        }
    }

    private val serverConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StatusReportingManager.ACTION_SERVER_DISCONNECTED) {
                // Only trigger auto-stop for SRT, as RTMP has its own TCP-based disconnection handling.
                if (viewModel.streamProtocol.value == "srt") {
                    Log.w("MainActivity", "Server disconnected (WebSocket failure) and protocol is SRT. Stopping stream.")
                    if (viewModel.isStreaming.value) {
                        viewModel.stopStreaming()
                    }
                } else {
                    Log.i("MainActivity", "Server disconnected (WebSocket failure), but protocol is RTMP. Letting RTMP handle disconnection.")
                }
            }
        }
    }

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

        // Set the streamKey to the deviceId once, as it's now fixed.
        val deviceId = statusReportingManager.deviceId
        viewModel.setStreamKey(deviceId)
        statusReportingManager.updateStreamKey(deviceId)

        // Display the deviceId on the UI.
        binding.deviceId.text = deviceId
        binding.deviceIdSource.text = getString(R.string.device_id_source_text, statusReportingManager.deviceIdSource)

        // Observe and display server IP
        lifecycleScope.launch {
            viewModel.serverIp.collectLatest { serverIp ->
                binding.serverIp.setText(serverIp)
            }
        }

        // Observe and display stream URL (dynamically generated from server IP)
        // When streamUrl changes (due to IP change), update StatusReportingManager
        lifecycleScope.launch {
            viewModel.streamUrl.collectLatest { streamUrl ->
                binding.streamUrl.text = streamUrl
                statusReportingManager.updateStreamUrl(streamUrl)
            }
        }
        
        // Monitor serverIp changes to handle reconnection of streaming and other services
        lifecycleScope.launch {
            var firstEmission = true
            viewModel.serverIp.collectLatest { newIp ->
                // Skip the first emission (initial value)
                if (firstEmission) {
                    firstEmission = false
                    return@collectLatest
                }
                
                // IP changed, handle reconnection
                handleServerIpChanged(newIp)
            }
        }

        // The streamKey is now fixed to deviceId and the UI is hidden,
        // so we only need to display it once for debugging or informational purposes.
        binding.streamKey.setText(statusReportingManager.deviceId)
        binding.streamKey.isEnabled = false


        binding.dimension.text =
            getString(R.string.dimension_text, viewModel.width, viewModel.height)
        binding.recordDimension.text =
            getString(R.string.dimension_text, viewModel.recordVideoWidth, viewModel.recordVideoHeight)
        binding.videoBitrate.text = (viewModel.videoBitrateBps / 1024).toString()
        binding.samplingRate.text = (viewModel.audioSampleRateHz / 1000f).toString()
        binding.audioBitrate.text = (viewModel.audioBitrateBps / 1024).toString()
        binding.audioChannel.text = viewModel.audioChannel.argumentValue
        binding.echoCanceler.text = viewModel.isEchoCancelerEnabled.toString()
        binding.micMode.text = viewModel.micMode.argumentValue
        binding.permissionGranted.text = permissionHelper.areAllPermissionsGranted().toString()

        // Save button click listener - now only saves server IP
        binding.buttonSaveConfig.setOnClickListener {
            val newServerIp = binding.serverIp.text.toString().trim()
            
            // Basic IP address validation
            if (newServerIp.isEmpty()) {
                viewModel.showToast("Server IP cannot be empty")
                return@setOnClickListener
            }
            
            // Simple IP format validation (basic check) - also allow hostnames
            val ipPattern = "^\\d{1,3}(\\.\\d{1,3}){3}$".toRegex()
            val hostnamePattern = "^[a-zA-Z0-9][a-zA-Z0-9-\\.]*[a-zA-Z0-9]$".toRegex()
            if (!newServerIp.matches(ipPattern) && !newServerIp.matches(hostnamePattern)) {
                viewModel.showToast("Invalid IP/hostname format. Example: 192.168.1.100")
                return@setOnClickListener
            }
            
            // Save the configuration
            viewModel.updateServerIp(newServerIp)
            viewModel.showToast("Configuration saved successfully")
            
            // Disable the save button after saving
            binding.buttonSaveConfig.isEnabled = false
        }
        binding.buttonSaveConfig.isEnabled = false

        // Text change listeners to enable/disable save button
        binding.serverIp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                checkIfConfigChanged()
            }
        })

        // If the preview is shown by default, initialize and display it.
        if (viewModel.shouldShowPreview) {
            inflateAndSetupPreview()
            // Show the preview (this will trigger surfaceChanged and start the camera).
            showPreview()
        }

        setupReceivers()

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
        
    }

    override fun onDestroy() {
        Log.i("MainActivity", "🛑 Activity is being destroyed, cleaning up resources...")
        
        // Unregister broadcast receivers first to avoid receiving new commands during cleanup.
        unregisterReceivers()
        
        Log.i("MainActivity", "✅ Activity cleanup completed")
        super.onDestroy()
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

    /**
     * Check if the server IP or stream key has been changed from saved values.
     */
    private fun checkIfConfigChanged() {
        val currentServerIp = binding.serverIp.text.toString().trim()
        val savedServerIp = viewModel.serverIp.value
        
        val isChanged = currentServerIp != savedServerIp
        binding.buttonSaveConfig.isEnabled = isChanged
    }

    private fun maybeNotifyLaunchErrors() {
        if (!permissionHelper.areAllPermissionsGranted()) {
            vibrator.vibrate(createStaccatoVibrationEffect(2))
            return
        }
        if (viewModel.streamUrl.value.isBlank() || viewModel.streamKey.value.isNullOrBlank()) {
            vibrator.vibrate(createStaccatoVibrationEffect(3))
            return
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                // The original physical button trigger logic is retained,
                // but now it calls the unified `toggleRecording` method.
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                // Consume the event to prevent system volume change
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Consume the event to prevent system volume change
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
            KeyEvent.KEYCODE_CAMERA -> {
                val now = System.currentTimeMillis()
                if (now - lastCameraClickTime > CAMERA_CLICK_DEBOUNCE_MS) {
                    lastCameraClickTime = now
                    toggleRecording()
                }
                true
            }
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
        // 1. Vibrator feedback
        val timings = longArrayOf(0, 200, 200, 200)
        val amplitudes = intArrayOf(0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))

        // 2. TTS speech announcement
        ttsManager.speakPowerDown()

        // 3. Send offline status and prepare for shutdown
        statusReportingManager.sendOfflineStatusAndStop()

        // 4. Execute shutdown
        PowerController().shutdown(this, wait = 1000 /* max wait 1s */)
    }

    private fun handleVolumeUpKeyPress(): Boolean {
        val batteryPercentage = ttsManager.getBatteryPercentage()
        val networkStatus = ttsManager.getNetworkStatus()
        val statusMessage = "battery status: ${batteryPercentage}% remaining, network status: $networkStatus"
        viewModel.showToast(statusMessage)
        ttsManager.speakBatteryAndNetworkStatus()
        return true
    }

    private fun handleVolumeDownKeyUp(): Boolean {
        return true
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
            // Make sure the preview is visible
            previewBinding?.preview?.visibility = android.view.View.VISIBLE
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

    /**
     * Handle server IP change - stop streaming if active, WebSocket will reconnect automatically
     */
    private fun handleServerIpChanged(newIp: String?) {
        if (newIp == null) return
        
        Log.i("MainActivity", "🔄 Server IP changed to: $newIp")
        
        // StatusReportingManager's WebSocket will automatically reconnect via streamUrl observer
        // (already handled in the streamUrl.collectLatest block)
        
        // If streaming is active, it will be stopped by updateServerIp() in ViewModel
        // User should manually restart streaming if they want to stream to the new address
        
        viewModel.showToast("Server IP updated to: $newIp")
        Log.i("MainActivity", "✅ IP change handling completed")
    }

    private fun setupReceivers() {
        LocalBroadcastManager.getInstance(this).registerReceiver(streamingControlReceiver, IntentFilter("streaming-control"))
        LocalBroadcastManager.getInstance(this).registerReceiver(recordingControlReceiver, IntentFilter("recording-control"))

        val serverConnectionFilter = IntentFilter(StatusReportingManager.ACTION_SERVER_DISCONNECTED)
        LocalBroadcastManager.getInstance(this).registerReceiver(serverConnectionReceiver, serverConnectionFilter)
    }

    private fun unregisterReceivers() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(streamingControlReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingControlReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serverConnectionReceiver)
            Log.d("MainActivity", "✅ All broadcast receivers unregistered")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Failed to unregister a receiver", e)
        }
    }
}
