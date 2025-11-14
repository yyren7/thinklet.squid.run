package ai.fd.thinklet.app.squid.run

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.util.Log

class SquidRunApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore by lazy {
        ViewModelStore()
    }
    
    companion object {
        // Track if MainActivity is currently in foreground
        @Volatile
        var isMainActivityInForeground = false
            private set
    }

    // Lazy initialization of NetworkManager
    val networkManager: NetworkManager by lazy {
        NetworkManager(applicationContext)
    }

    // Lazy initialization of StorageManager
    val storageManager: StorageManager by lazy {
        StorageManager(applicationContext)
    }

    // Lazy initialization of StatusReportingManager to ensure it's created only once.
    // It's tied to the Application's lifecycle, not an Activity's.
    val statusReportingManager: StatusReportingManager by lazy {
        // Here we can pass applicationContext, which is valid for the entire app lifecycle.
        // We will get the streamUrl from the ViewModel, which will also be managed at the application level.
        StatusReportingManager(
            context = applicationContext,
            streamUrl = null, // Initialize with null, will be updated later
            networkManager = networkManager, // Inject the NetworkManager instance
            storageManager = storageManager, // Inject the StorageManager instance
            geofenceManager = null  // Will be set later after geofenceManager is initialized
        ).also {
            // Start synchronously to ensure the manager is fully ready when first accessed
            // This prevents race conditions where updateStreamUrl() might be called before start() completes
            it.start()
        }
    }

    // Lazy initialization of LogcatLogger for system log capture
    // Note: start() should be called after permissions are granted
    // Only enabled in debug builds to reduce resource usage in production
    val logcatLogger: LogcatLogger? by lazy {
        if (BuildConfig.ENABLE_LOGCAT_CAPTURE) {
            Log.i("SquidRunApplication", "📝 LogcatLogger enabled (debug build)")
            LogcatLogger.getInstance(applicationContext)
        } else {
            Log.i("SquidRunApplication", "📝 LogcatLogger disabled (release build)")
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Register ActivityLifecycleCallbacks to track MainActivity state
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityInForeground = true
                    Log.d("SquidRunApplication", "📱 MainActivity started (foreground)")
                }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityInForeground = true
                    Log.d("SquidRunApplication", "📱 MainActivity resumed (foreground)")
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityInForeground = false
                    Log.d("SquidRunApplication", "📱 MainActivity paused (background)")
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityInForeground = false
                    Log.d("SquidRunApplication", "📱 MainActivity stopped (background)")
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        Log.i("SquidRunApplication", "✅ ActivityLifecycleCallbacks registered")
        
        // Note: LogcatLogger initialization is deferred until permissions are granted
        // It will be started in MainActivity.onRequestPermissionsResult() after permissions are granted
        
        // Note: StatusReportingManager is lazily initialized and will be created
        // the first time it is accessed. We do NOT initialize it here to avoid
        // generating a temporary UUID before permissions are granted.
        // It will be initialized in MainActivity.initializeMainContent() after all permissions are granted.

        // ⚠️ Important: Immediately trigger TTS initialization in background thread to avoid ANR when first called on main thread
        GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Trigger lazy initialization (on IO thread)
            val tts = ttsManager
            Log.i("SquidRunApplication", "🔄 TTS initialization triggered in background thread")
            
            // Wait for initialization to complete
            tts.ttsReady.first { it }
            Log.i("SquidRunApplication", "✅ TTS ready, announcing application prepared")
            tts.speakApplicationPrepared()
        }
    }

    // Lazy initialization of TTSManager to ensure it's created only once.
    val ttsManager: SherpaOnnxTTSManager by lazy {
        SherpaOnnxTTSManager(applicationContext)
    }

    // Lazy initialization of BeaconScannerManager for iBeacon scanning
    val beaconScannerManager: BeaconScannerManager by lazy {
        BeaconScannerManager(applicationContext)
    }

    // Lazy initialization of BLE device configuration manager
    val bleDeviceConfigManager: BleDeviceConfigManager by lazy {
        BleDeviceConfigManager(applicationContext)
    }

    // Lazy initialization of GeofenceManager for electronic geofencing
    val geofenceManager: GeofenceManager by lazy {
        GeofenceManager(applicationContext, beaconScannerManager).also { manager ->
            Log.i("GeofenceManager", "📍 Initializing geofence zones from BLE config...")
            
            // Load geofence zones from BLE device configuration
            val deviceId = statusReportingManager.deviceId
            val zones = bleDeviceConfigManager.toGeofenceZones(deviceId)
            
            if (zones.isNotEmpty()) {
                zones.forEach { zone -> manager.addGeofenceZone(zone) }
                Log.i("GeofenceManager", "✅ GeofenceManager initialized with ${zones.size} zones from BLE config")
            } else {
                Log.w("GeofenceManager", "⚠️ No BLE devices configured, GeofenceManager has no zones")
            }
            
            // Set GeofenceManager reference in StatusReportingManager
            statusReportingManager.setGeofenceManager(manager)
        }
    }

    override fun onTerminate() {
        // This is where we would stop the StatusReportingManager
        // when the application is terminating.
        Log.i("SquidRunApplication", "🛑 Application terminating, stopping all services")
        
        // Stop foreground service
        ThinkletForegroundService.stop(applicationContext)
        
        statusReportingManager.stop()
        networkManager.unregisterCallback() // Unregister network callback
        ttsManager.shutdown()  // Call the same shutdown method
        geofenceManager.cleanup() // Cleanup geofence manager
        beaconScannerManager.cleanup() // Cleanup beacon scanner
        logcatLogger?.stop() // Stop logcat logger (if enabled)
        super.onTerminate()
    }
}
