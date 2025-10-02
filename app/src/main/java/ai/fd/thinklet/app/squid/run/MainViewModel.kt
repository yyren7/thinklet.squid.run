package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord
import ai.fd.thinklet.sdk.maintenance.camera.Angle
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.util.Log
import android.os.Build
import android.os.Environment
import android.view.Surface
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.pedro.common.ConnectChecker
import com.pedro.library.base.recording.RecordController
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.library.generic.GenericStream
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraOpenException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.LifecycleOwner

class MainViewModel(
    private val application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)

    val streamUrl: String? = savedState.get<String>("streamUrl") ?: DefaultConfig.DEFAULT_STREAM_URL

    /**
     * The key for RTMP streaming.
     */
    private val _streamKey = MutableStateFlow<String?>(null)
    val streamKey: StateFlow<String?> = _streamKey.asStateFlow()

    private val sharedPreferences: SharedPreferences = application.getSharedPreferences("StreamConfig", Context.MODE_PRIVATE)

    init {
        _streamKey.value = sharedPreferences.getString("streamKey", null)
            ?: savedState.get<String>("streamKey")
            ?: DefaultConfig.DEFAULT_STREAM_KEY
    }

    fun updateStreamKey(newStreamKey: String) {
        _streamKey.value = newStreamKey
        sharedPreferences.edit().putString("streamKey", newStreamKey).apply()
    }

    private val longSide: Int = savedState.get<Int>("longSide") ?: DefaultConfig.DEFAULT_LONG_SIDE
    private val shortSide: Int = savedState.get<Int>("shortSide") ?: DefaultConfig.DEFAULT_SHORT_SIDE
    private val orientation: Orientation? =
        Orientation.fromArgumentValue(savedState.get<String>("orientation") ?: DefaultConfig.DEFAULT_ORIENTATION)
    val videoBitrateBps: Int =
        savedState.get<Int>("videoBitrate")?.let { it * 1024 } ?: (DefaultConfig.DEFAULT_VIDEO_BITRATE * 1024)
    val videoFps: Int = savedState.get<Int>("videoFps") ?: DefaultConfig.DEFAULT_VIDEO_FPS
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

    private val _isRecording: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Recording duration is now calculated on the frontend, not needed here
    // private val _recordingDurationMs: MutableStateFlow<Long> = MutableStateFlow(0L)
    // val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()
    // private var recordingStartTime: Long = 0L
    // private var recordingDurationJob: Job? = null

    private val _isPreviewActive: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isPreviewActive: StateFlow<Boolean> = _isPreviewActive.asStateFlow()

    val isReadyForStreaming: StateFlow<Boolean> = streamKey.map { key ->
        !streamUrl.isNullOrBlank() && !key.isNullOrBlank() && isAllPermissionGranted()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = !streamUrl.isNullOrBlank() && !_streamKey.value.isNullOrBlank() && isAllPermissionGranted()
    )

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
     * Check if the device has an available camera.
     */
    private fun isCameraAvailable(): Boolean {
        return try {
            val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            Log.i("MainViewModel", "Found ${cameraIds.size} cameras")
            cameraIds.isNotEmpty()
        } catch (e: Exception) {
            // If any exception occurs, assume the camera is unavailable.
            Log.e("MainViewModel", "Camera check failed", e)
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Camera check failed: ${e.message}")
            )
            false
        }
    }

    @MainThread
    suspend fun maybePrepareStreaming() {
        Log.d("MainViewModel", "maybePrepareStreaming called")
        if (_isPrepared.value) {
            return
        }
        if (streamUrl == null || streamKey.value == null || !isAllPermissionGranted()) {
            _isPrepared.value = false
            return
        }

        try {
            // Add a delay to give the camera service time to initialize on some devices.
            delay(1000)

            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Camera resource is being enabled")
            )

            val cameraSource = Camera2Source(application)
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
                cameraSource,
                audioSource
            ).apply {
                getGlInterface().autoHandleOrientation = false
            }
            val isPrepared = try {
                // Note: The output size is converted by 90 degrees inside RootEncoder when the rotation
                // is portrait. Therefore, we intentionally pass `longSide` and `shortSide` to `width`
                // and `height` respectively so that it will be output at the correct size.
                val isVideoPrepared = localStream.prepareVideo(
                    width = longSide,
                    height = shortSide,
                    fps = videoFps,
                    bitrate = videoBitrateBps,
                    rotation = getDeviceRotation()
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
                    StreamingEvent("Failed to set streaming parameters: ${e.message}")
                )
                false
            } catch (e: Exception) {
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("Failed to prepare camera video stream: ${e.message}")
                )
                false
            }
            
            if (isPrepared) {
                stream = localStream
                _isPrepared.value = true
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("Camera resource has been started successfully")
                )
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("Camera video stream is ready")
                )
            } else {
                _isPrepared.value = false
            }
        } catch (e: CancellationException) {
            // Coroutine cancellation is a normal behavior, no need to report an error.
            // Re-throw it to be handled by the coroutine framework.
            Log.d("MainViewModel", "Camera preparation was cancelled")
            throw e
        } catch (e: Exception) {
            // Catch all exceptions related to camera initialization.
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Camera initialization failed: ${e.message}")
            )
            _isPrepared.value = false
        }
    }

    private fun createMicrophoneSource(isInitiallyMuted: Boolean): StandardMicrophoneSource {
        val microphoneSource = StandardMicrophoneSource()
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

    fun activityOnPause() {
        // 如果正在录制或推流，保持所有资源（相机、录制、推流）
        if (_isRecording.value || _isStreaming.value) {
            Log.w("MainViewModel", "Recording or streaming in progress, keeping all resources active")
            // 停止预览以节省资源，但保持相机、录制和推流
            // checkAndReleaseCamera() 会检查录制和推流状态，不会释放相机
            stopPreview()
            return
        }
        
        // 如果没有在录制或推流，正常清理所有资源
        stopStreaming()
        stopPreview()
        stream = null
        _isPrepared.value = false
    }

    fun activityOnResume() {
        // 如果正在录制或推流，它们的资源一直保持着，不需要恢复
        // 预览会由 SurfaceHolder.Callback 自动恢复，
        // 它会调用 maybePrepareStreaming -> startPreview
    }

    /**
     * Start streaming.
     * If the camera is not ready, it will be initialized automatically.
     */
    fun maybeStartStreaming(onResult: ((Boolean) -> Unit)? = null) {
        // Ensure the camera is ready
        ensureCameraReady {
            val isStreamingStarted = maybeStartStreamingInternal()
            _isStreaming.value = isStreamingStarted
            onResult?.invoke(isStreamingStarted)
        }
    }

    private fun maybeStartStreamingInternal(): Boolean {
        val streamSnapshot = stream
        if (streamUrl == null || streamKey.value == null || streamSnapshot == null) {
            return false
        }
        
        // If already streaming, return success directly.
        if (streamSnapshot.isStreaming) {
            return true
        }
        
        // If the previous connection failed or was in an error state, clean up the underlying state first.
        if (_connectionStatus.value == ConnectionStatus.FAILED || 
            _connectionStatus.value == ConnectionStatus.DISCONNECTED) {
            Log.d("MainViewModel", "Detected previous connection error state, cleaning up underlying stream state first")
            try {
                streamSnapshot.stopStream()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Exception occurred while cleaning up old state: ${e.message}")
            }
            _connectionStatus.value = ConnectionStatus.IDLE
        }
        
        streamSnapshot.startStream("$streamUrl/${streamKey.value}")
        return true
    }
    
    /**
     * Synchronous version of start streaming (for compatibility with old code).
     * Succeeds only if the camera is already prepared.
     */
    fun maybeStartStreamingSync(): Boolean {
        if (!_isPrepared.value) {
            Log.w("MainViewModel", "Camera is not ready")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Streaming failed: Camera is not ready")
            )
            return false
        }
        
        val isStreamingStarted = maybeStartStreamingInternal()
        _isStreaming.value = isStreamingStarted
        return isStreamingStarted
    }

    fun stopStreaming() {
        val streamSnapshot = stream ?: return
        streamSnapshot.stopStream()
        _isStreaming.value = false
        _connectionStatus.value = ConnectionStatus.IDLE
        // Check if camera resources need to be released
        checkAndReleaseCamera()
    }

    @MainThread
    fun startPreview(surface: Surface, width: Int, height: Int) {
        startPreviewJob?.cancel()
        startPreviewJob = viewModelScope.launch {
            // Ensure the camera is ready
            if (!_isPrepared.value) {
                Log.i("MainViewModel", "Starting preview: Camera not ready, initializing...")
                maybePrepareStreaming()
            }
            
            // Wait until the stream is prepared before starting the preview.
            isPrepared.first { it }

            val localStream = stream
            if (localStream == null) {
                Log.e("MainViewModel", "Error: Stream is not available even after being prepared.")
                return@launch
            }

            if (localStream.isOnPreview) {
                localStream.stopPreview()
            }
            Log.i("MainViewModel", "Starting preview on surface.")
            localStream.startPreview(surface, width, height)
            _isPreviewActive.value = true
        }
    }

    @MainThread
    fun stopPreview() {
        startPreviewJob?.cancel()
        val localStream = stream ?: return
        if (localStream.isOnPreview) {
            try {
                localStream.stopPreview()
                _isPreviewActive.value = false
                Log.d("MainViewModel", "Preview has been stopped")
            } catch (e: Exception) {
                // Catch exceptions like Surface being destroyed.
                Log.w("MainViewModel", "Exception occurred while stopping preview (Surface might be destroyed): ${e.message}")
                _isPreviewActive.value = false
            }
        } else {
            // Even if the preview is not active, ensure the state is correct.
            _isPreviewActive.value = false
        }
        // Check if camera resources need to be released
        checkAndReleaseCamera()
    }

    fun muteAudio() {
        _isAudioMuted.value = true
        val audioSource = stream?.audioSource ?: return
        when (audioSource) {
            is ThinkletMicrophoneSource -> audioSource.mute()
            is StandardMicrophoneSource -> audioSource.mute()
        }
    }

    fun unMuteAudio() {
        _isAudioMuted.value = false
        val audioSource = stream?.audioSource ?: return
        when (audioSource) {
            is ThinkletMicrophoneSource -> audioSource.unMute()
            is StandardMicrophoneSource -> audioSource.unMute()
        }
    }

    /**
     * Start recording video to a local file.
     * Recording and streaming share the same camera stream but output to different destinations.
     * If the camera is not ready, it will be initialized automatically.
     */
    fun startRecording(onResult: ((Boolean) -> Unit)? = null) {
        // Ensure the camera is ready
        ensureCameraReady {
            val result = startRecordingInternal()
            onResult?.invoke(result)
        }
    }

    private fun startRecordingInternal(): Boolean {
        val streamSnapshot = stream
        if (streamSnapshot == null) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording failed: Stream not ready")
            )
            return false
        }

        if (_isRecording.value) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording is already in progress")
            )
            return true
        }

        try {
            // Generate recording file path
            val recordFolder = File(
                application.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "SquidRun"
            )
            if (!recordFolder.exists()) {
                recordFolder.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val recordFile = File(recordFolder, "recording_$timestamp.mp4")
            val recordPath = recordFile.absolutePath

            // Start recording
            streamSnapshot.startRecord(
                recordPath, 
                null,  // RecordController.RecordTracks - use default configuration (record audio and video)
                object : RecordController.Listener {
                    override fun onStatusChange(status: RecordController.Status) {
                        // Recording status callback
                        when (status) {
                            RecordController.Status.STARTED -> {
                                _isRecording.value = true
                                // Duration timer removed - frontend now handles timing
                                // recordingStartTime = System.currentTimeMillis()
                                // startRecordingDurationTimer()
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording started: $recordPath")
                                )
                            }
                            RecordController.Status.STOPPED -> {
                                _isRecording.value = false
                                // Duration timer removed - frontend now handles timing
                                // stopRecordingDurationTimer()
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording stopped: $recordPath")
                                )
                                
                                // 在录制停止后计算并保存 MD5
                                // 关键：延迟执行，确保底层 MediaMuxer 完全将数据刷新到磁盘
                                viewModelScope.launch {
                                    try {
                                        // 等待 1 秒，确保文件完全写入磁盘
                                        // MediaMuxer 和文件系统可能需要时间来完成最后的写入操作
                                        delay(1000)
                                        
                                        // 验证文件是否存在且大小合理
                                        if (!recordFile.exists()) {
                                            Log.e("MainViewModel", "Recording file does not exist: ${recordFile.name}")
                                            return@launch
                                        }
                                        
                                        val fileSize = recordFile.length()
                                        if (fileSize < 1024) { // 文件小于 1KB，可能有问题
                                            Log.w("MainViewModel", "Recording file size is too small (${fileSize} bytes): ${recordFile.name}")
                                            // 即使文件很小，也继续计算 MD5，但记录警告
                                        }
                                        
                                        Log.i("MainViewModel", "Starting MD5 calculation for file: ${recordFile.name} (${fileSize} bytes)")
                                        val success = MD5Utils.calculateAndSaveMD5(recordFile)
                                        if (success) {
                                            Log.i("MainViewModel", "MD5 file created successfully for: ${recordFile.name}")
                                        } else {
                                            Log.w("MainViewModel", "Failed to create MD5 file for: ${recordFile.name}")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainViewModel", "Error while creating MD5 file for: ${recordFile.name}", e)
                                    }
                                }
                            }
                            RecordController.Status.RECORDING -> {
                                // Recording in progress
                            }
                            RecordController.Status.PAUSED -> {
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording paused")
                                )
                            }
                            RecordController.Status.RESUMED -> {
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording resumed")
                                )
                            }
                            else -> {
                                // Unknown or future status
                            }
                        }
                    }
                }
            )
            return true
        } catch (e: Exception) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to start recording: ${e.message}")
            )
            _isRecording.value = false
            return false
        }
    }

    /**
     * Stop recording.
     */
    fun stopRecording() {
        val streamSnapshot = stream ?: run {
            Log.w("MainViewModel", "Cannot stop recording: stream is null")
            _isRecording.value = false
            return
        }
        
        if (!_isRecording.value) {
            Log.w("MainViewModel", "Recording is not active")
            return
        }
        
        try {
            Log.i("MainViewModel", "Stopping recording...")
            streamSnapshot.stopRecord()
            // The status will be updated in the callback.
            // 延迟检查相机资源释放，给录制停止回调足够的时间来完成 MD5 计算
            viewModelScope.launch {
                // 等待更长时间，确保 MD5 计算完成
                delay(2000) // 等待 2 秒：100ms 状态更新 + 1000ms MD5 延迟 + 额外缓冲
                checkAndReleaseCamera()
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to stop recording", e)
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to stop recording: ${e.message}")
            )
            _isRecording.value = false
            // 即使失败，也延迟一下再检查资源释放
            viewModelScope.launch {
                delay(500)
                checkAndReleaseCamera()
            }
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
            // Note: The underlying stream state is cleaned up by the user actively calling stopStreaming() or before the next startStream.
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

    private fun getDeviceRotation(): Int {
        return angle.current()
    }

    /**
     * Check if camera resources need to be released.
     * When streaming, recording, and preview are all turned off, completely release the camera to save power.
     */
    private fun checkAndReleaseCamera() {
        val shouldRelease = !_isStreaming.value && !_isRecording.value && !_isPreviewActive.value
        if (shouldRelease) {
            Log.i("MainViewModel", "All features are off, releasing camera resources to save power")
            releaseCamera()
        }
    }

    /**
     * Completely release camera and encoder resources.
     */
    private fun releaseCamera() {
        stream?.let { localStream ->
            // 分别处理每个清理步骤，确保录制停止不会被跳过
            
            // 1. 首先停止录制（最关键，必须确保文件正确关闭）
            if (localStream.isRecording) {
                try {
                    Log.i("MainViewModel", "Stopping recording before releasing camera")
                    localStream.stopRecord()
                    // 给一点时间让录制完全停止并写入文件
                    Thread.sleep(200)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to stop recording, but will continue cleanup", e)
                }
            }
            
            // 2. 停止推流
            if (localStream.isStreaming) {
                try {
                    localStream.stopStream()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to stop streaming", e)
                }
            }
            
            // 3. 停止预览
            if (localStream.isOnPreview) {
                try {
                    localStream.stopPreview()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to stop preview", e)
                }
            }
            
            // 4. 最后释放资源
            try {
                localStream.release()
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("Camera resources have been released")
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to release camera resources", e)
            }
        }
        stream = null
        _isPrepared.value = false
        _isPreviewActive.value = false
    }

    /**
     * Ensure the camera is ready (if needed).
     * This is for cases where Preview=false, to automatically prepare the camera for streaming or recording.
     */
    fun ensureCameraReady(onReady: () -> Unit) {
        if (_isPrepared.value) {
            onReady()
            return
        }
        
        viewModelScope.launch {
            maybePrepareStreaming()
            isPrepared.first { it }
            onReady()
        }
    }

    /**
     * Recording duration timer removed - frontend now handles timing
     */
    // private fun startRecordingDurationTimer() {
    //     recordingDurationJob?.cancel()
    //     recordingDurationJob = viewModelScope.launch {
    //         while (true) {
    //             delay(1000) // Update once per second
    //             val duration = System.currentTimeMillis() - recordingStartTime
    //             _recordingDurationMs.value = duration
    //         }
    //     }
    // }

    // private fun stopRecordingDurationTimer() {
    //     recordingDurationJob?.cancel()
    //     recordingDurationJob = null
    //     _recordingDurationMs.value = 0L
    //     recordingStartTime = 0L
    // }

    /**
     * 当 ViewModel 被销毁时调用（应用完全退出时）
     * 确保正在进行的录制被安全停止
     */
    override fun onCleared() {
        super.onCleared()
        Log.i("MainViewModel", "ViewModel is being cleared")
        
        // 如果正在录制，必须先停止录制
        if (_isRecording.value) {
            Log.w("MainViewModel", "Recording is still active during onCleared, forcing stop")
            stream?.let { localStream ->
                try {
                    if (localStream.isRecording) {
                        localStream.stopRecord()
                        // 同步等待一小段时间，确保文件关闭
                        Thread.sleep(500)
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to stop recording in onCleared", e)
                }
            }
        }
        
        // 清理所有资源
        releaseCamera()
    }

    companion object {
        private const val STREAMING_EVENT_BUFFER_SIZE = 15
    }
}
