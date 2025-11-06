package ai.fd.thinklet.app.squid.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Foreground service to ensure app is not killed by system when in background
 * Protects streaming, recording and all critical features to keep running
 */
class ThinkletForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private var receiversRegistered = false
    
    // Service state (instance variables)
    private var isStreaming = false
    private var isRecording = false
    private var deviceId: String? = null

    // Streaming control receiver
    private val streamingControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("action")) {
                "start", "stop" -> {
                    // Ensure MainActivity is in foreground, then forward command
                    forwardCommandToActivity(intent)
                }
            }
        }
    }

    // Recording control receiver
    private val recordingControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("action")) {
                "start", "stop" -> {
                    // Ensure MainActivity is in foreground, then forward command
                    forwardCommandToActivity(intent)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ThinkletFgService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "thinklet_foreground_service"
        private const val CHANNEL_NAME = "Thinklet Running Service"
        
        // Service state
        const val ACTION_START = "ai.fd.thinklet.ACTION_START_FOREGROUND"
        const val ACTION_STOP = "ai.fd.thinklet.ACTION_STOP_FOREGROUND"
        const val ACTION_UPDATE_STATUS = "ai.fd.thinklet.ACTION_UPDATE_STATUS"
        
        // Notification state parameters
        const val EXTRA_IS_STREAMING = "extra_is_streaming"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
        const val EXTRA_DEVICE_ID = "extra_device_id"

        /**
         * Start foreground service
         */
        fun start(context: Context, deviceId: String? = null) {
            val intent = Intent(context, ThinkletForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "🚀 Starting foreground service")
        }

        /**
         * Stop foreground service
         */
        fun stop(context: Context) {
            val intent = Intent(context, ThinkletForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
            Log.i(TAG, "🛑 Stopping foreground service")
        }

        /**
         * Update service state (update notification display)
         */
        fun updateStatus(
            context: Context,
            isStreaming: Boolean,
            isRecording: Boolean,
            deviceId: String? = null
        ) {
            val intent = Intent(context, ThinkletForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_IS_STREAMING, isStreaming)
                putExtra(EXTRA_IS_RECORDING, isRecording)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "✅ Service created")
        
        // Create notification channel
        createNotificationChannel()
        
        // Acquire WakeLock to prevent CPU sleep
        acquireWakeLock()
        
        // Register broadcast receivers to receive commands from PC frontend
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                startForegroundService()
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
            ACTION_UPDATE_STATUS -> {
                isStreaming = intent.getBooleanExtra(EXTRA_IS_STREAMING, false)
                isRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: deviceId
                updateNotification()
            }
        }
        
        // If service is killed by system, restart service
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "🛑 Service destroyed")
        unregisterReceivers()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Create notification channel (required for Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance, don't disturb user
            ).apply {
                description = "Keep Thinklet app running in background"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Start foreground service
     */
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "🔔 Foreground service started with notification")
    }

    /**
     * Stop foreground service
     */
    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Update notification content
     */
    private fun updateNotification() {
        val notification = createNotification()
        notificationManager?.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "🔄 Notification updated - Streaming: $isStreaming, Recording: $isRecording")
    }

    /**
     * Create notification
     */
    private fun createNotification(): Notification {
        // Click notification to open MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build status text
        val statusText = buildStatusText()
        val title = if (isStreaming || isRecording) {
            "Thinklet Running"
        } else {
            "Thinklet Standby"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use app icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot swipe to dismiss
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority, don't disturb
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * Build status text
     */
    private fun buildStatusText(): String {
        val statusParts = mutableListOf<String>()
        
        if (isStreaming) {
            statusParts.add("📹 Streaming")
        }
        
        if (isRecording) {
            statusParts.add("🔴 Recording")
        }
        
        if (statusParts.isEmpty()) {
            statusParts.add("Standby")
        }
        
        deviceId?.let {
            statusParts.add("Device: $it")
        }
        
        return statusParts.joinToString(" • ")
    }

    /**
     * Acquire WakeLock to prevent CPU sleep
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Thinklet::ForegroundServiceWakeLock"
            ).apply {
                acquire()
                Log.i(TAG, "🔋 WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to acquire WakeLock", e)
        }
    }

    /**
     * Release WakeLock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "🔋 WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to release WakeLock", e)
        }
    }

    /**
     * Register broadcast receivers to receive commands from StatusReportingManager
     */
    private fun registerReceivers() {
        if (receiversRegistered) {
            Log.w(TAG, "⚠️ Receivers already registered")
            return
        }
        
        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                streamingControlReceiver,
                IntentFilter("streaming-control")
            )
            LocalBroadcastManager.getInstance(this).registerReceiver(
                recordingControlReceiver,
                IntentFilter("recording-control")
            )
            receiversRegistered = true
            Log.i(TAG, "✅ Command receivers registered in foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register receivers", e)
        }
    }

    /**
     * Unregister broadcast receivers
     */
    private fun unregisterReceivers() {
        if (!receiversRegistered) {
            return
        }
        
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(streamingControlReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingControlReceiver)
            receiversRegistered = false
            Log.i(TAG, "✅ Command receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to unregister receivers", e)
        }
    }

    /**
     * Forward command to MainActivity
     * If Activity is not in foreground, will start Activity first
     */
    private fun forwardCommandToActivity(originalIntent: Intent) {
        try {
            // Create Intent to start Activity
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                // Pass original command as extras
                putExtras(originalIntent)
                // Add a flag indicating this is a command forwarded from service
                putExtra("from_service", true)
                putExtra("command_action", originalIntent.action)
            }
            
            startActivity(activityIntent)
            Log.d(TAG, "📤 Command forwarded to MainActivity: ${originalIntent.action}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to forward command to MainActivity", e)
        }
    }
}

