package ai.fd.thinklet.app.squid.run

import android.app.ActivityManager
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    
    // Activity monitoring (continuous background detection)
    private val handler = Handler(Looper.getMainLooper())
    private var activityMonitorRunnable: Runnable? = null
    private var isMonitoring = false
    private var lastForegroundState = true // Track last known state to avoid spam logs

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
        private const val ACTIVITY_CHECK_INTERVAL_MS = 2000L // Check every 2 seconds
        
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
        // Stop monitoring if still running
        stopActivityMonitoring()
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
        
        // Start monitoring MainActivity state to auto-bring to foreground
        startActivityMonitoring()
    }

    /**
     * Stop foreground service
     */
    private fun stopForegroundService() {
        // Stop monitoring before stopping service
        stopActivityMonitoring()
        
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
        // Use FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP to resume existing activity
        // instead of creating a new one (prevents interrupting streaming/recording)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
     * Check if MainActivity is in foreground
     * Uses Application-level lifecycle tracking for reliable detection on all Android versions
     */
    private fun isMainActivityInForeground(): Boolean {
        val isInForeground = SquidRunApplication.isMainActivityInForeground
        Log.d(TAG, "🔍 Checking activity state via Application: $isInForeground")
        return isInForeground
    }
    
    /**
     * Check if user is on home screen (Launcher)
     * This helps distinguish between Home button press vs. other background scenarios
     * (notification drawer, recent apps, switching to other apps)
     */
    private fun isUserOnHomeScreen(): Boolean {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // Get the top activity using getRunningTasks (works on Android 8.1)
            val tasks = activityManager.getRunningTasks(1)
            
            if (tasks.isNotEmpty()) {
                val topActivity = tasks[0].topActivity
                val topPackage = topActivity?.packageName
                
                Log.d(TAG, "🔍 Top activity: ${topActivity?.className}")
                Log.d(TAG, "🔍 Top package: $topPackage")
                
                val isLauncher = isLauncherPackage(topPackage)
                Log.d(TAG, "🔍 Is launcher: $isLauncher")
                
                return isLauncher
            } else {
                Log.d(TAG, "🔍 No running tasks found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to detect home screen", e)
        }
        return false
    }
    
    /**
     * Check if the given package name is a Launcher (home screen app)
     * Covers common launchers from major manufacturers + Thinklet custom launcher
     */
    private fun isLauncherPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        
        // Common launcher package names from major manufacturers
        val launcherPackages = setOf(
            // Thinklet Custom Launcher (Priority - our own launcher)
            "ai.fd.thinklet.app.launcher",
            
            // AOSP / Google
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            
            // Huawei
            "com.huawei.android.launcher",
            
            // Samsung
            "com.sec.android.app.launcher",
            "com.sec.android.app.twlauncher",
            
            // Xiaomi / MIUI
            "com.miui.home",
            
            // OPPO / ColorOS
            "com.oppo.launcher",
            
            // Vivo / Funtouch OS
            "com.bbk.launcher2",
            "com.vivo.launcher",
            
            // OnePlus
            "net.oneplus.launcher",
            
            // Sony
            "com.sonyericsson.home",
            
            // Lenovo
            "com.lenovo.launcher",
            
            // LG
            "com.lge.launcher2",
            "com.lge.launcher3",
            
            // Motorola
            "com.motorola.launcher3",
            
            // ASUS
            "com.asus.launcher",
            
            // Meizu
            "com.meizu.flyme.launcher"
        )
        
        // Check if package name contains any launcher identifier
        return launcherPackages.any { packageName.contains(it) }
    }
    
    /**
     * Bring MainActivity to foreground
     * This method can be used to bring the app to foreground when needed
     */
    private fun bringMainActivityToForeground() {
        try {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(activityIntent)
            Log.i(TAG, "📱 MainActivity brought to foreground")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to bring MainActivity to foreground", e)
        }
    }
    
    /**
     * Forward command to MainActivity
     * If Activity is not in foreground, will bring it to foreground first, then forward command
     */
    private fun forwardCommandToActivity(originalIntent: Intent) {
        try {
            // Extract command type from Intent action (e.g., "streaming-control" or "recording-control")
            val commandAction = originalIntent.action ?: return
            val action = originalIntent.getStringExtra("action") ?: return
            
            // Check if MainActivity is in foreground (monitoring should already keep it in foreground)
            val isInForeground = isMainActivityInForeground()
            if (!isInForeground) {
                Log.i(TAG, "⚠️ MainActivity not in foreground, bringing it to foreground first")
            }
            
            // Create Intent to start/resume Activity and forward command
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Pass command information
                putExtra("from_service", true)
                putExtra("command_action", commandAction)  // "streaming-control" or "recording-control"
                putExtra("action", action)  // "start" or "stop"
            }
            
            startActivity(activityIntent)
            Log.d(TAG, "📤 Command forwarded to MainActivity: $commandAction, action: $action (was in foreground: $isInForeground)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to forward command to MainActivity", e)
        }
    }
    
    /**
     * Start monitoring MainActivity state
     * Periodically checks if MainActivity is in foreground, and brings it to foreground if not
     * This ensures physical buttons always work by keeping the app in foreground
     */
    private fun startActivityMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "⚠️ Activity monitoring already started")
            return
        }
        
        isMonitoring = true
        lastForegroundState = true // Assume it's in foreground initially
        
        activityMonitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) {
                    Log.d(TAG, "🔍 Monitor check skipped - monitoring stopped")
                    return
                }
                
                Log.d(TAG, "🔍 Running periodic activity check...")
                val isInForeground = isMainActivityInForeground()
                Log.d(TAG, "🔍 Result: isInForeground=$isInForeground, lastState=$lastForegroundState")
                
                // Check if MainActivity is in background
                if (!isInForeground) {
                    // MainActivity is in background, check if user is on home screen
                    val isOnHomeScreen = isUserOnHomeScreen()
                    
                    if (isOnHomeScreen) {
                        // User is on launcher - bring app back to foreground
                        Log.i(TAG, "🏠 User on home screen detected, bringing MainActivity to foreground automatically")
                        bringMainActivityToForeground()
                    } else {
                        // User is in another app, notification drawer, or recent apps - don't interrupt
                        if (lastForegroundState) {
                            // First time detecting background
                            Log.d(TAG, "📱 MainActivity in background but user NOT on home screen - no action")
                            Log.d(TAG, "   (User may be in: notification drawer, recent apps, or another app)")
                        }
                        // Don't log repeatedly when still in background
                    }
                } else if (isInForeground && !lastForegroundState) {
                    Log.d(TAG, "✅ MainActivity returned to foreground")
                }
                
                lastForegroundState = isInForeground
                
                // Schedule next check
                handler.postDelayed(this, ACTIVITY_CHECK_INTERVAL_MS)
            }
        }
        
        handler.post(activityMonitorRunnable!!)
        Log.i(TAG, "✅ Activity monitoring started (check interval: ${ACTIVITY_CHECK_INTERVAL_MS}ms)")
    }
    
    /**
     * Stop monitoring MainActivity state
     */
    private fun stopActivityMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        isMonitoring = false
        activityMonitorRunnable?.let {
            handler.removeCallbacks(it)
        }
        activityMonitorRunnable = null
        Log.i(TAG, "🛑 Activity monitoring stopped")
    }
}


