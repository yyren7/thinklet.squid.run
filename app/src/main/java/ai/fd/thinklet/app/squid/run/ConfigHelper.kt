package ai.fd.thinklet.app.squid.run

/**
 * Configuration helper class.
 * Provides convenient methods to quickly set up common configuration combinations.
 */
object ConfigHelper {
    
    /**
     * Local test configuration (using MediaMTX).
     */
    fun getLocalTestConfig(): Map<String, Any> {
        return mapOf(
            "streamUrl" to "rtmp://192.168.16.88:1935/thinklet.squid.run",
            "streamKey" to "test_stream",
            "longSide" to 720,
            "shortSide" to 480,
            "orientation" to "landscape",
            "videoBitrate" to 2048, // Lower bitrate for local testing
            "audioSampleRate" to 48000,
            "audioBitrate" to 128,
            "audioChannel" to "stereo",
            "echoCanceler" to false,
            "micMode" to "android",
            "preview" to true // Enable preview for local testing
        )
    }
    
    /**
     * YouTube Live configuration template.
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
            "echoCanceler" to true, // YouTube recommends enabling this
            "micMode" to "android",
            "preview" to false
        )
    }
    
    /**
     * High-quality configuration (suitable for professional live streaming).
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
            "audioBitrate" to 320, // High-quality audio
            "audioChannel" to "stereo",
            "echoCanceler" to true,
            "micMode" to "thinklet5", // Use THINKLET multi-microphone
            "preview" to false
        )
    }
    
    /**
     * Low-power configuration (to save battery).
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
            "audioChannel" to "monaural", // Monaural to save bandwidth
            "echoCanceler" to false,
            "micMode" to "android",
            "preview" to false
        )
    }
    
    /**
     * Portrait configuration (suitable for viewing on mobile devices).
     */
    fun getPortraitConfig(streamUrl: String, streamKey: String): Map<String, Any> {
        return mapOf(
            "streamUrl" to streamUrl,
            "streamKey" to streamKey,
            "longSide" to 720,
            "shortSide" to 480,
            "orientation" to "portrait", // Force portrait orientation
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
     * Generate an adb command string.
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
