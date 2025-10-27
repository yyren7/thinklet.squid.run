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

    // Lazy initialization of StatusReportingManager to ensure it's created only once.
    // It's tied to the Application's lifecycle, not an Activity's.
    val statusReportingManager: StatusReportingManager by lazy {
        // Here we can pass applicationContext, which is valid for the entire app lifecycle.
        // We will get the streamUrl from the ViewModel, which will also be managed at the application level.
        StatusReportingManager(
            context = applicationContext,
            streamUrl = null, // Initialize with null, will be updated later
            networkManager = networkManager // Inject the NetworkManager instance
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

        // Announce at the Application level to ensure it's spoken only once per application lifecycle.
        GlobalScope.launch {
            ttsManager.ttsReady.first { it }
            Log.i("SquidRunApplication", "✅ TTS ready, announcing application prepared")
            ttsManager.speakApplicationPrepared()
        }
    }

    // Lazy initialization of TTSManager to ensure it's created only once.
    val ttsManager: SherpaOnnxTTSManager by lazy {
        SherpaOnnxTTSManager(applicationContext)
    }

    override fun onTerminate() {
        // This is where we would stop the StatusReportingManager
        // when the application is terminating.
        statusReportingManager.stop()
        networkManager.unregisterCallback() // Unregister network callback
        ttsManager.shutdown()  // Call the same shutdown method
        super.onTerminate()
    }
}
