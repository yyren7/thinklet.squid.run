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
import android.util.Log
import java.util.*

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isInitialized = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e("TTSManager", "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTSManager", "The Language specified is not supported!")
            } else {
                isInitialized = true
            }
        } else {
            Log.e("TTSManager", "Initialization Failed!")
        }
    }

    fun speak(message: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, utteranceId: String? = null) {
        if (!isInitialized) {
            Log.e("TTSManager", "TTS not initialized, cannot speak.")
            return
        }
        Log.d("TTSManager", "TTS speak: $message")
        tts.speak(message, queueMode, null, utteranceId)
    }

    fun speakPowerDown() {
        val message = "power down"
        speak(message, utteranceId = "power_down")
    }

    fun speakRecordingStarted() {
        speak("recording started", utteranceId = "recording_started")
    }

    fun speakRecordingFinished() {
        speak("recording finished", utteranceId = "recording_finished")
    }

    fun speakApplicationPrepared() {
        speakWithRetry("application prepared", utteranceId = "application_prepared", maxRetries = 5, delayMs = 500)
    }
    
    /**
     * 带重试机制的TTS播报，用于处理TTS异步初始化的情况
     */
    private fun speakWithRetry(
        message: String, 
        utteranceId: String? = null, 
        maxRetries: Int = 3, 
        delayMs: Long = 300,
        currentRetry: Int = 0
    ) {
        if (isInitialized) {
            speak(message, utteranceId = utteranceId)
            return
        }
        
        if (currentRetry >= maxRetries) {
            Log.w("TTSManager", "Failed to speak after $maxRetries retries: $message")
            return
        }
        
        handler.postDelayed({
            speakWithRetry(message, utteranceId, maxRetries, delayMs, currentRetry + 1)
        }, delayMs)
    }

    fun getBatteryAndNetworkStatusMessage(): String {
        val batteryPercentage = getBatteryPercentage()
        val networkStatus = getNetworkStatus()
        return "battery status: ${batteryPercentage}% remaining, network status: $networkStatus"
    }

    fun speakBatteryAndNetworkStatus() {
        val message = getBatteryAndNetworkStatusMessage()
        speak(message, utteranceId = "battery_network_status")
    }

    private fun getBatteryPercentage(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @SuppressLint("MissingPermission")
    private fun getNetworkStatus(): String {
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

        return "connected to wifi ${spellOutWifiName(currentSsid)}"
    }

    private fun spellOutWifiName(ssid: String): String {
        return ssid.replace("-", " dash ").replace("_", " underscore ")
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null) // 清理所有待处理的回调
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}





