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
