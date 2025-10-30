package ai.fd.thinklet.app.squid.run

/**
 * Configuration helper class.
 * Provides high-quality configuration for professional live streaming.
 */
object ConfigHelper {
    
    /**
     * High-quality configuration (suitable for professional live streaming).
     */
    fun getHighQualityConfig(streamUrl: String, streamKey: String): Map<String, Any> {
        return mapOf(
            "streamUrl" to streamUrl,
            "streamKey" to streamKey,
            "longSide" to 1920,
            "shortSide" to 1080,
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
