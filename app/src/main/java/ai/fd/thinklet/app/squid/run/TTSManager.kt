package ai.fd.thinklet.app.squid.run

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TTS Manager for Android 8.1 with boot-time protection.
 * 
 * Design principles:
 * - Fully asynchronous using Kotlin Flow
 * - No thread blocking (no wait/sleep on main thread)
 * - Main thread initialization
 * - Graceful degradation if TTS not available
 * - Boot-time delay to avoid system TTS service crashes
 * 
 * Known issue: Pico TTS (libttspico.so) may crash with SIGSEGV on Android 8.1
 * if initialized too early during system boot. This is a system-level bug that
 * cannot be recovered from until the next reboot.
 * 
 * Solution: Detect boot scenario and delay TTS initialization to give system
 * services time to stabilize (3 seconds on boot, immediate otherwise).
 */
class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // TTS ready state using StateFlow instead of synchronized lock
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()
    
    init {
        // Detect boot scenario and delay TTS initialization to avoid system crash
        val isBootScenario = isRecentBoot()
        
        if (isBootScenario) {
            // Boot scenario: delay TTS initialization to give system time to stabilize
            // This prevents Pico TTS service from crashing when called too early
            Log.i(TAG, "⏱️ Boot scenario detected (uptime < 90s), delaying TTS initialization by 3 seconds...")
            handler.postDelayed({
                Log.i(TAG, "✅ Boot delay complete, initializing TTS...")
                initializeTTS()
            }, 3000) // 3 seconds delay for boot scenario
        } else {
            // Normal scenario: initialize immediately
            Log.d(TAG, "Normal startup, initializing TTS immediately")
            initializeTTS()
        }
    }
    
    /**
     * Check if this is a recent boot scenario.
     * If system uptime is less than 90 seconds, consider it a boot scenario.
     */
    private fun isRecentBoot(): Boolean {
        val uptimeMillis = android.os.SystemClock.elapsedRealtime()
        val uptimeSeconds = uptimeMillis / 1000
        
        Log.d(TAG, "System uptime: ${uptimeSeconds}s")
        
        // If system has been up for less than 90 seconds, treat as boot scenario
        return uptimeSeconds < 90
    }
    
    private fun initializeTTS() {
        try {
            Log.d(TAG, "Initializing TextToSpeech...")
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TextToSpeech instance", e)
            _ttsReady.value = false
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "✅ TTS initialized successfully")
            _ttsReady.value = true
        } else {
            Log.e(TAG, "❌ TTS initialization failed with status: $status")
            _ttsReady.value = false
        }
    }
    
    /**
     * Speak text if TTS is ready, otherwise skip gracefully.
     * Non-blocking method.
     */
    fun speak(
        message: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        utteranceId: String? = null
    ) {
        if (!_ttsReady.value) {
            Log.w(TAG, "⚠️ TTS not ready, skipping speech: $message")
            return
        }
        
        val ttsInstance = tts ?: run {
            Log.w(TAG, "⚠️ TTS instance is null, skipping speech: $message")
            return
        }
        
        try {
            val result = ttsInstance.speak(message, queueMode, null, utteranceId)
            when (result) {
                TextToSpeech.SUCCESS -> {
                    Log.d(TAG, "🔊 Speech queued: $message")
                }
                TextToSpeech.ERROR -> {
                    Log.w(TAG, "⚠️ Failed to queue speech: $message")
                }
                else -> {
                    Log.w(TAG, "⚠️ Speech result: $result for text: $message")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception while speaking: $message", e)
        }
    }
    
    fun speakPowerDown() {
        speak("power down", utteranceId = "power_down")
    }
    
    fun speakRecordingStarted() {
        speak("recording started", utteranceId = "recording_started")
    }
    
    fun speakRecordingFinished() {
        speak("recording finished", utteranceId = "recording_finished")
    }
    
    fun speakApplicationPrepared() {
        speak("application prepared", utteranceId = "application_prepared")
    }
    
    fun speakBatteryAndNetworkStatus() {
        val message = getBatteryAndNetworkStatusMessage()
        speak(message, utteranceId = "battery_network_status")
    }
    
    fun getBatteryAndNetworkStatusMessage(): String {
        val batteryPercentage = getBatteryPercentage()
        val networkStatus = getNetworkStatus()
        return "battery status: ${batteryPercentage}% remaining, network status: $networkStatus"
    }
    
    private fun getBatteryPercentage(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery percentage", e)
            0
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun getNetworkStatus(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (!wifiManager.isWifiEnabled) {
                return "wifi is disabled"
            }

            val activeNetwork = connectivityManager.activeNetwork ?: return "not connected to wifi"
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isWifiConnected = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false

            if (!isWifiConnected) {
                return "not connected to wifi"
            }

            val currentSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wifiInfo = networkCapabilities?.transportInfo as? WifiInfo
                wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "unknown network"
            } else {
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo.ssid.removePrefix("\"").removeSuffix("\"")
            }

            "connected to wifi ${spellOutWifiName(currentSsid)}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network status", e)
            "network status unavailable"
        }
    }
    
    private fun spellOutWifiName(ssid: String): String {
        return ssid.replace("-", " dash ").replace("_", " underscore ")
    }
    
    fun shutdown() {
        Log.i(TAG, "🛑 Shutting down TTS...")
        tts?.let {
            try {
                it.stop()
                it.shutdown()
                Log.d(TAG, "✅ TTS shutdown complete")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during TTS shutdown", e)
            }
        }
        tts = null
        _ttsReady.value = false
    }
    
    companion object {
        private const val TAG = "TTSManager"
    }
}





