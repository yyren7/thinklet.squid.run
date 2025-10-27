package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration Manager
 * Manages the application's configuration parameters, supporting reading user-defined configurations from SharedPreferences.
 */
class ConfigManager(context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("squid_run_config", Context.MODE_PRIVATE)
    
    /**
     * Gets the streaming server URL.
     */
    fun getStreamUrl(): String {
        return sharedPreferences.getString("streamUrl", DefaultConfig.DEFAULT_STREAM_URL) 
            ?: DefaultConfig.DEFAULT_STREAM_URL
    }
    
    /**
     * Sets the streaming server URL.
     */
    fun setStreamUrl(url: String) {
        sharedPreferences.edit().putString("streamUrl", url).apply()
    }
    
    /**
     * Gets the stream key.
     */
    fun getStreamKey(): String {
        return sharedPreferences.getString("streamKey", DefaultConfig.DEFAULT_STREAM_KEY)
            ?: DefaultConfig.DEFAULT_STREAM_KEY
    }
    
    /**
     * Sets the stream key.
     */
    fun setStreamKey(key: String) {
        sharedPreferences.edit().putString("streamKey", key).apply()
    }
    
    /**
     * Gets the long side resolution.
     */
    fun getLongSide(): Int {
        return sharedPreferences.getInt("longSide", DefaultConfig.DEFAULT_LONG_SIDE)
    }
    
    /**
     * Sets the long side resolution.
     */
    fun setLongSide(size: Int) {
        sharedPreferences.edit().putInt("longSide", size).apply()
    }
    
    /**
     * Gets the short side resolution.
     */
    fun getShortSide(): Int {
        return sharedPreferences.getInt("shortSide", DefaultConfig.DEFAULT_SHORT_SIDE)
    }
    
    /**
     * Sets the short side resolution.
     */
    fun setShortSide(size: Int) {
        sharedPreferences.edit().putInt("shortSide", size).apply()
    }
    
    /**
     * Gets the video bitrate (in kbps).
     */
    fun getVideoBitrate(): Int {
        return sharedPreferences.getInt("videoBitrate", DefaultConfig.DEFAULT_VIDEO_BITRATE)
    }
    
    /**
     * Sets the video bitrate (in kbps).
     */
    fun setVideoBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt("videoBitrate", bitrate).apply()
    }
    
    /**
     * Gets the audio sample rate (in Hz).
     */
    fun getAudioSampleRate(): Int {
        return sharedPreferences.getInt("audioSampleRate", DefaultConfig.DEFAULT_AUDIO_SAMPLE_RATE)
    }
    
    /**
     * Sets the audio sample rate (in Hz).
     */
    fun setAudioSampleRate(sampleRate: Int) {
        sharedPreferences.edit().putInt("audioSampleRate", sampleRate).apply()
    }
    
    /**
     * Gets the audio bitrate (in kbps).
     */
    fun getAudioBitrate(): Int {
        return sharedPreferences.getInt("audioBitrate", DefaultConfig.DEFAULT_AUDIO_BITRATE)
    }
    
    /**
     * Sets the audio bitrate (in kbps).
     */
    fun setAudioBitrate(bitrate: Int) {
        sharedPreferences.edit().putInt("audioBitrate", bitrate).apply()
    }
    
    /**
     * Resets all configurations to their default values.
     */
    fun resetToDefaults() {
        sharedPreferences.edit().clear().apply()
    }
    
    /**
     * Gets a map of all current configurations.
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
