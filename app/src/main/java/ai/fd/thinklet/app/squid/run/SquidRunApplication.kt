package ai.fd.thinklet.app.squid.run

import android.app.Application
import android.content.Context
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
            storageManager = storageManager // Inject the StorageManager instance
        ).also {
            GlobalScope.launch {
                it.start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The StatusReportingManager is lazily initialized,
        // so it will be created and started the first time it is accessed.
        // We can trigger the creation here if we want it to start immediately with the app.
        statusReportingManager

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

    // Lazy initialization of GeofenceManager for electronic geofencing
    val geofenceManager: GeofenceManager by lazy {
        GeofenceManager(applicationContext, beaconScannerManager).also {
            // Add example geofence zones (can be configured according to actual needs)
            // Here we add an example geofence, you can configure it according to actual iBeacon device information
            Log.i("GeofenceManager", "📍 Initializing geofence zones...")
            
            // Configure geofence zone
            // Based on actual Beacon device info: UUID=E2C56DB5-DFFB-48D2-B060-D0F5A71096E0, Major=0, Minor=0
            val zone1 = GeofenceZone(
                id = "zone_1",
                name = "Geofence Zone 1",
                beaconUuid = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
                beaconMajor = 0,  // Actual Beacon Major value
                beaconMinor = 0,  // Actual Beacon Minor value
                radiusMeters = 10.0,
                enabled = true
            )
            it.addGeofenceZone(zone1)
            
            Log.i("GeofenceManager", "✅ GeofenceManager initialized")
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
        super.onTerminate()
    }
}
