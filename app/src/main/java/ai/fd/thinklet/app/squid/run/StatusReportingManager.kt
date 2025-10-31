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
import java.net.ConnectException
import java.net.SocketTimeoutException
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

enum class DeviceType {
    LANDSCAPE, // Landscape device (starts with M)
    PORTRAIT   // Portrait device (starts with P)
}

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

data class FileListUpdateNotification(
    val type: String = "fileListUpdated",
    val deviceId: String
)

class StatusReportingManager(
    private val context: Context,
    private var streamUrl: String?,
    private val networkManager: NetworkManager
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
    private var reportTimer: Timer? = null
    private var reconnectTimer: Timer? = null
    private var reconnectAttempts = 0
    private val gson = Gson()
    val deviceId: String
    val deviceIdSource: String
    val deviceType: DeviceType

    // File transfer server - completely subordinate to StatusReportingManager
    // Its lifecycle can only be controlled by the start() and stop() methods
    private val fileTransferServer: FileTransferServer by lazy {
        FileTransferServer(context, port = 8889)
    }
    private var fileServerEnabled = false  // Whether the file server was successfully started
    private var fileServerInitialized = false  // Whether the file server has been initialized

    @Volatile
    private var isStarted = false
    @Volatile
    private var isConnecting = false
    @Volatile
    private var isReceiverRegistered = false
    
    // Dynamic reporting interval: 15 seconds normally, 5 seconds during streaming/recording
    private var currentReportInterval: Long = NORMAL_REPORT_INTERVAL
    
    companion object {
        const val ACTION_SERVER_DISCONNECTED = "ai.fd.thinklet.app.squid.run.SERVER_DISCONNECTED"
        private const val TAG = "StatusReportingManager"
        private const val NORMAL_REPORT_INTERVAL = 5000L  // 5 seconds
        private const val ACTIVE_REPORT_INTERVAL = 5000L   // 5 seconds
        private const val INITIAL_RECONNECT_DELAY = 2000L  // 2 seconds
        private const val MAX_RECONNECT_DELAY = 60000L     // 60 seconds
        private const val RECONNECT_JITTER = 500           // 0.5 seconds
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
        this.deviceType = when {
            id.startsWith("P", ignoreCase = true) -> DeviceType.PORTRAIT
            else -> DeviceType.LANDSCAPE // Starts with M or other cases, defaults to landscape
        }
        // Start listening to network state changes
        GlobalScope.launch {
            var isFirstNetworkCheck = true
            networkManager.isConnected.collect { isConnected ->
                // Skip the initial state emission on startup
                if (isFirstNetworkCheck) {
                    isFirstNetworkCheck = false
                    return@collect
                }

                if (isConnected) {
                    Log.i(TAG, "Network connection has returned. Attempting to reconnect WebSocket.")
                    // When network comes back, try to reconnect WebSocket if the URL is valid
                    if (!streamUrl.isNullOrBlank()) {
                        reconnectWebSocket()
                    } else {
                        Log.w(TAG, "Network is back, but streamUrl is not set yet. Skipping reconnect.")
                    }
                } else {
                    Log.w(TAG, "Network connection lost. Stopping any reconnect attempts.")
                    // When network is lost, cancel any pending reconnect tasks
                    cancelReconnectTimer()
                    // Close WebSocket to prevent stale connections
                    webSocket?.close(1001, "Network lost")
                    webSocket = null
                }
            }
        }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun initializeDeviceId(): Pair<String, String> {
        // Priority 1: Always try to get hardware serial if permission is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    // Note: Build.getSerial() requires READ_PHONE_STATE permission for regular apps.
                    // The Lint warning about READ_PRIVILEGED_PHONE_STATE is for system apps only.
                    // We have already checked READ_PHONE_STATE permission above.
                    val serial = Build.getSerial()
                    if (serial != null && serial != Build.UNKNOWN && serial.isNotBlank()) {
                        Log.i(TAG, "Using hardware serial as device ID.")
                        return Pair(serial, "Hardware Serial")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to get serial number due to security exception.", e)
                }
            } else {
                Log.w(TAG, "READ_PHONE_STATE permission not granted. Cannot get serial number.")
            }
        }

        // Fallback: If serial is unavailable, generate a new temporary UUID for this session.
        // This ID will not be saved.
        Log.w(TAG, "Could not get hardware serial. Generating temporary UUID for this session.")
        val newId = UUID.randomUUID().toString()
        return Pair(newId, "Temporary UUID")
    }

    fun updateStreamUrl(newStreamUrl: String?) {
        if (this.streamUrl != newStreamUrl) {
            this.streamUrl = newStreamUrl
            Log.d(TAG, "Stream URL updated to: $newStreamUrl")
            // Reconnect WebSocket immediately to use the new URL
            reconnectWebSocket()
        }
    }

    /**
     * Reconnect WebSocket without stopping the entire StatusReportingManager.
     * This is more efficient than stop() + start() and avoids stopping file server and re-registering receivers.
     */
    private fun reconnectWebSocket() {
        if (!isStarted) {
            Log.w(TAG, "StatusReportingManager is not started, cannot reconnect WebSocket")
            return
        }
        
        Log.d(TAG, "🔄 Reconnecting WebSocket...")
        
        // Cancel any pending reconnect timers
        cancelReconnectTimer()
        
        // Close existing WebSocket connection if any
        try {
            val ws = webSocket
            if (ws != null) {
                ws.close(1000, "Reconnecting with new URL")
                webSocket = null
                Log.d(TAG, "✅ Existing WebSocket closed for reconnection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to close existing WebSocket", e)
        }
        
        // Cancel report timer (it will be restarted when connection succeeds)
        reportTimer?.cancel()
        reportTimer = null
        
        // Reset reconnect attempts since this is a manual reconnect
        reconnectAttempts = 0
        
        // Start new connection immediately
        connect()
    }

    private fun reconnect(immediate: Boolean = false) {
        // Reconnect WebSocket without stopping the file server
        // This avoids port binding issues and unnecessary service restarts
        reconnectWebSocket()
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

    /**
     * Starts the StatusReportingManager.
     * The file transfer server will start with it, and their lifecycles are bound.
     */
    fun start() {
        if (isStarted) {
            Log.d(TAG, "⚠️ StatusReportingManager is already started.")
            return
        }
        
        Log.i(TAG, "🚀 Starting StatusReportingManager...")
        isStarted = true
        
        // 1. Start the file transfer server (must be started first to ensure the service is ready for status reporting).
        startFileTransferServer()
        
        // 2. Register for power state listening.
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(powerConnectionReceiver, intentFilter)
        isReceiverRegistered = true
        
        // 3. Establish WebSocket connection.
        connect()
        
        Log.i(TAG, "✅ StatusReportingManager started (fileServer: $fileServerEnabled)")
    }
    
    /**
     * Starts the file transfer server.
     * Can only be called by the start() method to ensure consistent lifecycle.
     */
    private fun startFileTransferServer() {
        // If already initialized and enabled, the server is running, no need to start again.
        if (fileServerInitialized && fileServerEnabled) {
            Log.d(TAG, "📁 File transfer server is already running on port 8889")
            return
        }
        
        try {
            Log.i(TAG, "📁 Starting file transfer server on port 8889...")
            fileTransferServer.startServer()
            fileServerEnabled = true
            fileServerInitialized = true
            Log.i(TAG, "✅ File transfer server started successfully on port 8889")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start file transfer server on port 8889", e)
            fileServerEnabled = false
            fileServerInitialized = false
            // Do not throw an exception, allow StatusReportingManager to continue running (file transfer will be unavailable).
        }
    }
    
    /**
     * Stops the file transfer server.
     * Can only be called by the stop() method to ensure consistent lifecycle.
     * Waits synchronously for the server to fully stop and release the port.
     */
    private fun stopFileTransferServer() {
        // If the server was never started, return directly.
        if (!fileServerInitialized) {
            Log.d(TAG, "📁 File transfer server was never started, skipping stop")
            return
        }
        
        // If the server is already stopped, avoid repeated operations.
        if (!fileServerEnabled) {
            Log.d(TAG, "📁 File transfer server is already stopped")
            return
        }
        
        try {
            Log.i(TAG, "🛑 Stopping file transfer server on port 8889...")
            fileTransferServer.stopServer()
            fileServerEnabled = false
            
            // Wait a moment to ensure the port is fully released.
            Thread.sleep(500)
            Log.i(TAG, "✅ File transfer server stopped and port 8889 released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop file transfer server", e)
            // Even if stopping fails, mark as not enabled to avoid inconsistent state.
            fileServerEnabled = false
        }
    }

    /**
     * Stops the StatusReportingManager.
     * The file transfer server will stop with it, and their lifecycles are bound.
     * Waits synchronously for all resources to be released.
     */
    fun stop() {
        if (!isStarted) {
            Log.d(TAG, "⚠️ StatusReportingManager is already stopped.")
            return
        }
        
        Log.i(TAG, "🛑 Stopping StatusReportingManager...")
        isStarted = false
        
        // 1. Cancel all timers.
        try {
            reportTimer?.cancel()
            reportTimer?.purge()
            reportTimer = null
            cancelReconnectTimer()
            Log.d(TAG, "✅ Timers cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cancel timers", e)
        }
        
        // 2. Close the WebSocket connection.
        try {
            val ws = webSocket
            if (ws != null) {
                ws.close(1000, "StatusReportingManager stopped")
                // Wait for the WebSocket to fully close.
                Thread.sleep(300)
                webSocket = null
                Log.d(TAG, "✅ WebSocket closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to close WebSocket", e)
        }
        
        // 3. Stop the file transfer server (critical! must wait for port release).
        stopFileTransferServer()
        
        // 4. Unregister the broadcast receiver.
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(powerConnectionReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "✅ BroadcastReceiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to unregister receiver", e)
            }
        }
        
        Log.i(TAG, "✅ StatusReportingManager stopped completely (fileServer: $fileServerEnabled)")
    }

    private fun connect() {
        if (!isStarted || isConnecting) {
            return
        }
        isConnecting = true

        if (!networkManager.isConnected.value) {
            Log.w(TAG, "No network connection. Aborting connect attempt.")
            isConnecting = false
            return
        }

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
                Log.i(TAG, "✅ WebSocket connection opened")
                isConnecting = false
                this@StatusReportingManager.webSocket = webSocket
                // Reset reconnect attempts on successful connection
                reconnectAttempts = 0
                
                // Immediately send device status once to let the PC side know the file server status.
                sendDeviceStatus()
                
                // Then start the periodic reporting timer.
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
                if (t is ConnectException || t is SocketTimeoutException) {
                    Log.e(TAG, "❌ WebSocket: Could not connect to server: ${t.message}")
                    // Broadcast that the server is disconnected
                    val intent = Intent(ACTION_SERVER_DISCONNECTED)
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                } else {
                    Log.e(TAG, "❌ WebSocket connection failed", t)
                }
                isConnecting = false
                this@StatusReportingManager.webSocket = null
                reportTimer?.cancel()
                maybeReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket connection closing: $reason")
                reportTimer?.cancel()
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
        if (isStarted && networkManager.isConnected.value) {
            reconnectAttempts++
            // Limit reconnect attempts to prevent overflow from left shift operation (max 1 shl 6 = 64x delay).
            val safeAttempts = reconnectAttempts.coerceAtMost(7)
            val delay = (INITIAL_RECONNECT_DELAY * (1 shl (safeAttempts - 1))).coerceAtMost(MAX_RECONNECT_DELAY)
            val jitter = (Math.random() * RECONNECT_JITTER).toLong()
            val totalDelay = delay + jitter
            
            Log.d(TAG, "Will try to reconnect in ${totalDelay / 1000} seconds (attempt #${reconnectAttempts})")

            cancelReconnectTimer()
            reconnectTimer = Timer("ReconnectTimer")
            reconnectTimer?.schedule(object : TimerTask() {
                override fun run() {
                    connect()
                }
            }, totalDelay)
        }
    }

    private fun cancelReconnectTimer() {
        reconnectTimer?.cancel()
        reconnectTimer?.purge()
        reconnectTimer = null
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
        reportTimer?.cancel()
        reportTimer = timer(period = currentReportInterval) {
            sendDeviceStatus()
        }
        Log.d(TAG, "⏱️ Status reporting timer started with interval: ${currentReportInterval}ms")
    }

    private fun handleCommand(command: Command) {
        val streamIntent = Intent("streaming-control")
        val recordIntent = Intent("recording-control")
        when (command.command) {
            "startStream" -> {
                Log.d(TAG, "Start stream command received")
                streamIntent.putExtra("action", "start")
                LocalBroadcastManager.getInstance(context).sendBroadcast(streamIntent)
            }
            "stopStream" -> {
                Log.d(TAG, "Stop stream command received")
                streamIntent.putExtra("action", "stop")
                LocalBroadcastManager.getInstance(context).sendBroadcast(streamIntent)
            }
            "startRecording" -> {
                Log.d(TAG, "Start recording command received")
                recordIntent.putExtra("action", "start")
                LocalBroadcastManager.getInstance(context).sendBroadcast(recordIntent)
            }
            "stopRecording" -> {
                Log.d(TAG, "Stop recording command received")
                recordIntent.putExtra("action", "stop")
                LocalBroadcastManager.getInstance(context).sendBroadcast(recordIntent)
            }
            else -> {
                Log.w(TAG, "Unknown command: ${command.command}")
            }
        }
    }

    fun sendOfflineStatusAndStop() {
        if (webSocket == null) {
            Log.w(TAG, "WebSocket is not connected, skipping offline status send.")
        } else {
            val status = getDeviceStatus().copy(isOnline = false)
            val update = StatusUpdate(id = deviceId, status = status)
            val statusJson = gson.toJson(update)
            Log.d(TAG, "📤 Sending offline status: $statusJson")
            webSocket?.send(statusJson)
            // Wait for a short while to ensure the message is sent.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Log.e(TAG, "Wait for offline message sending interrupted", e)
            }
        }
        stop()
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
        Log.d(TAG, "📊 File server status: enabled=$fileServerEnabled, initialized=$fileServerInitialized, port=8889")
        webSocket?.send(statusJson)
    }

    /**
     * Send a notification to PC side that the file list has been updated.
     * This allows PC side to immediately trigger a file scan instead of waiting for the next polling cycle.
     */
    fun notifyFileListUpdated() {
        if (webSocket == null) {
            Log.w(TAG, "WebSocket is not connected, skipping file list update notification.")
            return
        }
        if (!fileServerEnabled) {
            Log.d(TAG, "File server is not enabled, skipping file list update notification.")
            return
        }
        val notification = FileListUpdateNotification(deviceId = deviceId)
        val notificationJson = gson.toJson(notification)
        Log.d(TAG, "📤 Sending file list update notification: $notificationJson")
        webSocket?.send(notificationJson)
    }

    /**
     * Gets the device status.
     * Includes complete status information for the file server, all information is obtained uniformly from StatusReportingManager.
     */
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

        val wifiSignalStrength = networkManager.getWifiSignalStrength()
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
            fileServerPort = 8889,  // File server port (fixed)
            fileServerEnabled = this.fileServerEnabled  // File server status (managed by StatusReportingManager)
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
