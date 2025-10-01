package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.app.squid.run.databinding.ActivityMainBinding
import ai.fd.thinklet.app.squid.run.databinding.PreviewBinding
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

        viewModel.updateStreamKey(statusReportingManager.deviceId)

        binding.streamUrl.text = viewModel.streamUrl
        lifecycleScope.launch {
            viewModel.streamKey.collectLatest { streamKey ->
                binding.streamKey.setText(streamKey)
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

        if (viewModel.shouldShowPreview) {
            val previewBinding = PreviewBinding.bind(binding.previewStub.inflate())
            previewBinding.preview.updateLayoutParams<ConstraintLayout.LayoutParams> {
                dimensionRatio = "${viewModel.width}:${viewModel.height}"
            }
            previewBinding.preview.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    // Surface is ready, now we can prepare the stream
                    viewModel.maybePrepareStreaming()
                    viewModel.startPreview(holder.surface, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.stopPreview()
            })
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
                    statusReportingManager.updateStreamingReadyStatus(it)
                }
        }
        lifecycleScope.launch {
            viewModel.isStreaming
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streaming.text = it.toString()
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
                    // 更新按钮文字
                    binding.buttonRecord.text = if (isRecording) {
                        getString(R.string.button_stop_recording)
                    } else {
                        getString(R.string.button_start_recording)
                    }
                }
        }

        // 设置录制按钮点击事件
        binding.buttonRecord.setOnClickListener {
            toggleRecording()
        }

        statusReportingManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusReportingManager.stop()
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
                toggleStreaming()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                toggleAudioMute()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleStreaming() {
        if (!viewModel.isStreaming.value) {
            val isStreamingStarted = viewModel.maybeStartStreaming()
            if (!isStreamingStarted) {
                vibrator.vibrate(createStaccatoVibrationEffect(2))
            }
        } else {
            viewModel.stopStreaming()
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
            val isRecordingStarted = viewModel.startRecording()
            if (isRecordingStarted) {
                vibrator.vibrate(createStaccatoVibrationEffect(1))
            } else {
                vibrator.vibrate(createStaccatoVibrationEffect(3))
            }
        }
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
        val hasCameraPermission = deniedPermissions.contains("摄像头")
        val message = if (hasCameraPermission) {
            "SquidRun 是一个直播应用，需要以下权限才能正常工作：\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\n特别是摄像头权限是必需的，没有摄像头权限无法进行直播。请授予这些权限以继续使用应用。"
        } else {
            "SquidRun 需要以下权限才能正常工作：\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\n请授予这些权限以继续使用应用。"
        }
        
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage(message)
            .setPositiveButton("授予权限") { _, _ ->
                permissionHelper.requestPermissions()
            }
            .setNegativeButton("取消") { dialog, _ ->
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
                // 所有权限都已授予
                binding.permissionGranted.text = "true"
                viewModel.maybePrepareStreaming()
            } else {
                // 有权限被拒绝
                binding.permissionGranted.text = "false"
                val deniedPermissions = permissionHelper.getDeniedPermissionNames()
                val hasCameraPermission = deniedPermissions.contains("摄像头")
                
                val message = if (hasCameraPermission) {
                    "以下权限被拒绝，应用无法正常工作：\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\n特别注意：没有摄像头权限，直播功能将无法使用。请在系统设置中手动授予这些权限。"
                } else {
                    "以下权限被拒绝，应用无法正常工作：\n\n${deniedPermissions.joinToString("\n• ", "• ")}\n\n请在系统设置中手动授予这些权限。"
                }
                
                AlertDialog.Builder(this)
                    .setTitle("权限被拒绝")
                    .setMessage(message)
                    .setPositiveButton("重试") { _, _ ->
                        checkAndRequestPermissions()
                    }
                    .setNegativeButton("退出") { _, _ ->
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
