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

    private val _recordingDurationMs: MutableStateFlow<Long> = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var recordingStartTime: Long = 0L
    private var recordingDurationJob: Job? = null

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

    private var wasStreamingBeforePause = false

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
            Log.i("MainViewModel", "发现 ${cameraIds.size} 个摄像头")
            cameraIds.isNotEmpty()
        } catch (e: Exception) {
            // 如果出现任何异常，认为相机不可用
            Log.e("MainViewModel", "相机检查失败", e)
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("相机检查失败: ${e.message}")
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
                StreamingEvent("摄像机资源被启用")
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
                    StreamingEvent("直播参数设置失败: ${e.message}")
                )
                false
            } catch (e: Exception) {
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("相机视频流准备失败: ${e.message}")
                )
                false
            }
            
            if (isPrepared) {
                stream = localStream
                _isPrepared.value = true
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("摄像机资源已正常启动")
                )
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("相机视频流准备完成")
                )
            } else {
                _isPrepared.value = false
            }
        } catch (e: CancellationException) {
            // 协程被取消是正常行为，不需要报错，直接重新抛出让协程框架处理
            Log.d("MainViewModel", "摄像头准备被取消")
            throw e
        } catch (e: Exception) {
            // 捕获相机初始化相关的所有异常
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("相机初始化失败: ${e.message}")
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
        wasStreamingBeforePause = _isStreaming.value
        stopStreaming()
        stopPreview()
        stream = null
        _isPrepared.value = false
    }

    fun activityOnResume() {
        // The preview will be restored by the SurfaceHolder.Callback,
        // which will call maybePrepareStreaming -> startPreview.
        // We only need to restore the streaming state.
        if (wasStreamingBeforePause) {
            viewModelScope.launch {
                isPrepared.first { it }
                maybeStartStreaming()
            }
            wasStreamingBeforePause = false
        }
    }

    /**
     * 启动推流
     * 如果摄像头未准备好，会先自动初始化
     */
    fun maybeStartStreaming(onResult: ((Boolean) -> Unit)? = null) {
        // 确保摄像头已准备好
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
        if (streamSnapshot.isStreaming) {
            return true
        }
        streamSnapshot.startStream("$streamUrl/${streamKey.value}")
        return true
    }
    
    /**
     * 同步版本的启动推流（兼容旧代码）
     * 仅在摄像头已准备好时才能成功
     */
    fun maybeStartStreamingSync(): Boolean {
        if (!_isPrepared.value) {
            Log.w("MainViewModel", "摄像头未准备好")
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("推流失败: 摄像头未准备好")
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
        // 检查是否需要释放摄像头资源
        checkAndReleaseCamera()
    }

    @MainThread
    fun startPreview(surface: Surface, width: Int, height: Int) {
        startPreviewJob?.cancel()
        startPreviewJob = viewModelScope.launch {
            // 确保摄像头已准备好
            if (!_isPrepared.value) {
                Log.i("MainViewModel", "预览启动: 摄像头未准备，正在初始化...")
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
                Log.d("MainViewModel", "预览已停止")
            } catch (e: Exception) {
                // 捕获 Surface 已销毁等异常
                Log.w("MainViewModel", "停止预览时出现异常（可能 Surface 已销毁）: ${e.message}")
                _isPreviewActive.value = false
            }
        } else {
            // 即使预览未激活，也确保状态正确
            _isPreviewActive.value = false
        }
        // 检查是否需要释放摄像头资源
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
     * 开始录制视频到本地文件
     * 录制和直播共用同一个摄像头流，但输出到不同的目标
     * 如果摄像头未准备好，会先自动初始化
     */
    fun startRecording(onResult: ((Boolean) -> Unit)? = null) {
        // 确保摄像头已准备好
        ensureCameraReady {
            val result = startRecordingInternal()
            onResult?.invoke(result)
        }
    }

    private fun startRecordingInternal(): Boolean {
        val streamSnapshot = stream
        if (streamSnapshot == null) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("录制失败: 流未准备好")
            )
            return false
        }

        if (_isRecording.value) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("录制已在进行中")
            )
            return true
        }

        try {
            // 生成录制文件路径
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

            // 开始录制
            streamSnapshot.startRecord(
                recordPath, 
                null,  // RecordController.RecordTracks - 使用默认配置（录制音频和视频）
                object : RecordController.Listener {
                    override fun onStatusChange(status: RecordController.Status) {
                        // 录制状态回调
                        when (status) {
                            RecordController.Status.STARTED -> {
                                _isRecording.value = true
                                recordingStartTime = System.currentTimeMillis()
                                startRecordingDurationTimer()
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("录制已开始: $recordPath")
                                )
                            }
                            RecordController.Status.STOPPED -> {
                                _isRecording.value = false
                                stopRecordingDurationTimer()
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("录制已停止: $recordPath")
                                )
                            }
                            RecordController.Status.RECORDING -> {
                                // 录制中
                            }
                            RecordController.Status.PAUSED -> {
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("录制已暂停")
                                )
                            }
                            RecordController.Status.RESUMED -> {
                                streamingEventMutableSharedFlow.tryEmit(
                                    StreamingEvent("录制已恢复")
                                )
                            }
                            else -> {
                                // 未知或未来的状态
                            }
                        }
                    }
                }
            )
            return true
        } catch (e: Exception) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("录制启动失败: ${e.message}")
            )
            _isRecording.value = false
            return false
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        val streamSnapshot = stream ?: return
        try {
            streamSnapshot.stopRecord()
            // 状态会在回调中更新
            // 检查是否需要释放摄像头资源
            viewModelScope.launch {
                delay(100) // 等待状态更新
                checkAndReleaseCamera()
            }
        } catch (e: Exception) {
            streamingEventMutableSharedFlow.tryEmit(
                StreamingEvent("停止录制失败: ${e.message}")
            )
            _isRecording.value = false
            checkAndReleaseCamera()
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

    private fun getDeviceRotation(): Int {
        return angle.current()
    }

    /**
     * 检查是否需要释放摄像头资源
     * 当推流、录像、预览三个功能都关闭时，完全释放摄像头以节省电量
     */
    private fun checkAndReleaseCamera() {
        val shouldRelease = !_isStreaming.value && !_isRecording.value && !_isPreviewActive.value
        if (shouldRelease) {
            Log.i("MainViewModel", "所有功能已关闭，释放摄像头资源以节省电量")
            releaseCamera()
        }
    }

    /**
     * 完全释放摄像头和编码器资源
     */
    private fun releaseCamera() {
        stream?.let { localStream ->
            try {
                if (localStream.isOnPreview) {
                    localStream.stopPreview()
                }
                if (localStream.isStreaming) {
                    localStream.stopStream()
                }
                if (localStream.isRecording) {
                    localStream.stopRecord()
                }
                // 释放资源
                localStream.release()
                streamingEventMutableSharedFlow.tryEmit(
                    StreamingEvent("摄像头资源已释放")
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "释放摄像头资源失败", e)
            }
        }
        stream = null
        _isPrepared.value = false
        _isPreviewActive.value = false
    }

    /**
     * 确保摄像头已准备好（如果需要使用时）
     * 适用于 Preview=false 的情况，在推流或录像时自动准备摄像头
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
     * 启动录像时长计时器，每秒更新一次
     */
    private fun startRecordingDurationTimer() {
        recordingDurationJob?.cancel()
        recordingDurationJob = viewModelScope.launch {
            while (true) {
                delay(1000) // 每秒更新一次
                val duration = System.currentTimeMillis() - recordingStartTime
                _recordingDurationMs.value = duration
            }
        }
    }

    /**
     * 停止录像时长计时器并重置时长
     */
    private fun stopRecordingDurationTimer() {
        recordingDurationJob?.cancel()
        recordingDurationJob = null
        _recordingDurationMs.value = 0L
        recordingStartTime = 0L
    }

    companion object {
        private const val STREAMING_EVENT_BUFFER_SIZE = 15
    }
}
