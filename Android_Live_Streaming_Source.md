# Android Live Streaming Source Code

This document contains the complete source code for the Android live streaming feature, structured for easy migration to other platforms.

## File Structure

```
app
└── src
    ├── main
    │   ├── java
    │   │   └── ai
    │   │       └── fd
    │   │           └── thinklet
    │   │               └── app
    │   │                   └── squid
    │   │                       └── run
    │   │                           ├── MainActivity.kt
    │   │                           ├── MainViewModel.kt
    │   │                           ├── ConfigHelper.kt
    │   │                           ├── ConfigManager.kt
    │   │                           ├── DefaultConfig.kt
    │   │                           └── PermissionHelper.kt
    │   ├── res
    │   │   ├── layout
    │   │   │   └── activity_main.xml
    │   │   └── values
    │   │       └── strings.xml
    │   └── AndroidManifest.xml
    ...
```

---

## Source Code

### `app/src/main/java/ai/fd/thinklet/app/squid/run/MainActivity.kt`

```kotlin
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val binding: ActivityMainBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val vibrator: Vibrator by lazy(LazyThreadSafetyMode.NONE) {
        checkNotNull(getSystemService())
    }

    private val permissionHelper: PermissionHelper by lazy(LazyThreadSafetyMode.NONE) {
        PermissionHelper(this)
    }

    private val viewModel: MainViewModel by viewModels()

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

        binding.streamUrl.text = viewModel.streamUrl
        binding.streamKey.text = viewModel.streamKey
        binding.dimension.text =
            getString(R.string.dimension_text, viewModel.width, viewModel.height)
        binding.videoBitrate.text = (viewModel.videoBitrateBps / 1024).toString()
        binding.samplingRate.text = (viewModel.audioSampleRateHz / 1000f).toString()
        binding.audioBitrate.text = (viewModel.audioBitrateBps / 1024).toString()
        binding.audioChannel.text = viewModel.audioChannel.argumentValue
        binding.echoCanceler.text = viewModel.isEchoCancelerEnabled.toString()
        binding.micMode.text = viewModel.micMode.argumentValue
        binding.permissionGranted.text = permissionHelper.areAllPermissionsGranted().toString()

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
                ) = viewModel.startPreview(holder.surface, width, height)

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
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
        maybeNotifyLaunchErrors()
        viewModel.maybePrepareStreaming()
    }

    override fun onPause() {
        viewModel.stopStreaming()
        super.onPause()
    }

    private fun maybeNotifyLaunchErrors() {
        if (!permissionHelper.areAllPermissionsGranted()) {
            vibrator.vibrate(createStaccatoVibrationEffect(2))
            return
        }
        if (viewModel.streamUrl == null || viewModel.streamKey == null) {
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
```

---

### `app/src/main/res/layout/activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layout_margin="16dp"
    tools:context=".MainActivity">

    <TextView
        android:id="@+id/title_launch_parameter"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/launch_parameters"
        android:textAppearance="@style/TextAppearance.AppCompat.Title"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/label_stream_url"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_stream_url"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/title_launch_parameter" />

    <TextView
        android:id="@+id/stream_url"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_stream_url"
        app:layout_constraintTop_toTopOf="@id/label_stream_url"
        tools:text="rtmp://example.com" />

    <TextView
        android:id="@+id/label_stream_key"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_stream_key"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_stream_url" />

    <TextView
        android:id="@+id/stream_key"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_stream_key"
        app:layout_constraintTop_toTopOf="@id/label_stream_key"
        tools:text="xxxx-xxxx-xxxx-xxxx" />

    <TextView
        android:id="@+id/label_dimension"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_dimension"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_stream_key" />

    <TextView
        android:id="@+id/dimension"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_dimension"
        app:layout_constraintTop_toTopOf="@id/label_dimension"
        tools:text="(w)1920 x (h)1080" />


    <TextView
        android:id="@+id/label_video_bitrate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_video_bitrate"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_dimension" />

    <TextView
        android:id="@+id/video_bitrate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_video_bitrate"
        app:layout_constraintTop_toTopOf="@id/label_video_bitrate"
        tools:text="8000" />

    <TextView
        android:id="@+id/label_sampling_rate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_sampling_rate"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_video_bitrate" />

    <TextView
        android:id="@+id/sampling_rate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_sampling_rate"
        app:layout_constraintTop_toTopOf="@id/label_sampling_rate"
        tools:text="96" />

    <TextView
        android:id="@+id/label_audio_bitrate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_audio_bitrate"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_sampling_rate" />

    <TextView
        android:id="@+id/audio_bitrate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_audio_bitrate"
        app:layout_constraintTop_toTopOf="@id/label_audio_bitrate"
        tools:text="96" />

    <TextView
        android:id="@+id/label_audio_channel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_audio_channel"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_audio_bitrate" />

    <TextView
        android:id="@+id/audio_channel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_audio_channel"
        app:layout_constraintTop_toTopOf="@id/label_audio_channel"
        tools:text="stereo" />

    <TextView
        android:id="@+id/label_echo_canceler"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_echo_canceler"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_audio_channel" />

    <TextView
        android:id="@+id/echo_canceler"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_echo_canceler"
        app:layout_constraintTop_toTopOf="@id/label_echo_canceler"
        tools:text="true" />

    <TextView
        android:id="@+id/label_mic_mode"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_mic_mode"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_echo_canceler" />

    <TextView
        android:id="@+id/mic_mode"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_mic_mode"
        app:layout_constraintTop_toTopOf="@id/label_mic_mode"
        tools:text="android" />

    <TextView
        android:id="@+id/label_app_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/label_app_status"
        android:textAppearance="@style/TextAppearance.AppCompat.Body2"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/mic_mode" />

    <TextView
        android:id="@+id/connection_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:textAppearance="@style/TextAppearance.AppCompat.Body2"
        app:layout_constraintStart_toEndOf="@id/label_app_status"
        app:layout_constraintTop_toTopOf="@id/label_app_status"
        tools:text="CONNECTED" />

    <TextView
        android:id="@+id/label_permission_granted"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_permission_granted"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_app_status" />

    <TextView
        android:id="@+id/permission_granted"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_permission_granted"
        app:layout_constraintTop_toTopOf="@id/label_permission_granted"
        tools:text="true" />

    <TextView
        android:id="@+id/label_stream_prepared"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/label_stream_prepared"
        android:textAppearance="@style/TextAppearance.AppCompat.Body2"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_app_status" />

    <TextView
        android:id="@+id/stream_prepared"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_stream_prepared"
        app:layout_constraintTop_toTopOf="@id/label_stream_prepared"
        tools:text="true" />

    <TextView
        android:id="@+id/label_streaming"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_streaming"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_stream_prepared" />

    <TextView
        android:id="@+id/streaming"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:textAppearance="@style/TextAppearance.AppCompat.Body2"
        app:layout_constraintStart_toEndOf="@id/label_streaming"
        app:layout_constraintTop_toTopOf="@id/label_streaming"
        tools:text="true" />

    <TextView
        android:id="@+id/label_audio_muted"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/label_audio_muted"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_streaming" />

    <TextView
        android:id="@+id/audio_muted"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        app:layout_constraintStart_toEndOf="@id/label_audio_muted"
        app:layout_constraintTop_toTopOf="@id/label_audio_muted"
        tools:text="true" />

    <TextView
        android:id="@+id/title_streaming_events"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/streaming_events"
        android:textAppearance="@style/TextAppearance.AppCompat.Title"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/label_audio_muted" />

    <TextView
        android:id="@+id/streaming_events"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/title_streaming_events"
        tools:text="event1\nevent2" />

    <ViewStub
        android:id="@+id/preview_stub"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout="@layout/preview"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintDimensionRatio="1"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintHorizontal_bias="1"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintWidth_percent="0.5" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

---

### `app/src/main/res/values/strings.xml`

```xml
<resources>
    <string name="app_name">thinklet.squid.run</string>
    <string name="launch_parameters">Launch Parameters</string>
    <string name="label_stream_url">Stream URL: </string>
    <string name="label_stream_key">Stream Key: </string>
    <string name="label_dimension">Video Dimension: </string>
    <string name="label_video_bitrate">Video Bitrate (kbps): </string>
    <string name="label_sampling_rate">Audio Sampling Rate (kHz): </string>
    <string name="label_audio_bitrate">Audio Bitrate (kbps): </string>
    <string name="label_audio_channel">Audio Channel: </string>
    <string name="label_echo_canceler">Echo Canceler: </string>
    <string name="label_mic_mode">Mic Mode: </string>
    <string name="label_permission_granted">Permission Granted: </string>
    <string name="label_app_status">App Status:</string>
    <string name="label_stream_prepared">Stream Prepared: </string>
    <string name="label_streaming">Streaming: </string>
    <string name="label_audio_muted">Audio Muted: </string>
    <string name="dimension_text">(w)%1$d x (h)%2$d</string>
    <string name="streaming_events">Streaming Events</string>
</resources>
```

---

### `app/src/main/java/ai/fd/thinklet/app/squid/run/MainViewModel.kt`

```kotlin
package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord
import ai.fd.thinklet.sdk.maintenance.camera.Angle
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Surface
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.video.Camera2Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(
    private val application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)

    val streamUrl: String? = savedState.get<String>("streamUrl") ?: DefaultConfig.DEFAULT_STREAM_URL
    val streamKey: String? = savedState.get<String>("streamKey") ?: DefaultConfig.DEFAULT_STREAM_KEY
    private val longSide: Int = savedState.get<Int>("longSide") ?: DefaultConfig.DEFAULT_LONG_SIDE
    private val shortSide: Int = savedState.get<Int>("shortSide") ?: DefaultConfig.DEFAULT_SHORT_SIDE
    private val orientation: Orientation? =
        Orientation.fromArgumentValue(savedState.get<String>("orientation") ?: DefaultConfig.DEFAULT_ORIENTATION)
    val videoBitrateBps: Int =
        savedState.get<Int>("videoBitrate")?.let { it * 1024 } ?: (DefaultConfig.DEFAULT_VIDEO_BITRATE * 1024)
    val audioSampleRateHz: Int =
        savedState.get<Int>("audioSampleRate") ?: DefaultConfig.DEFAULT_AUDIO_SAMPLE_RATE
    val audioBitrateBps: Int =
        savedState.get<Int>("audioBitrate")?.let { it * 1024 } ?: (DefaultConfig.DEFAULT_AUDIO_BITRATE * 1024)
    val audioChannel: AudioChannel =
        AudioChannel.fromArgumentValue(savedState.get<String>("audioChannel") ?: DefaultConfig.DEFAULT_AUDIO_CHANNEL)
            ?: AudioChannel.STEREO
    val micMode: MicMode =
        MicMode.fromArgumentValue(savedState.get<String>("micMode") ?: DefaultConfig.DEFAULT_MIC_MODE)
            ?: MicMode.ANDROID
    val isEchoCancelerEnabled: Boolean = savedState.get<Boolean>("echoCanceler") ?: DefaultConfig.DEFAULT_ECHO_CANCELER
    val shouldShowPreview: Boolean = savedState.get<Boolean>("preview") ?: DefaultConfig.DEFAULT_PREVIEW

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isPrepared: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    private val _isStreaming: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isAudioMuted: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isAudioMuted: StateFlow<Boolean> = _isAudioMuted.asStateFlow()

    private val streamingEventMutableSharedFlow: MutableSharedFlow<StreamingEvent> =
        MutableSharedFlow(replay = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val latestStreamingEventList: Flow<List<String>> = streamingEventMutableSharedFlow
        .map { DateTimeFormatter.ISO_LOCAL_TIME.format(it.timestamp) + " " + it.message }
        .runningFold(ArrayList(STREAMING_EVENT_BUFFER_SIZE)) { acc, value ->
            acc.add(value)
            if (acc.size > STREAMING_EVENT_BUFFER_SIZE) {
                acc.removeAt(0)
            }
            acc
        }

    val width: Int
        get() = if (angle.isPortrait()) shortSide else longSide

    val height: Int
        get() = if (angle.isPortrait()) longSide else shortSide

    private var stream: GenericStream? = null

    private var startPreviewJob: Job? = null

    init {
        when (orientation) {
            Orientation.LANDSCAPE -> angle.setLandscape()
            Orientation.PORTRAIT -> angle.setPortrait()
            null -> Unit
        }
    }

    fun isAllPermissionGranted(): Boolean = PermissionHelper.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查设备是否有可用的相机
     */
    private fun isCameraAvailable(): Boolean {
        return try {
            val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            cameraIds.isNotEmpty()
        } catch (e: Exception) {
            // 如果出现任何异常，认为相机不可用
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("相机检查失败: ${e.message}")
            )
            false
        }
    }

    @MainThread
    fun maybePrepareStreaming() {
        if (streamUrl == null || streamKey == null || !isAllPermissionGranted() || _isPrepared.value) {
            _isPrepared.value = false
            return
        }

        // 检查相机是否可用（必需的硬件）
        if (!isCameraAvailable()) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("错误: 未检测到可用的相机设备，无法进行直播")
            )
            _isPrepared.value = false
            return
        }

        try {
            val angle = Angle()
            val camera2Source = Camera2Source(application)
            val isInitiallyMuted = _isAudioMuted.value
            val audioSource = when (micMode) {
                MicMode.ANDROID -> createMicrophoneSource(isInitiallyMuted)

                MicMode.THINKLET_5 -> createThinkletMicrophoneSource(
                    application,
                    MultiChannelAudioRecord.Channel.CHANNEL_FIVE,
                    isInitiallyMuted
                )

                MicMode.THINKLET_6 -> createThinkletMicrophoneSource(
                    application,
                    MultiChannelAudioRecord.Channel.CHANNEL_SIX,
                    isInitiallyMuted
                )
            }
            val localStream = stream ?: GenericStream(
                application,
                ConnectionCheckerImpl(streamingEventMutableSharedFlow, _isStreaming, _connectionStatus),
                camera2Source,
                audioSource
            ).apply {
                getGlInterface().autoHandleOrientation = false
                if (angle.isLandscape()) {
                    val rotateFilterRender = RotationFilterRender().apply {
                        this.rotation = 270
                    }
                    getGlInterface().addFilter(rotateFilterRender)
                }
            }
            val isPrepared = try {
                // Note: The output size is converted by 90 degrees inside RootEncoder when the rotation
                // is portrait. Therefore, we intentionally pass `longSide` and `shortSide` to `width`
                // and `height` respectively so that it will be output at the correct size.
                val isVideoPrepared = localStream.prepareVideo(
                    width = longSide,
                    height = shortSide,
                    bitrate = videoBitrateBps,
                    rotation = angle.current()
                )
                val isAudioPrepared = localStream.prepareAudio(
                    sampleRate = audioSampleRateHz,
                    isStereo = audioChannel == AudioChannel.STEREO,
                    bitrate = audioBitrateBps,
                    echoCanceler = isEchoCancelerEnabled
                )
                isVideoPrepared && isAudioPrepared
            } catch (e: IllegalArgumentException) {
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("直播参数设置失败: ${e.message}")
                )
                false
            } catch (e: Exception) {
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("直播准备失败: ${e.message}")
                )
                false
            }
            
            if (isPrepared) {
                stream = localStream
                _isPrepared.value = true
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("直播准备完成")
                )
            } else {
                _isPrepared.value = false
            }
        } catch (e: Exception) {
            // 捕获相机初始化相关的所有异常
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("相机初始化失败: ${e.message}")
            )
            _isPrepared.value = false
        }
    }

    private fun createMicrophoneSource(isInitiallyMuted: Boolean): MicrophoneSource {
        val microphoneSource = MicrophoneSource()
        if (isInitiallyMuted) {
            microphoneSource.mute()
        }
        return microphoneSource
    }

    private fun createThinkletMicrophoneSource(
        context: Context,
        inputChannel: MultiChannelAudioRecord.Channel,
        isInitiallyMuted: Boolean
    ): ThinkletMicrophoneSource {
        val microphoneSource = ThinkletMicrophoneSource(context, inputChannel)
        if (isInitiallyMuted) {
            microphoneSource.mute()
        }
        return microphoneSource
    }

    fun maybeStartStreaming(): Boolean {
        val isStreamingStarted = maybeStartStreamingInternal()
        _isStreaming.value = isStreamingStarted
        return isStreamingStarted
    }

    private fun maybeStartStreamingInternal(): Boolean {
        val streamSnapshot = stream
        if (streamUrl == null || streamKey == null || streamSnapshot == null) {
            return false
        }
        if (streamSnapshot.isStreaming) {
            return true
        }
        streamSnapshot.startStream("$streamUrl/$streamKey")
        return true
    }

    fun stopStreaming() {
        val streamSnapshot = stream ?: return
        streamSnapshot.stopStream()
        _isStreaming.value = false
        _connectionStatus.value = ConnectionStatus.IDLE
    }

    @MainThread
    fun startPreview(surface: Surface, width: Int, height: Int) {
        val localStream = stream ?: return
        if (localStream.isOnPreview) {
            localStream.stopPreview()
        }

        if (startPreviewJob != null) {
            return
        }
        startPreviewJob = viewModelScope.launch {
            isPrepared.first { true }
            localStream.startPreview(surface, width, height)
        }
    }

    @MainThread
    fun stopPreview() {
        startPreviewJob?.cancel()
        val localStream = stream ?: return
        if (localStream.isOnPreview) {
            localStream.stopPreview()
        }
    }

    fun muteAudio() {
        _isAudioMuted.value = true
        val audioSource = stream?.audioSource ?: return
        when (audioSource) {
            is ThinkletMicrophoneSource -> audioSource.mute()
            is MicrophoneSource -> audioSource.mute()
        }
    }

    fun unMuteAudio() {
        _isAudioMuted.value = false
        val audioSource = stream?.audioSource ?: return
        when (audioSource) {
            is ThinkletMicrophoneSource -> audioSource.unMute()
            is MicrophoneSource -> audioSource.unMute()
        }
    }

    private class ConnectionCheckerImpl(
        private val streamingEventMutableSharedFlow: MutableSharedFlow<StreamingEvent>,
        private val isStreamingFlow: MutableStateFlow<Boolean>,
        private val connectionStatusFlow: MutableStateFlow<ConnectionStatus>
    ) : ConnectChecker {
        override fun onAuthError() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onAuthError"))
            connectionStatusFlow.tryEmit(ConnectionStatus.FAILED)
        }

        override fun onAuthSuccess() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onAuthSuccess"))
        }

        override fun onConnectionFailed(reason: String) {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onConnectionFailed: $reason"))
            isStreamingFlow.tryEmit(false)
            connectionStatusFlow.tryEmit(ConnectionStatus.FAILED)
        }

        override fun onConnectionStarted(url: String) {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onConnectionStarted: $url"))
            connectionStatusFlow.tryEmit(ConnectionStatus.CONNECTING)
        }

        override fun onConnectionSuccess() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onConnectionSuccess"))
            isStreamingFlow.tryEmit(true)
            connectionStatusFlow.tryEmit(ConnectionStatus.CONNECTED)
        }

        override fun onDisconnect() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onDisconnect"))
            isStreamingFlow.tryEmit(false)
            connectionStatusFlow.tryEmit(ConnectionStatus.DISCONNECTED)
        }

        override fun onNewBitrate(bitrate: Long) {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onNewBitrate: $bitrate"))
        }
    }

    private data class StreamingEvent(
        val message: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        CONNECTED,
        FAILED,
        DISCONNECTED
    }

    enum class Orientation(val argumentValue: String) {
        LANDSCAPE("landscape"), PORTRAIT("portrait");

        companion object {
            fun fromArgumentValue(value: String?): Orientation? =
                entries.find { it.argumentValue == value }
        }
    }

    enum class AudioChannel(val argumentValue: String) {
        MONAURAL("monaural"), STEREO("stereo");

        companion object {
            fun fromArgumentValue(value: String?): AudioChannel? =
                entries.find { it.argumentValue == value }
        }
    }

    enum class MicMode(val argumentValue: String) {
        ANDROID("android"),
        THINKLET_5("thinklet5"),
        THINKLET_6("thinklet6"),
        ;

        companion object {
            fun fromArgumentValue(value: String?): MicMode? =
                entries.find { it.argumentValue == value }
        }
    }

    companion object {
        private const val STREAMING_EVENT_BUFFER_SIZE = 15
    }
}
```

---

### `app/src/main/java/ai/fd/thinklet/app/squid/run/ConfigHelper.kt`

```kotlin
package ai.fd.thinklet.app.squid.run

/**
 * 配置助手类
 * 提供便捷的方法来快速设置常用的配置组合
 */
object ConfigHelper {
    
    /**
     * 本地测试配置（使用MediaMTX）
     */
    fun getLocalTestConfig(): Map<String, Any> {
        return mapOf(
            "streamUrl" to "rtmp://192.168.16.88:1935/thinklet.squid.run",
            "streamKey" to "test_stream",
            "longSide" to 720,
            "shortSide" to 480,
            "orientation" to "landscape",
            "videoBitrate" to 2048, // 降低比特率用于本地测试
            "audioSampleRate" to 48000,
            "audioBitrate" to 128,
            "audioChannel" to "stereo",
            "echoCanceler" to false,
            "micMode" to "android",
            "preview" to true // 本地测试时启用预览
        )
    }
    
    /**
     * YouTube Live 配置模板
     */
    fun getYoutubeLiveConfig(streamKey: String): Map<String, Any> {
        return mapOf(
            "streamUrl" to "rtmp://a.rtmp.youtube.com/live2",
            "streamKey" to streamKey,
            "longSide" to 1280,
            "shortSide" to 720,
            "orientation" to "landscape",
            "videoBitrate" to 4096,
            "audioSampleRate" to 48000,
            "audioBitrate" to 128,
            "audioChannel" to "stereo",
            "echoCanceler" to true, // YouTube推荐启用
            "micMode" to "android",
            "preview" to false
        )
    }
    
    /**
     * 高质量配置（适合专业直播）
     */
    fun getHighQualityConfig(streamUrl: String, streamKey: String): Map<String, Any> {
        return mapOf(
            "streamUrl" to streamUrl,
            "streamKey" to streamKey,
            "longSide" to 1920,
            "shortSide" to 1080,
            "orientation" to "landscape",
            "videoBitrate" to 8192, // 8Mbps
            "audioSampleRate" to 48000,
            "audioBitrate" to 320, // 高质量音频
            "audioChannel" to "stereo",
            "echoCanceler" to true,
            "micMode" to "thinklet5", // 使用THINKLET多麦克风
            "preview" to false
        )
    }
    
    /**
     * 低功耗配置（节省电池）
     */
    fun getLowPowerConfig(streamUrl: String, streamKey: String): Map<String, Any> {
        return mapOf(
            "streamUrl" to streamUrl,
            "streamKey" to streamKey,
            "longSide" to 640,
            "shortSide" to 360,
            "orientation" to "landscape",
            "videoBitrate" to 1024, // 1Mbps
            "audioSampleRate" to 44100,
            "audioBitrate" to 96,
            "audioChannel" to "monaural", // 单声道节省带宽
            "echoCanceler" to false,
            "micMode" to "android",
            "preview" to false
        )
    }
    
    /**
     * 竖屏配置（适合移动设备观看）
     */
    fun getPortraitConfig(streamUrl: String, streamKey: String): Map<String, Any> {
        return mapOf(
            "streamUrl" to streamUrl,
            "streamKey" to streamKey,
            "longSide" to 720,
            "shortSide" to 480,
            "orientation" to "portrait", // 强制竖屏
            "videoBitrate" to 3072,
            "audioSampleRate" to 48000,
            "audioBitrate" to 128,
            "audioChannel" to "stereo",
            "echoCanceler" to false,
            "micMode" to "android",
            "preview" to false
        )
    }
    
    /**
     * 生成adb命令字符串
     */
    fun generateAdbCommand(config: Map<String, Any>): String {
        val baseCommand = "adb shell am start -n ai.fd.thinklet.app.squid.run/.MainActivity -a android.intent.action.MAIN"
        val params = mutableListOf<String>()
        
        config.forEach { (key, value) ->
            when (value) {
                is String -> params.add("-e $key \"$value\"")
                is Int -> params.add("--ei $key $value")
                is Boolean -> params.add("--ez $key $value")
            }
        }
        
        return if (params.isEmpty()) {
            baseCommand
        } else {
            "$baseCommand \\\n    ${params.joinToString(" \\\n    ")}"
        }
    }
}
```

---

### `app/src/main/java/ai/fd/thinklet/app/squid/run/ConfigManager.kt`

```kotlin
package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 * 用于管理应用的配置参数，支持从SharedPreferences读取用户自定义配置
 */
class ConfigManager(context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("squid_run_config", Context.MODE_PRIVATE)
    
    /**
     * 获取流媒体服务器URL
     */
    fun getStreamUrl(): String {
        return sharedPreferences.getString("streamUrl", DefaultConfig.DEFAULT_STREAM_URL) 
            ?: DefaultConfig.DEFAULT_STREAM_URL
    }
    
    /**
     * 设置流媒体服务器URL
     */
    fun setStreamUrl(url: String) {
        sharedPreferences.edit().putString("streamUrl", url).apply()
    }
    
    /**
     * 获取流密钥
     */
    fun getStreamKey(): String {
        return sharedPreferences.getString("streamKey", DefaultConfig.DEFAULT_STREAM_KEY)
            ?: DefaultConfig.DEFAULT_STREAM_KEY
    }
    
    /**
     * 设置流密钥
     */
    fun setStreamKey(key: String) {
        sharedPreferences.edit().putString("streamKey", key).apply()
    }
    
    /**
     * 获取长边分辨率
     */
    fun getLongSide(): Int {
        return sharedPreferences.getInt("longSide", DefaultConfig.DEFAULT_LONG_SIDE)
    }
    
    /**
     * 设置长边分辨率
     */
    fun setLongSide(size: Int) {
        sharedPreferences.edit().putInt("longSide", size).apply()
    }
    
    /**
     * 获取短边分辨率
     */
    fun getShortSide(): Int {
        return sharedPreferences.getInt("shortSide", DefaultConfig.DEFAULT_SHORT_SIDE)
    }
    
    /**
     * 设置短边分辨率
     */
    fun setShortSide(size: Int) {
        sharedPreferences.edit().putInt("shortSide", size).apply()
    }
    
    /**
     * 获取视频比特率（kbps）
     */
    fun getVideoBitrate(): Int {
        return sharedPreferences.getInt("videoBitrate", DefaultConfig.DEFAULT_VIDEO_BITRATE)
    }
    
    /**
     * 设置视频比特率（kbps）
     */
    fun setVideoBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt("videoBitrate", bitrate).apply()
    }
    
    /**
     * 获取音频采样率（Hz）
     */
    fun getAudioSampleRate(): Int {
        return sharedPreferences.getInt("audioSampleRate", DefaultConfig.DEFAULT_AUDIO_SAMPLE_RATE)
    }
    
    /**
     * 设置音频采样率（Hz）
     */
    fun setAudioSampleRate(sampleRate: Int) {
        sharedPreferences.edit().putInt("audioSampleRate", sampleRate).apply()
    }
    
    /**
     * 获取音频比特率（kbps）
     */
    fun getAudioBitrate(): Int {
        return sharedPreferences.getInt("audioBitrate", DefaultConfig.DEFAULT_AUDIO_BITRATE)
    }
    
    /**
     * 设置音频比特率（kbps）
     */
    fun setAudioBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt("audioBitrate", bitrate).apply()
    }
    
    /**
     * 重置所有配置为默认值
     */
    fun resetToDefaults() {
        sharedPreferences.edit().clear().apply()
    }
    
    /**
     * 获取所有当前配置的映射表
     */
    fun getCurrentConfigMap(): Map<String, Any> {
        return mapOf(
            "streamUrl" to getStreamUrl(),
            "streamKey" to getStreamKey(),
            "longSide" to getLongSide(),
            "shortSide" to getShortSide(),
            "videoBitrate" to getVideoBitrate(),
            "audioSampleRate" to getAudioSampleRate(),
            "audioBitrate" to getAudioBitrate()
        )
    }
}
```

---

### `app/src/main/java/ai/fd/thinklet/app/squid/run/DefaultConfig.kt`

```kotlin
package ai.fd.thinklet.app.squid.run

/**
 * 默认配置类
 * 包含应用启动时使用的默认参数，避免每次都需要通过命令行传递参数
 */
object DefaultConfig {
    
    // RTMP服务器配置
    const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
    const val DEFAULT_STREAM_KEY = "test_stream"
    
    // 视频配置
    const val DEFAULT_LONG_SIDE = 720
    const val DEFAULT_SHORT_SIDE = 480
    const val DEFAULT_ORIENTATION = "landscape" // "landscape" 或 "portrait"
    const val DEFAULT_VIDEO_BITRATE = 4096 // kbps
    
    // 音频配置
    const val DEFAULT_AUDIO_SAMPLE_RATE = 48000 // Hz
    const val DEFAULT_AUDIO_BITRATE = 128 // kbps
    const val DEFAULT_AUDIO_CHANNEL = "stereo" // "monaural" 或 "stereo"
    const val DEFAULT_ECHO_CANCELER = false
    const val DEFAULT_MIC_MODE = "android" // "android", "thinklet5", "thinklet6"
    
    // 其他配置
    const val DEFAULT_PREVIEW = true // 是否显示预览（建议关闭以节省电量）
    
    /**
     * 获取所有默认配置的映射表
     */
    fun getDefaultConfigMap(): Map<String, Any> {
        return mapOf(
            "streamUrl" to DEFAULT_STREAM_URL,
            "streamKey" to DEFAULT_STREAM_KEY,
            "longSide" to DEFAULT_LONG_SIDE,
            "shortSide" to DEFAULT_SHORT_SIDE,
            "orientation" to DEFAULT_ORIENTATION,
            "videoBitrate" to DEFAULT_VIDEO_BITRATE,
            "audioSampleRate" to DEFAULT_AUDIO_SAMPLE_RATE,
            "audioBitrate" to DEFAULT_AUDIO_BITRATE,
            "audioChannel" to DEFAULT_AUDIO_CHANNEL,
            "echoCanceler" to DEFAULT_ECHO_CANCELER,
            "micMode" to DEFAULT_MIC_MODE,
            "preview" to DEFAULT_PREVIEW
        )
    }
}
```

---

### `app/src/main/java/ai/fd/thinklet/app/squid/run/PermissionHelper.kt`

```kotlin
package ai.fd.thinklet.app.squid.run

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理助手类
 * 用于请求和检查应用所需的权限
 */
class PermissionHelper(private val activity: Activity) {
    
    companion object {
        const val PERMISSION_REQUEST_CODE = 1001
        
        /**
         * 应用所需的权限列表
         */
        val REQUIRED_PERMISSIONS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
                .takeIf { Build.VERSION.SDK_INT <= Build.VERSION_CODES.P }
        )
    }
    
    /**
     * 检查是否已获得所有必需权限
     */
    fun areAllPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 获取未授权的权限列表
     */
    fun getDeniedPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 请求所有必需的权限
     */
    fun requestPermissions() {
        val deniedPermissions = getDeniedPermissions()
        if (deniedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                deniedPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * 检查是否需要显示权限说明
     */
    fun shouldShowRequestPermissionRationale(): Boolean {
        return getDeniedPermissions().any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    
    /**
     * 获取权限的友好名称
     */
    fun getPermissionFriendlyName(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "摄像头"
            Manifest.permission.RECORD_AUDIO -> "麦克风"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "存储"
            else -> permission
        }
    }
    
    /**
     * 获取所有未授权权限的友好名称列表
     */
    fun getDeniedPermissionNames(): List<String> {
        return getDeniedPermissions().map { getPermissionFriendlyName(it) }
    }
}
```

---

### `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-feature android:name="android.hardware.camera.any" />

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Thinkletsquidrun"
        android:usesCleartextTraffic="true"
        tools:targetApi="31">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```


