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
import android.os.Build
import androidx.core.content.ContextCompat
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager

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
    private var streamUrl: String?
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
    val deviceIdSource: String

    // File transfer server
    private val fileTransferServer: FileTransferServer by lazy {
        FileTransferServer(context, port = 8889)
    }
    private var fileServerEnabled = false
    private var fileServerInitialized = false

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
        val (id, source) = initializeDeviceId()
        this.deviceId = id
        this.deviceIdSource = source
    }

    @SuppressLint("HardwareIds")
    private fun initializeDeviceId(): Pair<String, String> {
        val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)

        // Priority 1: Always try to get hardware serial if permission is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val serial = Build.getSerial()
                    if (serial != null && serial != Build.UNKNOWN && serial.isNotBlank()) {
                        Log.i(TAG, "Using hardware serial as device ID. Overwriting previous ID if any.")
                        // If the current saved ID is different, overwrite it.
                        if (prefs.getString("device_id", null) != serial) {
                            prefs.edit().putString("device_id", serial).apply()
                        }
                        return Pair(serial, "Hardware Serial")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to get serial number due to security exception.", e)
                }
            } else {
                Log.w(TAG, "READ_PHONE_STATE permission not granted. Cannot get serial number.")
            }
        }

        // Priority 2: If serial is unavailable, use a previously saved ID.
        val savedId = prefs.getString("device_id", null)
        if (savedId != null) {
            Log.i(TAG, "Using previously saved ID (could not get hardware serial).")
            return Pair(savedId, "Generated UUID")
        }

        // Priority 3: If no ID has ever been saved, generate a new UUID.
        Log.i(TAG, "Generating new UUID as device ID.")
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", newId).apply()
        return Pair(newId, "Generated UUID")
    }

    fun updateStreamUrl(newStreamUrl: String?) {
        if (this.streamUrl != newStreamUrl) {
            this.streamUrl = newStreamUrl
            Log.d(TAG, "Stream URL updated to: $newStreamUrl")
            // 重新连接以使用新的 URL
            reconnect()
        }
    }

    private fun reconnect() {
        // Stop the existing connection and timer, then start a new connection.
        stop()
        start()
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
            fileServerInitialized = true
            Log.i(TAG, "✅ File transfer server started on port 8889")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start file transfer server", e)
            fileServerEnabled = false
        }
    }
    
    /**
     * Stop the file transfer server
     * 同步等待服务器完全停止
     */
    private fun stopFileTransferServer() {
        // 只有在服务器已经被初始化时才停止它
        if (!fileServerInitialized) {
            Log.d(TAG, "File transfer server was never started, skipping stop")
            return
        }
        
        try {
            Log.i(TAG, "🛑 Stopping file transfer server...")
            fileTransferServer.stopServer()
            fileServerEnabled = false
            
            // 等待一段时间确保端口完全释放
            Thread.sleep(500)
            Log.i(TAG, "✅ File transfer server stopped and port 8889 released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop file transfer server", e)
        }
    }

    /**
     * 停止 StatusReportingManager 并同步等待所有资源释放完成
     */
    fun stop() {
        if (!isStarted) {
            Log.d(TAG, "StatusReportingManager is already stopped.")
            return
        }
        
        Log.d(TAG, "🛑 Stopping StatusReportingManager...")
        isStarted = false
        
        // 1. 取消定时器
        try {
            timer?.cancel()
            timer?.purge()
            timer = null
            Log.d(TAG, "✅ Timer cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cancel timer", e)
        }
        
        // 2. 关闭 WebSocket 连接
        try {
            val ws = webSocket
            if (ws != null) {
                ws.close(1000, "Client initiated disconnect.")
                // 等待 WebSocket 关闭完成
                Thread.sleep(300)
                webSocket = null
                Log.d(TAG, "✅ WebSocket closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to close WebSocket", e)
        }
        
        // 3. 停止文件传输服务器（关键！必须等待端口释放）
        stopFileTransferServer()
        
        // 4. 注销广播接收器
        try {
            context.unregisterReceiver(powerConnectionReceiver)
            Log.d(TAG, "✅ BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to unregister receiver", e)
        }
        
        Log.i(TAG, "✅ StatusReportingManager stopped completely")
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
