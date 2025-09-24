package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 * 用于管理应用的配置参数，支持从SharedPreferences读取用户自定义配置
 */
class ConfigManager(context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("squid_run_config", Context.MODE_PRIVATE)
    
    /**
     * 获取流媒体服务器URL
     */
    fun getStreamUrl(): String {
        return sharedPreferences.getString("streamUrl", DefaultConfig.DEFAULT_STREAM_URL) 
            ?: DefaultConfig.DEFAULT_STREAM_URL
    }
    
    /**
     * 设置流媒体服务器URL
     */
    fun setStreamUrl(url: String) {
        sharedPreferences.edit().putString("streamUrl", url).apply()
    }
    
    /**
     * 获取流密钥
     */
    fun getStreamKey(): String {
        return sharedPreferences.getString("streamKey", DefaultConfig.DEFAULT_STREAM_KEY)
            ?: DefaultConfig.DEFAULT_STREAM_KEY
    }
    
    /**
     * 设置流密钥
     */
    fun setStreamKey(key: String) {
        sharedPreferences.edit().putString("streamKey", key).apply()
    }
    
    /**
     * 获取长边分辨率
     */
    fun getLongSide(): Int {
        return sharedPreferences.getInt("longSide", DefaultConfig.DEFAULT_LONG_SIDE)
    }
    
    /**
     * 设置长边分辨率
     */
    fun setLongSide(size: Int) {
        sharedPreferences.edit().putInt("longSide", size).apply()
    }
    
    /**
     * 获取短边分辨率
     */
    fun getShortSide(): Int {
        return sharedPreferences.getInt("shortSide", DefaultConfig.DEFAULT_SHORT_SIDE)
    }
    
    /**
     * 设置短边分辨率
     */
    fun setShortSide(size: Int) {
        sharedPreferences.edit().putInt("shortSide", size).apply()
    }
    
    /**
     * 获取视频比特率（kbps）
     */
    fun getVideoBitrate(): Int {
        return sharedPreferences.getInt("videoBitrate", DefaultConfig.DEFAULT_VIDEO_BITRATE)
    }
    
    /**
     * 设置视频比特率（kbps）
     */
    fun setVideoBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt("videoBitrate", bitrate).apply()
    }
    
    /**
     * 获取音频采样率（Hz）
     */
    fun getAudioSampleRate(): Int {
        return sharedPreferences.getInt("audioSampleRate", DefaultConfig.DEFAULT_AUDIO_SAMPLE_RATE)
    }
    
    /**
     * 设置音频采样率（Hz）
     */
    fun setAudioSampleRate(sampleRate: Int) {
        sharedPreferences.edit().putInt("audioSampleRate", sampleRate).apply()
    }
    
    /**
     * 获取音频比特率（kbps）
     */
    fun getAudioBitrate(): Int {
        return sharedPreferences.getInt("audioBitrate", DefaultConfig.DEFAULT_AUDIO_BITRATE)
    }
    
    /**
     * 设置音频比特率（kbps）
     */
    fun setAudioBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt("audioBitrate", bitrate).apply()
    }
    
    /**
     * 重置所有配置为默认值
     */
    fun resetToDefaults() {
        sharedPreferences.edit().clear().apply()
    }
    
    /**
     * 获取所有当前配置的映射表
     */
    fun getCurrentConfigMap(): Map<String, Any> {
        return mapOf(
            "streamUrl" to getStreamUrl(),
            "streamKey" to getStreamKey(),
            "longSide" to getLongSide(),
            "shortSide" to getShortSide(),
            "videoBitrate" to getVideoBitrate(),
            "audioSampleRate" to getAudioSampleRate(),
            "audioBitrate" to getAudioBitrate()
        )
    }
}
