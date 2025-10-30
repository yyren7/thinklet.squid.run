package ai.fd.thinklet.app.squid.run

/**
 * Default configuration class.
 * Contains the default parameters used when the application starts,
 * avoiding the need to pass parameters via the command line every time.
 */
object DefaultConfig {
    
    // RTMP Server Configuration
    const val DEFAULT_STREAM_URL = "rtmp://192.168.16.88:1935/thinklet.squid.run"
    const val DEFAULT_STREAM_KEY = "test_stream"
    
    // Video Configuration
    const val DEFAULT_LONG_SIDE = 640
    const val DEFAULT_SHORT_SIDE = 360
    const val DEFAULT_VIDEO_BITRATE = 512 // kbps
    const val DEFAULT_VIDEO_FPS = 24 // fps

    // Recording-specific Video Configuration (can be different from streaming)
    const val DEFAULT_RECORD_VIDEO_WIDTH = 1920
    const val DEFAULT_RECORD_VIDEO_HEIGHT = 1080
    const val DEFAULT_RECORD_VIDEO_BITRATE = 8192 // kbps, higher quality for local recording
    
    // Audio Configuration
    const val DEFAULT_AUDIO_SAMPLE_RATE = 48000 // Hz
    const val DEFAULT_AUDIO_BITRATE = 128 // kbps
    const val DEFAULT_AUDIO_CHANNEL = "stereo" // "monaural" or "stereo"
    const val DEFAULT_ECHO_CANCELER = false
    const val DEFAULT_MIC_MODE = "android" // "android", "thinklet5", "thinklet6"
    
    // Other Configurations
    const val DEFAULT_PREVIEW = false // Whether to show a preview (recommended to be off to save power)
    
    /**
     * Get a map of all default configurations.
     */
    fun getDefaultConfigMap(): Map<String, Any> {
        return mapOf(
            "streamUrl" to DEFAULT_STREAM_URL,
            "streamKey" to DEFAULT_STREAM_KEY,
            "longSide" to DEFAULT_LONG_SIDE,
            "shortSide" to DEFAULT_SHORT_SIDE,
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
