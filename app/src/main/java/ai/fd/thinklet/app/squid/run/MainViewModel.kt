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

    private val statusReportingManager: StatusReportingManager by lazy {
        (application as SquidRunApplication).statusReportingManager
    }

    val ttsManager: SherpaOnnxTTSManager by lazy {
        (application as SquidRunApplication).ttsManager
    }
    
    private val storageManager: StorageManager by lazy {
        (application as SquidRunApplication).storageManager.apply {
            setStorageCapacityListener(object : StorageCapacityListener {
                override fun onStorageCapacityUpdated(
                    internalCapacity: StorageCapacity?,
                    sdCardCapacity: StorageCapacity?
                ) {
                    Log.i("MainViewModel", "📊 Storage capacity updated - Internal: ${internalCapacity?.availableGB?.let { String.format("%.2f", it) } ?: "N/A"} GB, SD: ${sdCardCapacity?.availableGB?.let { String.format("%.2f", it) } ?: "N/A"} GB")
                }
            })
        }
    }
    private val ledController = LedController(application)
    private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)

    /**
     * The server IP address for streaming.
     */
    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp: StateFlow<String?> = _serverIp.asStateFlow()

    /**
     * Streaming protocol (rtmp or srt)
     */
    private val _streamProtocol = MutableStateFlow<String>("rtmp")
    val streamProtocol: StateFlow<String> = _streamProtocol.asStateFlow()

    /**
     * The complete stream URL, dynamically generated from server IP and protocol.
     * SRT (default): srt://ip:8890
     * RTMP (fallback): rtmp://ip:1935/thinklet.squid.run
     */
    val streamUrl: StateFlow<String> = kotlinx.coroutines.flow.combine(
        _serverIp,
        _streamProtocol
    ) { ip, protocol ->
        when (protocol) {
            "srt" -> "srt://$ip:8890"
            else -> "rtmp://$ip:1935/thinklet.squid.run"
        }
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
        
        // Load protocol preference (default to SRT for better weak network performance)
        _streamProtocol.value = sharedPreferences.getString("streamProtocol", "srt") ?: "srt"
    }

    /**
     * Extract IP address from stream URL.
     * Format: srt://IP:PORT or rtmp://IP:PORT/PATH (default: SRT)
     */
    private fun extractIpFromUrl(url: String): String? {
        return try {
            val pattern = "(rtmp|srt)://([^:]+):".toRegex()
            pattern.find(url)?.groupValues?.get(2)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Update streaming protocol (rtmp or srt)
     */
    fun updateStreamProtocol(protocol: String) {
        if (protocol != "rtmp" && protocol != "srt") {
            Log.w("MainViewModel", "Invalid protocol: $protocol, using rtmp")
            return
        }
        
        if (_streamProtocol.value == protocol) return
        
        _streamProtocol.value = protocol
        sharedPreferences.edit().putString("streamProtocol", protocol).apply()
        
        // If streaming is active, stop it
        if (_isStreaming.value || _connectionStatus.value == ConnectionStatus.CONNECTING) {
            Log.i("MainViewModel", "Protocol changed to $protocol, stopping current stream")
            stopStreaming()
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
    
    /**
     * Set storage location for recording files.
     * @param storageType "internal" or "sd_card"
     */
    fun setRecordingStorageType(storageType: String) {
        storageManager.setStorageType(storageType)
        Log.i("MainViewModel", "Recording storage type set to: $storageType")
    }
    
    /**
     * Get current storage type for recording files.
     * @return "internal" or "sd_card"
     */
    fun getRecordingStorageType(): String {
        return storageManager.getCurrentStorageType()
    }
    
    /**
     * Check if SD card is available.
     * @return true if SD card is available and writable
     */
    fun isSdCardAvailable(): Boolean {
        return storageManager.isSdCardAvailable()
    }
    
    /**
     * Check storage capacity for both internal storage and SD card.
     * This method can be called manually, e.g., during app initialization.
     */
    fun checkStorageCapacity() {
        storageManager.checkAndNotifyCapacity("AppInitialization")
    }

    private val longSide: Int = savedState.get<Int>("longSide") ?: DefaultConfig.DEFAULT_LONG_SIDE
    private val shortSide: Int = savedState.get<Int>("shortSide") ?: DefaultConfig.DEFAULT_SHORT_SIDE
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

    // Recording-specific settings
    val recordVideoWidth: Int = savedState.get<Int>("recordVideoWidth") ?: DefaultConfig.DEFAULT_RECORD_VIDEO_WIDTH
    val recordVideoHeight: Int = savedState.get<Int>("recordVideoHeight") ?: DefaultConfig.DEFAULT_RECORD_VIDEO_HEIGHT
    val recordBitrateBps: Int = savedState.get<Int>("recordBitrate")?.let { it * 1024 } ?: (DefaultConfig.DEFAULT_RECORD_VIDEO_BITRATE * 1024)
    
    // Recording segmentation settings
    val isRecordingSegmentationEnabled: Boolean = savedState.get<Boolean>("enableRecordingSegmentation") ?: DefaultConfig.DEFAULT_ENABLE_RECORDING_SEGMENTATION
    val segmentSizeBytes: Long = savedState.get<Int>("segmentSizeMB")?.let { it * 1024L * 1024L } ?: DefaultConfig.DEFAULT_SEGMENT_SIZE_BYTES

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

    /**
     * Get streaming width based on device type.
     * For PORTRAIT devices: shortSide x longSide (9:16)
     * For LANDSCAPE devices: longSide x shortSide (16:9)
     */
    val width: Int
        get() = when (statusReportingManager.deviceType) {
            DeviceType.PORTRAIT -> shortSide
            DeviceType.LANDSCAPE -> longSide
        }

    /**
     * Get streaming height based on device type.
     * For PORTRAIT devices: shortSide x longSide (9:16)
     * For LANDSCAPE devices: longSide x shortSide (16:9)
     */
    val height: Int
        get() = when (statusReportingManager.deviceType) {
            DeviceType.PORTRAIT -> longSide
            DeviceType.LANDSCAPE -> shortSide
        }

    /**
     * Get displayed record video width based on device type.
     * For PORTRAIT devices, swap width and height to show the actual recording dimensions.
     */
    val displayRecordVideoWidth: Int
        get() = when (statusReportingManager.deviceType) {
            DeviceType.PORTRAIT -> recordVideoHeight
            DeviceType.LANDSCAPE -> recordVideoWidth
        }

    /**
     * Get displayed record video height based on device type.
     * For PORTRAIT devices, swap width and height to show the actual recording dimensions.
     */
    val displayRecordVideoHeight: Int
        get() = when (statusReportingManager.deviceType) {
            DeviceType.PORTRAIT -> recordVideoWidth
            DeviceType.LANDSCAPE -> recordVideoHeight
        }

    private var stream: GenericStream? = null

    private var startPreviewJob: Job? = null
    
    // Flag to prevent concurrent camera preparation
    @Volatile
    private var isPreparing: Boolean = false
    
    // Flag to prevent concurrent recording operations
    @Volatile
    private var isRecordingOperationInProgress: Boolean = false
    
    // Recording segmentation support
    private val recordingSegmentManager: RecordingSegmentManager by lazy {
        RecordingSegmentManager(
            maxSegmentSizeBytes = segmentSizeBytes, // Configurable segment size
            checkIntervalMs = 5000L, // Check every 5 seconds
            triggerThresholdRatio = 0.95f // Trigger at 95% of max size
        ).apply {
            // Set callback for segment switching
            onSegmentSwitchNeeded = { currentFile, nextFilePath ->
                handleSegmentSwitch(currentFile, nextFilePath)
            }
        }
    }
    
    // Track current recording file for segment management
    private var currentRecordingFile: File? = null
    
    // Flag to indicate if we're in the middle of a segment switch
    @Volatile
    private var isSegmentSwitching: Boolean = false
    
    // Store next file path for segment switching (set before stopRecord, used in STOPPED callback)
    private var nextSegmentFilePath: String? = null
    
    // Bitrate monitoring
    @Volatile
    private var consecutiveZeroBitrateCount = 0
    @Volatile
    private var lastBitrateWarningTime = 0L
    private val ZERO_BITRATE_WARNING_THRESHOLD = 3  // Warn after 3 consecutive zeros (~3 seconds)
    private val ZERO_BITRATE_AUTO_STOP_THRESHOLD = 10  // Auto-stop after 10 consecutive zeros (~10 seconds)
    private val WARNING_COOLDOWN_MS = 5000L  // Only warn once every 5 seconds
    
    // Storage capacity monitoring during recording
    private var recordingCapacityCheckJob: Job? = null
    private val RECORDING_CAPACITY_CHECK_INTERVAL_MS = 5000L  // Check every 5 seconds during recording
    @Volatile
    private var isHandlingStorageSwitch: Boolean = false  // Flag to prevent concurrent storage switch handling
    
    // Battery level monitoring
    private val LOW_BATTERY_THRESHOLD = 5  // Low battery threshold: 5%
    private val _currentBatteryLevel = MutableStateFlow(-1)
    private val _isBatteryCharging = MutableStateFlow(false)


    init {
        // Initialize angle based on device type.
        // Device type is determined by device ID prefix (P for portrait, M for landscape).
        when (statusReportingManager.deviceType) {
            DeviceType.LANDSCAPE -> angle.setLandscape()
            DeviceType.PORTRAIT -> angle.setPortrait()
        }
        
        // Start file list monitoring
        storageManager.startFileListMonitoring()
        
        // Set up battery level listener
        statusReportingManager.setBatteryLevelListener(object : BatteryLevelListener {
            override fun onBatteryLevelChanged(level: Int, isCharging: Boolean) {
                _currentBatteryLevel.value = level
                _isBatteryCharging.value = isCharging
                
                // Check if we need to stop recording due to low battery
                if (level <= LOW_BATTERY_THRESHOLD && !isCharging && _isRecording.value) {
                    Log.w("MainViewModel", "⚠️ Battery level critically low ($level%), stopping recording")
                    viewModelScope.launch(Dispatchers.Main) {
                        streamingEventMutableSharedFlow.tryEmit(
                            StreamingEvent("Battery critically low ($level%), stopping recording")
                        )
                        ttsManager.speak("Battery critically low. Stopping recording.")
                        stopRecording { success ->
                            if (success) {
                                Log.i("MainViewModel", "✅ Recording stopped due to low battery")
                            } else {
                                Log.e("MainViewModel", "❌ Failed to stop recording on low battery")
                            }
                        }
                    }
                }
            }
        })
        
        // Observe recording state to start/stop capacity monitoring
        viewModelScope.launch {
            _isRecording.collect { isRecording ->
                if (isRecording) {
                    startRecordingCapacityMonitoring()
                } else {
                    stopRecordingCapacityMonitoring()
                }
            }
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
    suspend fun prepareSources() {
        Log.d("MainViewModel", "prepareSources called")
        if (_isPrepared.value) {
            return
        }
        
        // Prevent concurrent preparation
        if (isPreparing) {
            Log.d("MainViewModel", "Camera preparation already in progress, skipping")
            return
        }
        
        if (!isAllPermissionGranted()) {
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

            // ================= Refactored Video Setup Logic =================
            // Unify video rotation and dimension handling to resolve scattered code issues.
            val (videoWidth, videoHeight, rotation, finalRecordWidth, finalRecordHeight) = when (statusReportingManager.deviceType) {
                DeviceType.PORTRAIT -> {
                    Quintuple(longSide, shortSide, getDeviceRotation(), recordVideoWidth, recordVideoHeight)
                }
                DeviceType.LANDSCAPE -> {
                    // M-series (Landscape device): Use the original logic.
                    Quintuple(longSide, shortSide, getDeviceRotation(), recordVideoWidth, recordVideoHeight)
                }
            }
            // =================================================================
            
            // Add detailed logs to help debug dimension conversion.
            Log.i("MainViewModel", "Video preparation - Device: ${statusReportingManager.deviceType}")
            Log.i("MainViewModel", "Streaming dimensions: ${videoWidth}x${videoHeight}")
            Log.i("MainViewModel", "Recording dimensions: ${finalRecordWidth}x${finalRecordHeight}")
            Log.i("MainViewModel", "Original record config: ${recordVideoWidth}x${recordVideoHeight}")

            val isPrepared = try {
                val isVideoPrepared = localStream.prepareVideo(
                    width = videoWidth,
                    height = videoHeight,
                    fps = videoFps,
                    bitrate = videoBitrateBps,
                    rotation = rotation,
                    recordWidth = finalRecordWidth,
                    recordHeight = finalRecordHeight,
                    recordBitrate = recordBitrateBps
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
        // As per user requirement, keep all resources active even when paused, as this is a single-purpose device.
        Log.i("MainViewModel", "Activity pausing, but keeping all resources active as per requirement.")
    }

    fun activityOnResume() {
        // If recording or streaming, their resources are always kept, no need to restore.
        // The preview will be automatically restored by SurfaceHolder.Callback,
        // which will call maybePrepareStreaming -> startPreview.
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
            // Build stream URL based on protocol
            val fullStreamUrl = if (_streamProtocol.value == "srt") {
                // SRT format: srt://ip:port?streamid=publish:path/key
                // MediaMTX requires streamid format: action:pathname where action is 'publish' or 'read'

                //
                // Optimal SRT parameters for industrial weak network environments
                // latency: (ms) Increased latency to allow more time for retransmission and mesh WiFi handover.
                //   - 10000ms (10 seconds) allows seamless mesh router switching with extra margin
                //   - Combined with PC-side adaptive playback rate, the stream will auto-catchup after network recovery
                // maxbw: (bytes/s) Maximum bandwidth limit, set slightly above the total bitrate to prevent network congestion.
                // smoother: Transmission smoothing algorithm to prevent data bursts.
                //
                val totalBitrateBps = videoBitrateBps + audioBitrateBps
                val maxBwBytesPerSecond = (totalBitrateBps * 1.25 / 8).toLong() // Set maxbw to 125% of total bitrate, converted to bytes/s

                "$currentStreamUrl?streamid=publish:thinklet.squid.run/$currentStreamKey&latency=10000&maxbw=$maxBwBytesPerSecond&smoother=live"
            } else {
                // RTMP format: rtmp://ip:port/path/key
                "$currentStreamUrl/$currentStreamKey"
            }
            streamSnapshot.startStream(fullStreamUrl)
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
        val streamSnapshot = stream
        try {
            streamSnapshot?.stopStream()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error while stopping stream", e)
        } finally {
            // Always reset state even if stopStream() fails to ensure consistent state
            _isStreaming.value = false
            _connectionStatus.value = ConnectionStatus.IDLE
            // Check if camera resources need to be released
            checkAndReleaseCamera()
        }
    }

    @MainThread
    fun startPreview(surface: Surface, width: Int, height: Int) {
        startPreviewJob?.cancel()
        startPreviewJob = viewModelScope.launch {
            // Ensure the camera is ready
            if (!_isPrepared.value) {
                Log.i("MainViewModel", "Starting preview: Camera not ready, initializing...")
                prepareSources()
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

        // ⚠️ LED control has been moved to the callback to ensure synchronization with the actual recording state.
        // LED will be started in RecordController.Status.STARTED callback

        try {
            // Check battery level before starting recording
            val currentBatteryLevel = _currentBatteryLevel.value
            val isCharging = _isBatteryCharging.value
            if (currentBatteryLevel in 0..LOW_BATTERY_THRESHOLD && !isCharging) {
                val errorMsg = "Battery level too low ($currentBatteryLevel%). Cannot start recording. Please charge the device."
                Log.e("MainViewModel", "❌ $errorMsg")
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent(errorMsg)
                )
                ttsManager.speak("Battery level too low. Cannot start recording.")
                return false
            }
            
            // Check storage capacity and select appropriate storage path
            val storageCheckResult = storageManager.checkAndSelectStorageForRecording()
            
            when (storageCheckResult) {
                is StorageManager.RecordingStorageCheckResult.Success -> {
                    Log.i("MainViewModel", "✅ Storage check passed, using ${storageCheckResult.storageType} storage")
                }
                is StorageManager.RecordingStorageCheckResult.Switched -> {
                    Log.i("MainViewModel", "🔄 Storage switched from ${storageCheckResult.oldStorageType} to ${storageCheckResult.newStorageType}")
                    streamingEventMutableSharedFlow.tryEmit(
                        StreamingEvent("Storage switched from ${storageCheckResult.oldStorageType} to ${storageCheckResult.newStorageType} for recording")
                    )
                    ttsManager.speak("Storage switched to ${storageCheckResult.newStorageType}")
                }
                is StorageManager.RecordingStorageCheckResult.InsufficientStorage -> {
                    val errorMsg = buildString {
                        append("Insufficient storage space. ")
                        append("Internal: ${String.format("%.2f", storageCheckResult.internalAvailableGB)} GB available. ")
                        if (storageCheckResult.sdCardAvailableGB != null) {
                            append("SD card: ${String.format("%.2f", storageCheckResult.sdCardAvailableGB)} GB available. ")
                        } else {
                            append("SD card: Not available. ")
                        }
                        append("At least 1 GB required for recording.")
                    }
                    Log.e("MainViewModel", "❌ $errorMsg")
                    streamingEventMutableSharedFlow.tryEmit(
                        StreamingEvent(errorMsg)
                    )
                    ttsManager.speak("Insufficient storage space. Cannot start recording.")
                    return false
                }
                is StorageManager.RecordingStorageCheckResult.Error -> {
                    Log.e("MainViewModel", "❌ Storage check failed: ${storageCheckResult.message}")
                    streamingEventMutableSharedFlow.tryEmit(
                        StreamingEvent("Storage check failed: ${storageCheckResult.message}")
                    )
                    ttsManager.speak("Storage check failed. Cannot start recording.")
                    return false
                }
            }
            
            // Generate recording file path using StorageManager
            val recordFolder = storageManager.getRecordingFolder()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            // Use part000 for initial file to maintain consistent naming
            val recordFile = File(recordFolder, "recording_${timestamp}_part000.mp4")
            val recordPath = recordFile.absolutePath
            
            // Store current recording file for segment management
            currentRecordingFile = recordFile
            
            Log.i("MainViewModel", "Recording to: $recordPath (storage: ${storageManager.getCurrentStorageType()})")

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
                                
                                // ✅ Start LED blinking only after recording has actually started.
                                ledController.startLedBlinking()
                                Log.d("MainViewModel", "💡 LED blinking started")
                                
                                // ✅ Start segment monitoring (if enabled and not in the middle of a segment switch)
                                if (isRecordingSegmentationEnabled) {
                                    if (!isSegmentSwitching) {
                                        recordingSegmentManager.startMonitoring(recordFile, viewModelScope)
                                        Log.i("MainViewModel", "📊 Segment monitoring started (max size: ${segmentSizeBytes / (1024 * 1024)}MB)")
                                    } else {
                                        // Update file for ongoing monitoring
                                        recordingSegmentManager.updateCurrentFile(recordFile)
                                        isSegmentSwitching = false
                                        Log.i("MainViewModel", "📊 Segment monitoring updated to new file")
                                    }
                                } else {
                                    Log.d("MainViewModel", "⏭️ Recording segmentation disabled")
                                }
                                
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
                                
                                // Only update state if not in segment switching mode
                                if (!isSegmentSwitching) {
                                    _isRecording.value = false
                                    ledController.stopLedBlinking()
                                    Log.d("MainViewModel", "💡 LED blinking stopped")
                                    
                                    // Stop segment monitoring
                                    recordingSegmentManager.stopMonitoring()
                                    Log.i("MainViewModel", "📊 Segment monitoring stopped")
                                    
                                    // TTS and event notification only for manual stop
                                    ttsManager.speakRecordingFinished()
                                    streamingEventMutableSharedFlow.tryEmit(
                                        StreamingEvent("Recording stopped: $recordPath")
                                    )
                                } else {
                                    // Segment switching: silent transition, no TTS, no LED change
                                    Log.d("MainViewModel", "🔄 Segment switching in progress, keeping recording state active")
                                    
                                    // Start next segment after a brief delay to ensure MediaCodec resources are released
                                    nextSegmentFilePath?.let { nextPath ->
                                        viewModelScope.launch {
                                            // Wait for MediaCodec and MediaMuxer to fully release resources
                                            // This is critical to prevent "start failed" IllegalStateException
                                            delay(200) // 200ms delay ensures clean resource transition
                                            
                                            Log.i("MainViewModel", "▶️ Starting next segment after STOPPED callback: ${File(nextPath).name}")
                                            startNextSegment(nextPath)
                                            nextSegmentFilePath = null
                                        }
                                    }
                                }
                                // Duration timer removed - frontend now handles timing
                                // stopRecordingDurationTimer()
                                
                                // Calculate and save MD5 after recording stops.
                                // Crucial: Delay execution to ensure the underlying MediaMuxer has fully flushed data to disk.
                                viewModelScope.launch(Dispatchers.IO) {
                                    try {
                                        // Wait for file size to stabilize before calculating MD5
                                        // This ensures MediaMuxer and file system have completed all write operations
                                        Log.i("MainViewModel", "Waiting for file to stabilize: ${recordFile.name}")
                                        
                                        var previousSize = 0L
                                        var stableCount = 0
                                        val maxWaitTime = 30000L // Maximum wait time: 30 seconds
                                        val startTime = System.currentTimeMillis()
                                        
                                        while (stableCount < 3 && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                                            if (!recordFile.exists()) {
                                                Log.e("MainViewModel", "Recording file does not exist: ${recordFile.name}")
                                                return@launch
                                            }
                                            
                                            val currentSize = recordFile.length()
                                            
                                            if (currentSize == previousSize && currentSize > 0) {
                                                stableCount++
                                                Log.d("MainViewModel", "File size stable (${stableCount}/3): ${currentSize} bytes")
                                            } else {
                                                stableCount = 0
                                                Log.d("MainViewModel", "File size changed: $previousSize -> $currentSize bytes")
                                            }
                                            
                                            previousSize = currentSize
                                            delay(1000) // Check every second
                                        }
                                        
                                        val fileSize = recordFile.length()
                                        val waitedTime = System.currentTimeMillis() - startTime
                                        
                                        if (stableCount < 3) {
                                            Log.w("MainViewModel", "File size did not stabilize after ${waitedTime}ms, proceeding anyway")
                                        } else {
                                            Log.i("MainViewModel", "File size stabilized after ${waitedTime}ms at ${fileSize} bytes")
                                        }
                                        
                                        if (fileSize < 1024) {
                                            Log.w("MainViewModel", "Recording file size is too small (${fileSize} bytes): ${recordFile.name}")
                                        }
                                        
                                        Log.i("MainViewModel", "Starting MD5 calculation for file: ${recordFile.name} (${fileSize} bytes)")
                                        val success = MD5Utils.calculateAndSaveMD5(recordFile)
                                        if (success) {
                                            Log.i("MainViewModel", "MD5 file created successfully for: ${recordFile.name}")
                                            // Notify PC side that file list has been updated
                                            statusReportingManager.notifyFileListUpdated()
                                            // Check storage capacity after file is fully written
                                            storageManager.checkAndNotifyCapacity("RecordingComplete[afterMD5:${recordFile.name}]")
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
     * Handle segment switching when file size limit is reached.
     * This method stops the current recording and immediately starts a new one
     * to maintain continuous recording across multiple files.
     * 
     * @param currentFile Current recording file that reached size limit
     * @param nextFilePath Path for the next segment file
     */
    private fun handleSegmentSwitch(currentFile: File, nextFilePath: String) {
        Log.i("MainViewModel", "🔄 Handling segment switch: ${currentFile.name} -> ${File(nextFilePath).name}")
        
        val streamSnapshot = stream
        if (streamSnapshot == null) {
            Log.e("MainViewModel", "❌ Cannot switch segment: stream is null")
            return
        }
        
        if (!_isRecording.value) {
            Log.w("MainViewModel", "⚠️ Not recording, ignoring segment switch")
            return
        }
        
        // Set flag to indicate we're in segment switching mode
        isSegmentSwitching = true
        nextSegmentFilePath = nextFilePath
        
        try {
            Log.i("MainViewModel", "⏸️ Stopping current segment...")
            Log.i("MainViewModel", "   Next segment will be: ${File(nextFilePath).name}")
            
            // Stop current recording
            // The STOPPED callback will trigger startNextSegment() to start the new recording
            streamSnapshot.stopRecord()
        } catch (e: Exception) {
            Log.e("MainViewModel", "❌ Failed to switch segment", e)
            isSegmentSwitching = false
            nextSegmentFilePath = null
            _isRecording.value = false
            ledController.stopLedBlinking()
            recordingSegmentManager.stopMonitoring()
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to switch recording segment: ${e.message}")
            )
        }
    }
    
    /**
     * Start next segment recording after current segment stopped.
     * This is called from STOPPED callback during segment switching.
     */
    private fun startNextSegment(nextFilePath: String) {
        val streamSnapshot = stream
        if (streamSnapshot == null) {
            Log.e("MainViewModel", "❌ Cannot start next segment: stream is null")
            isSegmentSwitching = false
            nextSegmentFilePath = null
            _isRecording.value = false
            ledController.stopLedBlinking()
            recordingSegmentManager.stopMonitoring()
            return
        }
        
        val nextFile = File(nextFilePath)
        currentRecordingFile = nextFile
        
        try {
            Log.i("MainViewModel", "▶️ Starting next segment: ${nextFile.name}")
            
            // Start recording to new file
            streamSnapshot.startRecord(
                nextFilePath,
                null,  // RecordController.RecordTracks - use default
                object : RecordController.Listener {
                    override fun onStatusChange(status: RecordController.Status) {
                        Log.d("MainViewModel", "📹 Segment recording status: $status")
                        when (status) {
                            RecordController.Status.STARTED -> {
                                Log.i("MainViewModel", "✅ Next segment STARTED successfully: ${nextFile.name}")
                                
                                // Update segment manager with new file
                                recordingSegmentManager.updateCurrentFile(nextFile)
                                isSegmentSwitching = false
                                
                                // Silent transition: no TTS, no LED change, only log event
                                val segmentNum = recordingSegmentManager.getCurrentSegmentIndex()
                                Log.i("MainViewModel", "📊 Segment switch completed successfully (segment #$segmentNum): ${nextFile.name}")
                                
                                // Emit event similar to normal recording start (for consistency)
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Recording started: $nextFilePath")
                                )
                            }
                            RecordController.Status.STOPPED -> {
                                Log.i("MainViewModel", "⏹️ Segment recording STOPPED")
                                
                                // Only update state if not in another segment switching mode
                                if (!isSegmentSwitching) {
                                    _isRecording.value = false
                                    ledController.stopLedBlinking()
                                    recordingSegmentManager.stopMonitoring()
                                } else {
                                    // Segment switching in progress, start next segment
                                    Log.d("MainViewModel", "🔄 Segment switching in progress for next recording")
                                    nextSegmentFilePath?.let { nextPath ->
                                        viewModelScope.launch {
                                            // Wait for MediaCodec and MediaMuxer to fully release resources
                                            delay(200)
                                            
                                            Log.i("MainViewModel", "▶️ Starting next segment after STOPPED callback: ${File(nextPath).name}")
                                            startNextSegment(nextPath)
                                            nextSegmentFilePath = null
                                        }
                                    }
                                }
                                
                                // Calculate and save MD5 for the segment file (same as normal recording)
                                viewModelScope.launch(Dispatchers.IO) {
                                    try {
                                        // Wait for file size to stabilize before calculating MD5
                                        Log.i("MainViewModel", "Waiting for segment file to stabilize: ${nextFile.name}")
                                        
                                        var previousSize = 0L
                                        var stableCount = 0
                                        val maxWaitTime = 30000L
                                        val startTime = System.currentTimeMillis()
                                        
                                        while (stableCount < 3 && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                                            if (!nextFile.exists()) {
                                                Log.e("MainViewModel", "Segment file does not exist: ${nextFile.name}")
                                                return@launch
                                            }
                                            
                                            val currentSize = nextFile.length()
                                            
                                            if (currentSize == previousSize && currentSize > 0) {
                                                stableCount++
                                                Log.d("MainViewModel", "File size stable (${stableCount}/3): ${currentSize} bytes")
                                            } else {
                                                stableCount = 0
                                                Log.d("MainViewModel", "File size changed: $previousSize -> $currentSize bytes")
                                            }
                                            
                                            previousSize = currentSize
                                            delay(1000)
                                        }
                                        
                                        val fileSize = nextFile.length()
                                        val waitedTime = System.currentTimeMillis() - startTime
                                        
                                        if (stableCount < 3) {
                                            Log.w("MainViewModel", "File size did not stabilize after ${waitedTime}ms, proceeding anyway")
                                        } else {
                                            Log.i("MainViewModel", "File size stabilized after ${waitedTime}ms at ${fileSize} bytes")
                                        }
                                        
                                        if (fileSize < 1024) {
                                            Log.w("MainViewModel", "Segment file size is too small (${fileSize} bytes): ${nextFile.name}")
                                        }
                                        
                                        Log.i("MainViewModel", "Starting MD5 calculation for segment: ${nextFile.name} (${fileSize} bytes)")
                                        val success = MD5Utils.calculateAndSaveMD5(nextFile)
                                        if (success) {
                                            Log.i("MainViewModel", "MD5 file created successfully for segment: ${nextFile.name}")
                                            statusReportingManager.notifyFileListUpdated()
                                            storageManager.checkAndNotifyCapacity("SegmentComplete[${nextFile.name}]")
                                        } else {
                                            Log.w("MainViewModel", "Failed to create MD5 file for segment: ${nextFile.name}")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainViewModel", "Error while creating MD5 file for segment: ${nextFile.name}", e)
                                    }
                                }
                            }
                            RecordController.Status.RECORDING -> {
                                // Recording in progress
                            }
                            RecordController.Status.PAUSED -> {
                                Log.w("MainViewModel", "⚠️ Segment recording PAUSED")
                            }
                            RecordController.Status.RESUMED -> {
                                Log.i("MainViewModel", "▶️ Segment recording RESUMED")
                            }
                        }
                    }
                }
            )
            
            Log.i("MainViewModel", "📹 Segment recording API called for: ${nextFile.name}")
        } catch (e: Exception) {
            Log.e("MainViewModel", "❌ Failed to start next segment", e)
            isSegmentSwitching = false
            nextSegmentFilePath = null
            _isRecording.value = false
            ledController.stopLedBlinking()
            recordingSegmentManager.stopMonitoring()
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to start next segment: ${e.message}")
            )
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
            
            // Stop segment monitoring (if active)
            if (recordingSegmentManager.isMonitoring()) {
                recordingSegmentManager.stopMonitoring()
                Log.i("MainViewModel", "📊 Segment monitoring stopped")
            }
            
            streamSnapshot.stopRecord()
            // The status will be updated in the callback.
            // Delay checking for camera resource release to give the recording stop callback enough time to complete MD5 calculation.
            viewModelScope.launch {
                // Wait longer to ensure MD5 calculation is complete.
                delay(2000) // Wait 2 seconds: 100ms for status update + 1000ms for MD5 delay + extra buffer
                checkAndReleaseCamera()
            }
            onResult?.invoke(true)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to stop recording", e)
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("Failed to stop recording: ${e.message}")
            )
            _isRecording.value = false
            
            // Ensure segment monitoring is stopped
            recordingSegmentManager.stopMonitoring()
            
            // Even if failed, delay a bit before checking resource release
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
    
    /**
     * Data class to hold five values (used for video configuration)
     */
    private data class Quintuple<out A, out B, out C, out D, out E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        CONNECTED,
        FAILED,
        DISCONNECTED
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
     * This method can be called on the main thread, but time-consuming operations will be executed on a background thread.
     */
    private fun releaseCamera() {
        val localStream = stream ?: return
        
        // Immediately set stream to null to prevent repeated releases (must be done before starting the coroutine).
        stream = null
        _isPrepared.value = false
        _isPreviewActive.value = false
        
        Log.i("MainViewModel", "Starting camera release process")
        
        // Execute resource release on a background thread to avoid blocking the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Stop recording first (most critical, must ensure the file is closed correctly).
                if (localStream.isRecording) {
                    try {
                        Log.i("MainViewModel", "Stopping recording before releasing camera")
                        localStream.stopRecord()
                        // Wait for recording to fully stop, giving the file system time to release the lock.
                        delay(200)
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to stop recording, but will continue cleanup", e)
                    }
                }
                
                // 2. Stop streaming.
                if (localStream.isStreaming) {
                    try {
                        localStream.stopStream()
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to stop streaming", e)
                    }
                }
                
                // 3. Stop preview.
                if (localStream.isOnPreview) {
                    try {
                        localStream.stopPreview()
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to stop preview", e)
                    }
                }
                
                // 4. Finally, release resources.
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
            prepareSources()
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
     * Called when the ViewModel is destroyed (when the application exits completely).
     * Ensures that any ongoing recording is safely stopped.
     * 
     * Note: This method executes synchronously on the main thread, but we need to give recording enough time to stop.
     * Use runBlocking on an IO thread to avoid blocking other main thread operations.
     */
    override fun onCleared() {
        super.onCleared()
        Log.i("MainViewModel", "ViewModel is being cleared")
        
        // Stop capacity monitoring
        stopRecordingCapacityMonitoring()
        storageManager.stopFileListMonitoring()
        storageManager.setStorageCapacityListener(null)
        
        // Clear battery level listener
        statusReportingManager.setBatteryLevelListener(null)
        
        stream?.let { localStream ->
            // Use viewModelScope.launch(Dispatchers.IO) to perform cleanup in the background
            // This avoids blocking the main thread, which can cause ANRs.
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // If recording is active, stop it first
                    if (localStream.isRecording) {
                        Log.w("MainViewModel", "Recording is still active during onCleared, forcing stop")
                        try {
                            localStream.stopRecord()
                            // Wait for the file system to release the lock
                            delay(500)
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to stop recording in onCleared", e)
                        }
                    }

                    // Stop streaming
                    if (localStream.isStreaming) {
                        try {
                            localStream.stopStream()
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to stop streaming in onCleared", e)
                        }
                    }

                    // Stop preview
                    if (localStream.isOnPreview) {
                        try {
                            localStream.stopPreview()
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to stop preview in onCleared", e)
                        }
                    }

                    // Release resources
                    try {
                        localStream.release()
                        Log.i("MainViewModel", "All camera resources released in onCleared")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to release camera resources in onCleared", e)
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Exception during cleanup in onCleared", e)
                }
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
        
        // First threshold: Warning only
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
        
        // Second threshold: Auto-stop to prevent wasting resources
        if (consecutiveZeroBitrateCount >= ZERO_BITRATE_AUTO_STOP_THRESHOLD) {
            Log.e("MainViewModel", "❌ Zero bitrate persisted for $consecutiveZeroBitrateCount reports. Auto-stopping stream to save resources.")
            
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("❌ Stream auto-stopped: No data transmitted for ${consecutiveZeroBitrateCount} seconds. Please check camera and network.")
            )
            
            // Stop streaming to save resources and allow recovery
            viewModelScope.launch(Dispatchers.Main) {
                stopStreaming()
            }
            
            // Reset counter to prevent repeated stops
            consecutiveZeroBitrateCount = 0
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
    
    /**
     * Start periodic storage capacity monitoring during recording.
     * Checks capacity every 5 seconds while recording.
     * If current path becomes insufficient (< 1GB), stops recording and restarts with capacity check.
     */
    private fun startRecordingCapacityMonitoring() {
        stopRecordingCapacityMonitoring()
        
        recordingCapacityCheckJob = viewModelScope.launch {
            // Check immediately on start
            storageManager.checkAndNotifyCapacity("RecordingTimer[initial]")
            
            // Then check every 5 seconds
            var checkCount = 1
            while (true) {
                delay(RECORDING_CAPACITY_CHECK_INTERVAL_MS)
                if (!_isRecording.value) {
                    break
                }
                
                // Check capacity and notify
                storageManager.checkAndNotifyCapacity("RecordingTimer[periodic:$checkCount]")
                
                // Check if current recording path has insufficient capacity
                if (storageManager.isCurrentRecordingPathInsufficient()) {
                    if (isHandlingStorageSwitch) {
                        Log.d("MainViewModel", "⏸️ Storage switch handling already in progress, skipping check")
                        checkCount++
                        continue
                    }
                    
                    Log.w("MainViewModel", "⚠️ Current recording path has insufficient capacity, stopping recording to switch storage")
                    
                    // Set flag to prevent concurrent handling
                    isHandlingStorageSwitch = true
                    
                    // Stop recording and wait for file to be saved
                    stopRecording { stopped ->
                        if (stopped) {
                            Log.i("MainViewModel", "✅ Recording stopped, waiting for file save completion before restarting")
                            
                            // Wait for file save and MD5 calculation to complete
                            // stopRecording waits 2 seconds, MD5 calculation waits 1 second, add small buffer
                            viewModelScope.launch {
                                delay(2000) // Wait 2 seconds for file save and MD5 calculation
                                
                                // Notify user
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("Storage capacity low, switching storage path and restarting recording...")
                                )
                                ttsManager.speak("Storage capacity low, switching storage and restarting recording")
                                
                                // Restart recording (this will trigger capacity check and auto-switch if needed)
                                Log.i("MainViewModel", "🔄 Restarting recording with storage capacity check...")
                                startRecording { success ->
                                    if (success) {
                                        Log.i("MainViewModel", "✅ Recording restarted successfully after storage switch")
                                    } else {
                                        Log.e("MainViewModel", "❌ Failed to restart recording after storage switch")
                                        streamingEventMutableSharedFlow.tryEmit(
                                            StreamingEvent("Failed to restart recording after storage switch")
                                        )
                                        ttsManager.speak("Failed to restart recording")
                                    }
                                    
                                    // Reset flag after restart attempt
                                    isHandlingStorageSwitch = false
                                }
                            }
                        } else {
                            Log.e("MainViewModel", "❌ Failed to stop recording for storage switch")
                            streamingEventMutableSharedFlow.tryEmit(
                                StreamingEvent("Failed to stop recording for storage switch")
                            )
                            // Reset flag even if stop failed
                            isHandlingStorageSwitch = false
                        }
                    }
                    
                    // Exit monitoring loop as recording is being stopped
                    break
                }
                
                checkCount++
            }
        }
        Log.i("MainViewModel", "✅ Started recording capacity monitoring (every 5 seconds)")
    }
    
    /**
     * Stop periodic storage capacity monitoring.
     */
    private fun stopRecordingCapacityMonitoring() {
        recordingCapacityCheckJob?.cancel()
        recordingCapacityCheckJob = null
        Log.i("MainViewModel", "⏹️ Stopped recording capacity monitoring")
    }

    companion object {
        private const val STREAMING_EVENT_BUFFER_SIZE = 15
    }
}
