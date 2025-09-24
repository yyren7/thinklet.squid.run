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
