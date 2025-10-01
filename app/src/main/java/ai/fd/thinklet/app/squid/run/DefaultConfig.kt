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
    const val DEFAULT_LONG_SIDE = 1920
    const val DEFAULT_SHORT_SIDE = 1080
    const val DEFAULT_ORIENTATION = "landscape" // "landscape" 或 "portrait"
    const val DEFAULT_VIDEO_BITRATE = 4096 // kbps
    const val DEFAULT_VIDEO_FPS = 24 // fps
    
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
