package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer
import androidx.localbroadcastmanager.content.LocalBroadcastManager

data class DeviceStatus(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val wifiSignalStrength: Int,
    val isOnline: Boolean,
    var isStreamingReady: Boolean,
    var isStreaming: Boolean,
    var streamKey: String? = null,
    var isRecording: Boolean = false,
    var recordingDurationMs: Long = 0
)

data class StatusUpdate(
    val id: String,
    val status: DeviceStatus
)

data class Command(val command: String)

class StatusReportingManager(
    private val context: Context,
    private val streamUrl: String?
) {

    var isStreamingReady: Boolean = false
    var isStreaming: Boolean = false
    private var streamKey: String? = null
    var isRecording: Boolean = false
    var recordingDurationMs: Long = 0
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var timer: Timer? = null
    private val gson = Gson()
    val deviceId: String

    @Volatile
    private var isStarted = false
    @Volatile
    private var isConnecting = false
    
    // 动态上报间隔：正常15秒，流媒体/录制时5秒
    private var currentReportInterval: Long = NORMAL_REPORT_INTERVAL
    
    companion object {
        private const val TAG = "StatusReportingManager"
        private const val NORMAL_REPORT_INTERVAL = 15000L  // 15秒
        private const val ACTIVE_REPORT_INTERVAL = 5000L   // 5秒
    }

    init {
        val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        this.deviceId = id
    }

    fun updateStreamingReadyStatus(isReady: Boolean) {
        this.isStreamingReady = isReady
        sendDeviceStatus()
    }

    fun updateStreamingStatus(isStreaming: Boolean) {
        this.isStreaming = isStreaming
        adjustReportInterval()
        sendDeviceStatus()
    }

    fun updateStreamKey(streamKey: String?) {
        this.streamKey = streamKey
        sendDeviceStatus()
    }

    fun updateRecordingStatus(isRecording: Boolean, durationMs: Long = 0) {
        this.isRecording = isRecording
        this.recordingDurationMs = durationMs
        adjustReportInterval()
        sendDeviceStatus()
    }

    fun start() {
        if (isStarted) {
            Log.d(TAG, "StatusReportingManager is already started.")
            return
        }
        isStarted = true
        Log.d(TAG, "StatusReportingManager started.")
        connect()
    }

    fun stop() {
        isStarted = false
        Log.d(TAG, "StatusReportingManager stopped.")
        webSocket?.close(1000, "Client initiated disconnect.")
        timer?.cancel()
    }

    private fun connect() {
        if (!isStarted || isConnecting) {
            return
        }
        isConnecting = true

        if (streamUrl == null) {
            Log.e(TAG, "❌ Stream URL is null, cannot start WebSocket.")
            isConnecting = false
            return
        }

        val hostname = try {
            java.net.URI.create(streamUrl).host
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse Stream URL", e)
            null
        }

        if (hostname == null) {
            Log.e(TAG, "❌ Could not extract hostname from $streamUrl")
            isConnecting = false
            return
        }

        val wsUrl = "ws://$hostname:8000"
        Log.d(TAG, "🔗 Connecting to WebSocket: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connection opened")
                isConnecting = false
                this@StatusReportingManager.webSocket = webSocket
                startReportTimer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📥 Received message: $text")
                try {
                    val command = gson.fromJson(text, Command::class.java)
                    handleCommand(command)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse command", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket connection failed", t)
                isConnecting = false
                this@StatusReportingManager.webSocket = null
                timer?.cancel()
                maybeReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket connection closing: $reason")
                timer?.cancel()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket connection closed: $reason")
                isConnecting = false
                this@StatusReportingManager.webSocket = null
                maybeReconnect()
            }
        })
    }
    private fun maybeReconnect() {
        if (isStarted) {
            Log.d(TAG, "will try to reconnect in 5 seconds")
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    connect()
                }
            }, 5000)
        }
    }

    /**
     * 根据流媒体和录制状态动态调整上报间隔
     */
    private fun adjustReportInterval() {
        val needActiveInterval = isStreaming || isRecording
        val newInterval = if (needActiveInterval) ACTIVE_REPORT_INTERVAL else NORMAL_REPORT_INTERVAL
        
        if (newInterval != currentReportInterval) {
            currentReportInterval = newInterval
            Log.d(TAG, "📊 调整上报间隔为: ${newInterval}ms (${if (needActiveInterval) "活跃模式" else "正常模式"})")
            // 如果WebSocket已连接，重启定时器
            if (webSocket != null) {
                startReportTimer()
            }
        }
    }

    /**
     * 启动或重启状态上报定时器
     */
    private fun startReportTimer() {
        timer?.cancel()
        timer = timer(period = currentReportInterval) {
            sendDeviceStatus()
        }
        Log.d(TAG, "⏱️ 状态上报定时器已启动，间隔: ${currentReportInterval}ms")
    }

    private fun handleCommand(command: Command) {
        val intent = Intent("streaming-control")
        when (command.command) {
            "startStream" -> {
                Log.d(TAG, "Start stream command received")
                intent.putExtra("action", "start")
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            }
            "stopStream" -> {
                Log.d(TAG, "Stop stream command received")
                intent.putExtra("action", "stop")
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            }
            else -> {
                Log.w(TAG, "Unknown command: ${command.command}")
            }
        }
    }


    private fun sendDeviceStatus() {
        if (webSocket == null) {
            Log.w(TAG, "WebSocket is not connected, skipping status send.")
            return
        }
        val status = getDeviceStatus()
        val update = StatusUpdate(id = deviceId, status = status)
        val statusJson = gson.toJson(update)
        Log.d(TAG, "📤 Sending status: $statusJson")
        webSocket?.send(statusJson)
    }

    private fun getDeviceStatus(): DeviceStatus {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val batteryLevel: Int = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            (level * 100 / scale.toFloat()).toInt()
        } ?: -1

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val wifiSignalStrength = getWifiSignalStrength()
        val isOnline = webSocket != null

        return DeviceStatus(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            wifiSignalStrength = wifiSignalStrength,
            isOnline = isOnline,
            isStreamingReady = this.isStreamingReady,
            isStreaming = this.isStreaming,
            streamKey = this.streamKey,
            isRecording = this.isRecording,
            recordingDurationMs = this.recordingDurationMs
        )
    }

    @Deprecated("use getDeviceStatus()")
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getWifiSignalStrength(): Int {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Android 10 (API 29) 及以上版本需要 `ACCESS_FINE_LOCATION` 权限才能获取Wi-Fi信息
        // 为了简单起见，我们只获取 RSSI，它在大多数情况下可用
        return wifiManager.connectionInfo.rssi
    }
}
