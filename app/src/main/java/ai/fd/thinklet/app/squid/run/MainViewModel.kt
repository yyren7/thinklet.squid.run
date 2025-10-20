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
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainViewModel(
    private val application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    val ttsManager: TTSManager by lazy {
        (application as SquidRunApplication).ttsManager
    }
    private val ledController = LedController(application)
    private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)

    /**
     * The server IP address for RTMP streaming.
     */
    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp: StateFlow<String?> = _serverIp.asStateFlow()

    /**
     * The complete RTMP stream URL, dynamically generated from server IP.
     */
    val streamUrl: StateFlow<String> = _serverIp.map { ip ->
        "rtmp://$ip:1935/thinklet.squid.run"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * The key for RTMP streaming.
     */
    private val _streamKey = MutableStateFlow<String?>(null)
    val streamKey: StateFlow<String?> = _streamKey.asStateFlow()

    private val sharedPreferences: SharedPreferences = application.getSharedPreferences("StreamConfig", Context.MODE_PRIVATE)

    init {
        // Extract IP address from DEFAULT_STREAM_URL or savedState
        val defaultIp = extractIpFromUrl(DefaultConfig.DEFAULT_STREAM_URL) ?: "192.168.16.88"
        val savedIp = savedState.get<String>("serverIp")
        
        _serverIp.value = sharedPreferences.getString("serverIp", null)
            ?: savedIp
            ?: defaultIp
    }

    /**
     * Extract IP address from RTMP URL.
     * Format: rtmp://IP:PORT/PATH
     */
    private fun extractIpFromUrl(url: String): String? {
        return try {
            val pattern = "rtmp://([^:]+):".toRegex()
            pattern.find(url)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    fun updateServerIp(newIp: String) {
        if (_serverIp.value == newIp) return

        _serverIp.value = newIp
        sharedPreferences.edit().putString("serverIp", newIp).apply()

        // If streaming is active or attempting to connect, stop it.
        // User should manually restart streaming to the new address if needed.
        if (_isStreaming.value || _connectionStatus.value == ConnectionStatus.CONNECTING || 
            _connectionStatus.value == ConnectionStatus.FAILED || _connectionStatus.value == ConnectionStatus.DISCONNECTED) {
            Log.i("MainViewModel", "Server IP changed, stopping current stream.")
            stopStreaming()
        }
    }

    fun setStreamKey(newStreamKey: String) {
        _streamKey.value = newStreamKey
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

    val isReadyForStreaming: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        streamUrl,
        streamKey
    ) { url, key ->
        !url.isNullOrBlank() && !key.isNullOrBlank() && isAllPermissionGranted()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
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
    
    // Flag to prevent concurrent camera preparation
    @Volatile
    private var isPreparing: Boolean = false
    
    // Flag to prevent concurrent recording operations
    @Volatile
    private var isRecordingOperationInProgress: Boolean = false
    
    // Bitrate monitoring
    @Volatile
    private var consecutiveZeroBitrateCount = 0
    @Volatile
    private var lastBitrateWarningTime = 0L
    private val ZERO_BITRATE_WARNING_THRESHOLD = 5  // Warn after 5 consecutive zeros
    private val WARNING_COOLDOWN_MS = 10000L  // Only warn once every 10 seconds


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
        
        // Prevent concurrent preparation
        if (isPreparing) {
            Log.d("MainViewModel", "Camera preparation already in progress, skipping")
            return
        }
        
        if (streamUrl.value.isBlank() || streamKey.value.isNullOrBlank() || !isAllPermissionGranted()) {
            _isPrepared.value = false
            return
        }

        isPreparing = true
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
                ConnectionCheckerImpl(this, streamingEventMutableSharedFlow, _isStreaming, _connectionStatus),
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
        } finally {
            // Always reset the preparing flag when done
            isPreparing = false
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
        Log.i("MainViewModel", "Activity pausing, cleaning up resources")
        val streamSnapshot = stream
        if (streamSnapshot != null) {
            // 停止所有活动
            if (streamSnapshot.isStreaming) {
                streamSnapshot.stopStream()
                _isStreaming.value = false
                _connectionStatus.value = ConnectionStatus.IDLE
            }
            if (streamSnapshot.isOnPreview) {
                streamSnapshot.stopPreview()
                _isPreviewActive.value = false
            }
            // 释放资源（只调用一次）
            releaseCamera()
        } else {
            // stream已经为null，只需要确保状态正确
            _isPrepared.value = false
            _isPreviewActive.value = false
        }
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
        val currentStreamUrl = streamUrl.value
        val currentStreamKey = streamKey.value
        
        if (currentStreamUrl.isBlank() || currentStreamKey.isNullOrBlank() || streamSnapshot == null) {
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
        
        // Try to start streaming with error handling for network issues
        return try {
            streamSnapshot.startStream("$currentStreamUrl/$currentStreamKey")
            true
        } catch (e: CancellationException) {
            // Network selector closed or cancelled - this can happen on first connection after WiFi connects
            // This usually means the underlying RTMP client's network resources are in a bad state
            Log.e("MainViewModel", "Failed to start stream due to cancellation (selector closed): ${e.message}")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Stream start failed: Network resources corrupted. Resetting stream...")
            )
            
            // The stream object's network resources are corrupted, we need to release and recreate it
            // IMPORTANT: Mark as not prepared FIRST to prevent concurrent access during cleanup
            _isPrepared.value = false
            stream = null
            
            // Release the corrupted stream in background
            // Note: We already set stream=null above, so no new operations can use this corrupted stream
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    streamSnapshot.release()
                    streamingEventMutableSharedFlow.tryEmit(
                        StreamingEvent("Stream reset complete. Please try streaming again.")
                    )
                } catch (releaseException: Exception) {
                    Log.e("MainViewModel", "Failed to release corrupted stream", releaseException)
                }
            }
            
            _connectionStatus.value = ConnectionStatus.FAILED
            false
        } catch (e: Exception) {
            // Other exceptions during stream start
            Log.e("MainViewModel", "Failed to start stream: ${e.message}", e)
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to start stream: ${e.message}")
            )
            _connectionStatus.value = ConnectionStatus.FAILED
            false
        }
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
     * 
     * Thread-safe: Uses operation lock to prevent concurrent recording operations.
     */
    fun startRecording(onResult: ((Boolean) -> Unit)? = null) {
        Log.i("MainViewModel", "📹 Recording start requested. Current state: isRecording=${_isRecording.value}, operationInProgress=$isRecordingOperationInProgress")
        
        // Check if another recording operation is already in progress
        if (isRecordingOperationInProgress) {
            Log.w("MainViewModel", "⚠️ Recording operation already in progress, ignoring request")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording operation already in progress")
            )
            onResult?.invoke(false)
            return
        }
        
        // Check if already recording
        if (_isRecording.value) {
            Log.w("MainViewModel", "⚠️ Recording is already active, ignoring request")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording is already in progress")
            )
            onResult?.invoke(true)
            return
        }
        
        // Set operation lock
        isRecordingOperationInProgress = true
        Log.d("MainViewModel", "🔒 Recording operation lock acquired")
        
        // Ensure the camera is ready
        ensureCameraReady {
            val result = startRecordingInternal()
            // Release operation lock after internal call completes
            isRecordingOperationInProgress = false
            Log.d("MainViewModel", "🔓 Recording operation lock released, result=$result")
            onResult?.invoke(result)
        }
    }

    private fun startRecordingInternal(): Boolean {
        Log.d("MainViewModel", "🎬 startRecordingInternal: Initiating recording")
        
        val streamSnapshot = stream
        if (streamSnapshot == null) {
            Log.e("MainViewModel", "❌ Recording failed: Stream not ready")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording failed: Stream not ready")
            )
            return false
        }

        // Double-check recording state (defensive programming)
        if (_isRecording.value) {
            Log.w("MainViewModel", "⚠️ Recording is already in progress (double-check)")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording is already in progress")
            )
            return true
        }

        // ⚠️ LED控制已移至回调中，确保与实际录像状态同步
        // LED will be started in RecordController.Status.STARTED callback

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
                        Log.d("MainViewModel", "📹 Recording status changed: $status")
                        when (status) {
                            RecordController.Status.STARTED -> {
                                Log.i("MainViewModel", "✅ Recording STARTED successfully")
                                _isRecording.value = true
                                
                                // ✅ 在录像真正开始后才启动LED闪烁
                                ledController.startLedBlinking()
                                Log.d("MainViewModel", "💡 LED blinking started")
                                
                                // Duration timer removed - frontend now handles timing
                                // recordingStartTime = System.currentTimeMillis()
                                // startRecordingDurationTimer()
                                ttsManager.speakRecordingStarted()
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording started: $recordPath")
                                )
                            }
                            RecordController.Status.STOPPED -> {
                                Log.i("MainViewModel", "⏹️ Recording STOPPED")
                                _isRecording.value = false
                                ledController.stopLedBlinking()
                                Log.d("MainViewModel", "💡 LED blinking stopped")
                                // Duration timer removed - frontend now handles timing
                                // stopRecordingDurationTimer()
                                ttsManager.speakRecordingFinished()
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording stopped: $recordPath")
                                )
                                
                                // 在录制停止后计算并保存 MD5
                                // 关键：延迟执行，确保底层 MediaMuxer 完全将数据刷新到磁盘
                                viewModelScope.launch(Dispatchers.IO) {
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
            Log.i("MainViewModel", "📹 Recording API call successful, waiting for STARTED callback")
            return true
        } catch (e: Exception) {
            Log.e("MainViewModel", "❌ Failed to start recording", e)
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to start recording: ${e.message}")
            )
            _isRecording.value = false
            // LED hasn't been started yet (only starts in STARTED callback), so no need to stop it
            // But add defensive stop just in case
            ledController.stopLedBlinking()
            return false
        }
    }

    /**
     * Stop recording.
     * Thread-safe: Uses operation lock to prevent concurrent operations.
     * 
     * @param onResult Optional callback with result: true if stop was initiated, false if failed or not recording
     */
    fun stopRecording(onResult: ((Boolean) -> Unit)? = null) {
        Log.i("MainViewModel", "⏹️ Recording stop requested. Current state: isRecording=${_isRecording.value}, operationInProgress=$isRecordingOperationInProgress")
        
        // Check if another recording operation is in progress
        if (isRecordingOperationInProgress) {
            Log.w("MainViewModel", "⚠️ Recording operation in progress, cannot stop yet")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Recording operation in progress, please wait")
            )
            onResult?.invoke(false)
            return
        }
        
        val streamSnapshot = stream ?: run {
            Log.w("MainViewModel", "⚠️ Cannot stop recording: stream is null")
            _isRecording.value = false
            // Ensure LED is stopped even if stream is null
            ledController.stopLedBlinking()
            onResult?.invoke(false)
            return
        }
        
        if (!_isRecording.value) {
            Log.w("MainViewModel", "⚠️ Recording is not active, ignoring stop request")
            onResult?.invoke(false)
            return
        }
        
        try {
            Log.i("MainViewModel", "🛑 Stopping recording...")
            streamSnapshot.stopRecord()
            // The status will be updated in the callback.
            // 延迟检查相机资源释放，给录制停止回调足够的时间来完成 MD5 计算
            viewModelScope.launch {
                // 等待更长时间，确保 MD5 计算完成
                delay(2000) // 等待 2 秒：100ms 状态更新 + 1000ms MD5 延迟 + 额外缓冲
                checkAndReleaseCamera()
            }
            onResult?.invoke(true)
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
            onResult?.invoke(false)
        }
    }

    private class ConnectionCheckerImpl(
        private val viewModel: MainViewModel,
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
            viewModel.stopStreaming()
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
            viewModel.stopStreaming()
            // Note: The underlying stream state is cleaned up by the user actively calling stopStreaming() or before the next startStream.
        }

        override fun onNewBitrate(bitrate: Long) {
            // Bitrate monitoring and diagnostics
            if (bitrate == 0L) {
                viewModel.onZeroBitrateDetected()
            } else {
                viewModel.onNonZeroBitrateDetected()
            }
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
     * 这个方法可以在主线程调用，但耗时操作会在后台线程执行
     */
    private fun releaseCamera() {
        val localStream = stream ?: return
        
        // 立即将 stream 设为 null，防止重复释放（必须在启动协程前完成）
        stream = null
        _isPrepared.value = false
        _isPreviewActive.value = false
        
        Log.i("MainViewModel", "Starting camera release process")
        
        // 在后台线程执行资源释放，避免阻塞主线程
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 首先停止录制（最关键，必须确保文件正确关闭）
                if (localStream.isRecording) {
                    try {
                        Log.i("MainViewModel", "Stopping recording before releasing camera")
                        localStream.stopRecord()
                        // 等待录制完全停止，给文件系统时间释放锁
                        delay(200)
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
                    Log.i("MainViewModel", "Camera released successfully")
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to release camera resources", e)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Unexpected error during camera release", e)
            }
        }
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
     * 
     * 注意：这个方法在主线程同步执行，但我们需要给录制足够时间停止
     * 使用 runBlocking 在 IO 线程执行，避免阻塞主线程的其他操作
     */
    override fun onCleared() {
        super.onCleared()
        Log.i("MainViewModel", "ViewModel is being cleared")
        
        stream?.let { localStream ->
            // 使用 runBlocking + Dispatchers.IO 在后台线程同步等待
            // 这样不会阻塞主线程的消息队列，但确保录制正确停止
            try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    // 如果正在录制，必须先停止录制
                    if (localStream.isRecording) {
                        Log.w("MainViewModel", "Recording is still active during onCleared, forcing stop")
                        try {
                            localStream.stopRecord()
                            // 等待文件系统释放锁，确保文件完整写入
                            delay(500)
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to stop recording in onCleared", e)
                        }
                    }
                    
                    // 停止推流
                    if (localStream.isStreaming) {
                        try {
                            localStream.stopStream()
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to stop streaming in onCleared", e)
                        }
                    }
                    
                    // 停止预览
                    if (localStream.isOnPreview) {
                        try {
                            localStream.stopPreview()
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to stop preview in onCleared", e)
                        }
                    }
                    
                    // 释放资源
                    try {
                        localStream.release()
                        Log.i("MainViewModel", "All camera resources released in onCleared")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to release camera resources in onCleared", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Exception during cleanup in onCleared", e)
            }
        }
        
        stream = null
        _isPrepared.value = false
        _isPreviewActive.value = false
    }

    fun showToast(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(application, message, Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Called when zero bitrate is detected
     */
    internal fun onZeroBitrateDetected() {
        consecutiveZeroBitrateCount++
        
        if (consecutiveZeroBitrateCount >= ZERO_BITRATE_WARNING_THRESHOLD) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBitrateWarningTime >= WARNING_COOLDOWN_MS) {
                lastBitrateWarningTime = currentTime
                
                // Diagnose the issue
                val diagnosis = diagnoseBitrateIssue()
                Log.w("MainViewModel", "⚠️ Zero bitrate detected for $consecutiveZeroBitrateCount consecutive reports. Diagnosis: $diagnosis")
                
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("⚠️ Streaming issue: $diagnosis")
                )
            }
        }
    }
    
    /**
     * Called when non-zero bitrate is detected
     */
    internal fun onNonZeroBitrateDetected() {
        if (consecutiveZeroBitrateCount > 0) {
            Log.d("MainViewModel", "✅ Bitrate recovered after $consecutiveZeroBitrateCount zero reports")
        }
        consecutiveZeroBitrateCount = 0
    }
    
    /**
     * Diagnose why bitrate is zero
     */
    private fun diagnoseBitrateIssue(): String {
        val streamSnapshot = stream
        
        return when {
            streamSnapshot == null -> "No stream object (internal error)"
            
            !streamSnapshot.isOnPreview && shouldShowPreview -> 
                "Camera preview stopped unexpectedly"
            
            !streamSnapshot.isStreaming -> 
                "Stream disconnected (checking network)"
            
            _connectionStatus.value != ConnectionStatus.CONNECTED -> 
                "Connection not stable (status: ${_connectionStatus.value})"
            
            else -> 
                "Low/No data from camera or network congestion. Check WiFi signal."
        }
    }

    companion object {
        private const val STREAMING_EVENT_BUFFER_SIZE = 15
    }
}
