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
import android.content.BroadcastReceiver

data class DeviceStatus(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val wifiSignalStrength: Int,
    val isOnline: Boolean,
    var isStreamingReady: Boolean,
    var isStreaming: Boolean,
    var streamKey: String? = null,
    var isRecording: Boolean = false,
    var recordingDurationMs: Long = 0,
    var fileServerPort: Int = 8889,
    var fileServerEnabled: Boolean = false
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
    
    // File transfer server
    private val fileTransferServer: FileTransferServer by lazy {
        FileTransferServer(context, port = 8889)
    }
    private var fileServerEnabled = false

    @Volatile
    private var isStarted = false
    @Volatile
    private var isConnecting = false
    
    // Dynamic reporting interval: 15 seconds normally, 5 seconds during streaming/recording
    private var currentReportInterval: Long = NORMAL_REPORT_INTERVAL
    
    companion object {
        private const val TAG = "StatusReportingManager"
        private const val NORMAL_REPORT_INTERVAL = 60000L  // 60 seconds
        private const val ACTIVE_REPORT_INTERVAL = 60000L   // 60 seconds
    }

    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "🔌 Power connection state changed, sending status update.")
            sendDeviceStatus()
        }
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

    private fun updateRecordingState(isRecording: Boolean, durationMs: Long = 0) {
        this.isRecording = isRecording
        this.recordingDurationMs = durationMs
    }

    fun updateRecordingStatus(isRecording: Boolean, durationMs: Long = 0) {
        val stateChanged = this.isRecording != isRecording
        updateRecordingState(isRecording, durationMs)
        adjustReportInterval()
        if (stateChanged) {
            sendDeviceStatus()
        }
    }

    fun start() {
        if (isStarted) {
            Log.d(TAG, "StatusReportingManager is already started.")
            return
        }
        isStarted = true
        Log.d(TAG, "StatusReportingManager started.")
        
        // Start the file transfer server
        startFileTransferServer()
        
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(powerConnectionReceiver, intentFilter)
        
        connect()
    }
    
    /**
     * Start the file transfer server
     */
    private fun startFileTransferServer() {
        try {
            fileTransferServer.startServer()
            fileServerEnabled = true
            Log.i(TAG, "✅ File transfer server started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start file transfer server", e)
            fileServerEnabled = false
        }
    }
    
    /**
     * Stop the file transfer server
     */
    private fun stopFileTransferServer() {
        try {
            fileTransferServer.stopServer()
            fileServerEnabled = false
            Log.i(TAG, "File transfer server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop file transfer server", e)
        }
    }

    fun stop() {
        isStarted = false
        Log.d(TAG, "StatusReportingManager stopped.")
        
        // Stop the file transfer server
        stopFileTransferServer()
        
        context.unregisterReceiver(powerConnectionReceiver)
        
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
                    if (command?.command == null) {
                        Log.w(TAG, "Received message with unknown format: $text")
                        return
                    }
                    handleCommand(command)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse command from message: $text", e)
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
     * Dynamically adjust the reporting interval based on streaming and recording status
     */
    private fun adjustReportInterval() {
        val needActiveInterval = isStreaming || isRecording
        val newInterval = if (needActiveInterval) ACTIVE_REPORT_INTERVAL else NORMAL_REPORT_INTERVAL
        
        if (newInterval != currentReportInterval) {
            currentReportInterval = newInterval
            Log.d(TAG, "📊 Adjusted reporting interval to: ${newInterval}ms (${if (needActiveInterval) "Active mode" else "Normal mode"})")
            // If WebSocket is connected, restart the timer
            if (webSocket != null) {
                startReportTimer()
            }
        }
    }

    /**
     * Start or restart the status reporting timer
     */
    private fun startReportTimer() {
        timer?.cancel()
        timer = timer(period = currentReportInterval) {
            sendDeviceStatus()
        }
        Log.d(TAG, "⏱️ Status reporting timer started with interval: ${currentReportInterval}ms")
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
            recordingDurationMs = this.recordingDurationMs,
            fileServerPort = 8889,
            fileServerEnabled = this.fileServerEnabled
        )
    }

    @Deprecated("use getDeviceStatus()")
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getWifiSignalStrength(): Int {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Android 10 (API 29) and above require `ACCESS_FINE_LOCATION` permission to get Wi-Fi information.
        // For simplicity, we only get the RSSI, which is available in most cases.
        return wifiManager.connectionInfo.rssi
    }
}
