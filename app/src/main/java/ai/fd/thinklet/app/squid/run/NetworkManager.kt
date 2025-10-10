package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized manager for network-related operations.
 * It monitors network connectivity and provides network information like Wi-Fi signal strength.
 * This class is designed to be a singleton, managed by the Application class.
 */
class NetworkManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _isConnected = MutableStateFlow(isNetworkAvailable())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "✅ Network is available.")
            _isConnected.value = true
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(TAG, "❌ Network connection lost.")
            _isConnected.value = false
        }
    }

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    /**
     * Checks if a network connection is currently available.
     */
    @Suppress("DEPRECATION")
    private fun isNetworkAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * Gets the current Wi-Fi signal strength (RSSI) in dBm.
     * Note: Requires ACCESS_FINE_LOCATION for accurate results on newer Android versions,
     * but RSSI is often available without it.
     */
    fun getWifiSignalStrength(): Int {
        // This logic is moved from StatusReportingManager for reusability.
        return wifiManager.connectionInfo.rssi
    }
    
    /**
     * Unregisters the network callback to prevent memory leaks.
     * Should be called when the application is shutting down.
     */
    fun unregisterCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.i(TAG, "Network callback unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    companion object {
        private const val TAG = "NetworkManager"
    }
}
