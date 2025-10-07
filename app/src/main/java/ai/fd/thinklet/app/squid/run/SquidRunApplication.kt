package ai.fd.thinklet.app.squid.run

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

class SquidRunApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore by lazy {
        ViewModelStore()
    }

    // Lazy initialization of StatusReportingManager to ensure it's created only once.
    // It's tied to the Application's lifecycle, not an Activity's.
    val statusReportingManager: StatusReportingManager by lazy {
        // Here we can pass applicationContext, which is valid for the entire app lifecycle.
        // We will get the streamUrl from the ViewModel, which will also be managed at the application level.
        StatusReportingManager(
            context = applicationContext,
            streamUrl = null // Initialize with null, will be updated later
        ).also {
            it.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The StatusReportingManager is lazily initialized,
        // so it will be created and started the first time it is accessed.
        // We can trigger the creation here if we want it to start immediately with the app.
        statusReportingManager
    }

    // Lazy initialization of TTSManager to ensure it's created only once.
    val ttsManager: TTSManager by lazy {
        TTSManager(applicationContext)
    }

    override fun onTerminate() {
        // This is where we would stop the StatusReportingManager
        // when the application is terminating.
        statusReportingManager.stop()
        ttsManager.shutdown()
        super.onTerminate()
    }
}
