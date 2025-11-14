package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BLE device configuration
 * This represents a single BLE device (iBeacon) that can be used for geofencing
 */
data class BleDevice(
    val id: String,              // Unique ID for this BLE device
    val name: String,            // Friendly name
    val uuid: String,            // iBeacon UUID
    val major: Int,              // iBeacon Major value
    val minor: Int,              // iBeacon Minor value
    val radiusMeters: Double = 5.0,  // Default geofence radius in meters
    val enabled: Boolean = true   // Whether this BLE device is active
)

/**
 * Configuration for which BLE devices are enabled for a specific Android device
 */
data class DeviceBleConfig(
    val deviceId: String,         // Android device ID
    val enabledBleDevices: Map<String, Boolean> = emptyMap()  // Map of BLE device ID -> enabled status
)

/**
 * BLE device configuration manager
 * Manages BLE device list and per-device BLE enablement from JSON file
 * Supports both offline mode (local JSON) and online mode (sync from server)
 */
class BleDeviceConfigManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BleDeviceConfigManager"
        private const val CONFIG_FILE_NAME = "ble_devices_config.json"
    }
    
    private val configFile: File = File(context.filesDir, CONFIG_FILE_NAME)
    private val gson = Gson()
    private val mutex = Mutex()
    
    // In-memory cache
    private var bleDevices: MutableList<BleDevice> = mutableListOf()
    private var deviceBleConfigs: MutableMap<String, DeviceBleConfig> = mutableMapOf()
    
    /**
     * Configuration file structure
     */
    data class ConfigFileData(
        val bleDevices: List<BleDevice> = emptyList(),
        val deviceConfigs: List<DeviceBleConfig> = emptyList()
    )
    
    init {
        loadConfig()
    }
    
    /**
     * Load configuration from file
     */
    private fun loadConfig() {
        try {
            if (configFile.exists()) {
                val json = configFile.readText()
                val config = gson.fromJson(json, ConfigFileData::class.java)
                
                bleDevices = config.bleDevices.toMutableList()
                deviceBleConfigs = config.deviceConfigs.associateBy { it.deviceId }.toMutableMap()
                
                Log.i(TAG, "✅ Loaded BLE config: ${bleDevices.size} devices, ${deviceBleConfigs.size} device-specific configs")
            } else {
                Log.i(TAG, "📝 No existing config file, using empty configuration")
                // Create default config file (non-blocking)
                saveConfigSync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load BLE configuration", e)
            // Use empty configuration
            bleDevices = mutableListOf()
            deviceBleConfigs = mutableMapOf()
        }
    }
    
    /**
     * Save configuration to file (synchronous version for init)
     */
    private fun saveConfigSync() {
        try {
            val config = ConfigFileData(
                bleDevices = bleDevices.toList(),
                deviceConfigs = deviceBleConfigs.values.toList()
            )
            val json = gson.toJson(config)
            configFile.writeText(json)
            Log.d(TAG, "💾 Saved BLE configuration to file")
        } catch (e: IOException) {
            Log.e(TAG, "❌ Failed to save BLE configuration", e)
        }
    }
    
    /**
     * Save configuration to file (suspend version with mutex lock)
     */
    private suspend fun saveConfig() {
        mutex.withLock {
            saveConfigSync()
        }
    }
    
    /**
     * Get all BLE devices
     */
    fun getAllBleDevices(): List<BleDevice> {
        return bleDevices.toList()
    }
    
    /**
     * Get enabled BLE devices (globally enabled)
     */
    fun getEnabledBleDevices(): List<BleDevice> {
        return bleDevices.filter { it.enabled }
    }
    
    /**
     * Get BLE devices enabled for a specific Android device
     * Takes into account both global enabled status and device-specific config
     */
    fun getEnabledBleDevicesForDevice(deviceId: String): List<BleDevice> {
        val deviceConfig = deviceBleConfigs[deviceId]
        return bleDevices.filter { bleDevice ->
            // BLE device must be globally enabled
            if (!bleDevice.enabled) return@filter false
            
            // If device-specific config exists, use it
            if (deviceConfig != null) {
                deviceConfig.enabledBleDevices[bleDevice.id] ?: true  // Default to enabled if not specified
            } else {
                true  // Default to enabled if no device config
            }
        }
    }
    
    /**
     * Check if a specific BLE device is enabled for a specific Android device
     */
    fun isBleEnabledForDevice(deviceId: String, bleDeviceId: String): Boolean {
        val bleDevice = bleDevices.find { it.id == bleDeviceId } ?: return false
        
        // Must be globally enabled
        if (!bleDevice.enabled) return false
        
        // Check device-specific config
        val deviceConfig = deviceBleConfigs[deviceId]
        return if (deviceConfig != null) {
            deviceConfig.enabledBleDevices[bleDeviceId] ?: true
        } else {
            true
        }
    }
    
    /**
     * Update configuration from server (online mode)
     */
    suspend fun updateFromServer(serverConfig: ConfigFileData) {
        mutex.withLock {
            bleDevices = serverConfig.bleDevices.toMutableList()
            deviceBleConfigs = serverConfig.deviceConfigs.associateBy { it.deviceId }.toMutableMap()
            Log.i(TAG, "🔄 Updated BLE config from server: ${bleDevices.size} devices")
        }
        saveConfig()
    }
    
    /**
     * Update configuration from JSON string (for server updates)
     */
    suspend fun updateFromJson(json: String) {
        try {
            val config = gson.fromJson(json, ConfigFileData::class.java)
            updateFromServer(config)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse BLE configuration from JSON", e)
        }
    }
    
    /**
     * Set whether a BLE device is enabled for a specific Android device
     */
    suspend fun setBleEnabledForDevice(deviceId: String, bleDeviceId: String, enabled: Boolean) {
        mutex.withLock {
            val deviceConfig = deviceBleConfigs.getOrPut(deviceId) {
                DeviceBleConfig(deviceId, mutableMapOf())
            }
            
            val updatedMap = deviceConfig.enabledBleDevices.toMutableMap()
            updatedMap[bleDeviceId] = enabled
            deviceBleConfigs[deviceId] = deviceConfig.copy(enabledBleDevices = updatedMap)
            
            Log.d(TAG, "🔧 Set BLE $bleDeviceId for device $deviceId: enabled=$enabled")
        }
        saveConfig()
    }
    
    /**
     * Convert to GeofenceZone list for use with GeofenceManager
     */
    fun toGeofenceZones(deviceId: String): List<GeofenceZone> {
        return getEnabledBleDevicesForDevice(deviceId).map { bleDevice ->
            GeofenceZone(
                id = bleDevice.id,
                name = bleDevice.name,
                beaconUuid = bleDevice.uuid,
                beaconMajor = bleDevice.major,
                beaconMinor = bleDevice.minor,
                radiusMeters = bleDevice.radiusMeters,
                enabled = true
            )
        }
    }
    
    /**
     * Get device-specific BLE configuration (for reporting to server)
     */
    fun getDeviceConfig(deviceId: String): Map<String, Boolean> {
        val deviceConfig = deviceBleConfigs[deviceId]
        val result = mutableMapOf<String, Boolean>()
        
        bleDevices.forEach { bleDevice ->
            val enabled = if (deviceConfig != null) {
                deviceConfig.enabledBleDevices[bleDevice.id] ?: true
            } else {
                true
            }
            result[bleDevice.id] = enabled && bleDevice.enabled
        }
        
        return result
    }
}

