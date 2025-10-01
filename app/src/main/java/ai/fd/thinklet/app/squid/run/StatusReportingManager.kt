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
import kotlin.concurrent.timer

data class DeviceStatus(
    val batteryLevel: Int,
    val wifiSignalStrength: Int,
    val isOnline: Boolean,
    var isStreamingReady: Boolean
)

data class StatusUpdate(
    val id: String,
    val status: DeviceStatus
)

class StatusReportingManager(
    private val context: Context,
    private val streamUrl: String?
) {

    var isStreamingReady: Boolean = false
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var timer: Timer? = null
    private val gson = Gson()
    val deviceId: String

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

    fun start() {
        if (streamUrl == null) {
            Log.e(TAG, "❌ Stream URL为空，无法启动WebSocket")
            return
        }

        val hostname = try {
            // 从 rtmp://hostname:port/app... 中提取 hostname
            val uri = java.net.URI.create(streamUrl)
            uri.host
        } catch (e: Exception) {
            Log.e(TAG, "❌ 解析Stream URL失败", e)
            null
        }

        if (hostname == null) {
            Log.e(TAG, "❌ 无法从 $streamUrl 中提取主机名")
            return
        }

        val wsUrl = "ws://$hostname:8000"
        Log.d(TAG, "🔗 正在连接到WebSocket: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket 连接已打开")
                // 连接成功后，开始定期发送状态
                timer = timer(period = 5000) {
                    sendDeviceStatus()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📥 收到消息: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket 连接失败", t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket 连接正在关闭: $reason")
                timer?.cancel()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WebSocket 连接已关闭: $reason")
            }
        })
    }

    fun stop() {
        webSocket?.close(1000, "客户端主动断开连接")
        timer?.cancel()
    }

    private fun sendDeviceStatus() {
        val status = getDeviceStatus()
        val update = StatusUpdate(id = deviceId, status = status)
        val statusJson = gson.toJson(update)
        Log.d(TAG, "📤 正在发送状态: $statusJson")
        webSocket?.send(statusJson)
    }

    private fun getDeviceStatus(): DeviceStatus {
        val batteryLevel = getBatteryLevel()
        val wifiSignalStrength = getWifiSignalStrength()
        // 这两个状态需要从 MainViewModel 中获取，我们暂时先用假数据
        val isOnline = webSocket != null

        return DeviceStatus(
            batteryLevel = batteryLevel,
            wifiSignalStrength = wifiSignalStrength,
            isOnline = isOnline,
            isStreamingReady = this.isStreamingReady
        )
    }

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

    companion object {
        private const val TAG = "StatusReportingManager"
    }
}
