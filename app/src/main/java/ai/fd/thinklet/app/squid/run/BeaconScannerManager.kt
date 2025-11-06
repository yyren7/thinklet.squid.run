package ai.fd.thinklet.app.squid.run

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID

/**
 * iBeacon data class
 * Contains UUID, Major, Minor and RSSI information of iBeacon
 */
data class BeaconData(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val distance: Double,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Estimate distance (meters) based on RSSI
         * Uses standard iBeacon distance calculation formula
         */
        fun calculateDistance(rssi: Int, txPower: Int = -59): Double {
            if (rssi == 0) {
                return -1.0
            }
            val ratio = rssi * 1.0 / txPower
            return if (ratio < 1.0) {
                Math.pow(ratio, 10.0)
            } else {
                0.89976 * Math.pow(ratio, 7.7095) + 0.111
            }
        }
    }
}

/**
 * Beacon scan listener interface
 */
interface BeaconScanListener {
    fun onBeaconDiscovered(beacon: BeaconData)
    fun onBeaconLost(beacon: BeaconData)
    fun onScanError(errorCode: Int)
}

/**
 * Advanced distance filter (Kalman filter + median filter)
 * Used to smooth iBeacon distance measurements and reduce RSSI fluctuation effects
 */
private class AdvancedDistanceFilter(
    private val processNoise: Double = 0.05,       // Process noise
    private val measurementNoise: Double = 3.0,    // Measurement noise
    private val medianWindowSize: Int = 3          // Median window size
) {
    // Kalman filter state
    private var estimate = 0.0                     // Current estimate
    private var errorCovariance = 1.0              // Estimation error covariance
    private var initialized = false                // Whether initialized
    
    // Median filter window
    private val medianSamples = mutableListOf<Double>()
    
    /**
     * Filter processing
     */
    fun filter(rawDistance: Double): Double {
        // 1. Outlier detection
        if (rawDistance < 0.0 || rawDistance > 50.0) {
            return if (initialized) estimate else rawDistance
        }
        
        // 2. Kalman filter
        val kalmanResult = kalmanFilter(rawDistance)
        
        // 3. Median filter
        val medianResult = medianFilter(kalmanResult)
        
        return medianResult
    }
    
    /**
     * Kalman filter implementation
     */
    private fun kalmanFilter(measurement: Double): Double {
        if (!initialized) {
            estimate = measurement
            initialized = true
            return estimate
        }
        
        // Prediction step
        val predictedErrorCovariance = errorCovariance + processNoise
        
        // Update step (Kalman gain)
        val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)
        
        // Update estimate
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1 - kalmanGain) * predictedErrorCovariance
        
        return estimate
    }
    
    /**
     * Median filter
     */
    private fun medianFilter(value: Double): Double {
        medianSamples.add(value)
        
        // Maintain window size
        if (medianSamples.size > medianWindowSize) {
            medianSamples.removeAt(0)
        }
        
        // Return median
        val sorted = medianSamples.sorted()
        return sorted[sorted.size / 2]
    }
    
    fun getEstimate(): Double = estimate
}

/**
 * iBeacon scanner manager
 * Responsible for scanning surrounding iBeacon devices and parsing data
 */
class BeaconScannerManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BeaconScannerManager"
        private const val IBEACON_LAYOUT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"
        // Note: Scanning is continuous, not periodic
        // Uses SCAN_MODE_LOW_LATENCY mode to ensure fast BLE broadcast capture
        // Since BLE devices send signals every 2 seconds, continuous scanning can reliably capture them
        private const val BEACON_TIMEOUT_MS = 60000L // Beacon timeout: 60 seconds (avoid false "exit" due to brief pause)
        
        // iBeacon standard UUID (Apple defined)
        val IBEACON_UUID = UUID.fromString("0000FED8-0000-1000-8000-00805F9B34FB")
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private val listeners = mutableListOf<BeaconScanListener>()
    
    // UUID whitelist: only process beacons with UUIDs in this set
    // If empty, all UUIDs are processed (backward compatibility)
    private val uuidWhitelist = mutableSetOf<String>()
    
    // Store recently discovered beacons
    private val discoveredBeacons = mutableMapOf<String, BeaconData>()
    
    // Advanced distance filter: maintain independent filter for each Beacon
    private val distanceFilters = mutableMapOf<String, AdvancedDistanceFilter>()
    
    // Scan statistics
    private var scanStartTime = 0L
    private var scanCount = 0
    private var ibeaconCount = 0
    private var otherDeviceCount = 0
    
    // Retry management for SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
    private var registrationFailureCount = 0
    private val maxRegistrationRetries = 3
    private var lastRegistrationFailureTime = 0L
    
    // Scan callback
    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            scanCount++
            result?.let { scanResult ->
                // Record all scanned devices (for debugging)
                val deviceName = scanResult.device?.name ?: "Unknown"
                val deviceAddress = scanResult.device?.address ?: "Unknown"
                val rssi = scanResult.rssi
                
                // Try to parse as iBeacon
                val beaconData = parseBeacon(scanResult)
                if (beaconData != null) {
                    ibeaconCount++
                    // Record all discovered iBeacons (for debugging)
                    if (ibeaconCount <= 20) { // Record first 20 iBeacons
                        Log.d(TAG, "📶 iBeacon detected: UUID=${beaconData.uuid}, Major=${beaconData.major}, Minor=${beaconData.minor}, RSSI=${beaconData.rssi}dBm, Distance=${String.format("%.2f", beaconData.distance)}m")
                    }
                    handleBeaconDiscovered(beaconData)
                } else {
                    // Record non-iBeacon devices (first 10 only to avoid excessive logs)
                    if (otherDeviceCount < 10) {
                        otherDeviceCount++
                        val manufacturerData = scanResult.scanRecord?.getManufacturerSpecificData(0x004C)
                        Log.d(TAG, "📡 BLE device found (not iBeacon): name=$deviceName, addr=$deviceAddress, rssi=$rssi, manData=${manufacturerData != null}")
                    }
                }
            }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                parseBeacon(result)?.let { beaconData ->
                    handleBeaconDiscovered(beaconData)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                else -> "UNKNOWN($errorCode)"
            }
            Log.e(TAG, "❌ BLE scan failed: $errorMsg (code: $errorCode)")
            Log.e(TAG, "   Scan stats: total=$scanCount, iBeacons=$ibeaconCount, others=$otherDeviceCount")
            
            // Handle APPLICATION_REGISTRATION_FAILED specially
            if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                registrationFailureCount++
                lastRegistrationFailureTime = System.currentTimeMillis()
                
                Log.w(TAG, "⚠️ Application registration failed (attempt $registrationFailureCount/$maxRegistrationRetries), cleaning up and will retry...")
                
                // Force cleanup
                isScanning = false
                try {
                    bluetoothLeScanner?.stopScan(this)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error stopping scan during cleanup: ${e.message}")
                }
                bluetoothLeScanner = null
                
                // Retry with backoff if not exceeded max retries
                if (registrationFailureCount < maxRegistrationRetries) {
                    val retryDelay = 1000L * registrationFailureCount // 1s, 2s, 3s
                    Log.d(TAG, "🔄 Retrying scan after ${retryDelay}ms...")
                    handler.postDelayed({
                        startScanning()
                    }, retryDelay)
                } else {
                    Log.e(TAG, "❌ Max registration retries reached, giving up. Please restart the app or reset Bluetooth.")
                }
            }
            
            listeners.forEach { it.onScanError(errorCode) }
        }
    }
    
    /**
     * Add scan listener
     */
    fun addListener(listener: BeaconScanListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * Remove scan listener
     */
    fun removeListener(listener: BeaconScanListener) {
        listeners.remove(listener)
    }
    
    /**
     * Check if Bluetooth is available
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }
    
    /**
     * Start scanning iBeacon
     */
    @SuppressLint("MissingPermission")
    fun startScanning() {
        Log.d(TAG, "🔍 Attempting to start scanning...")
        
        if (!isBluetoothAvailable()) {
            Log.w(TAG, "⚠️ Bluetooth is not available or not enabled")
            Log.w(TAG, "   BluetoothAdapter: ${bluetoothAdapter != null}, Enabled: ${bluetoothAdapter?.isEnabled}")
            return
        }
        
        if (isScanning) {
            Log.d(TAG, "ℹ️ Already scanning")
            return
        }
        
        // Check if too many registration failures in short time
        val currentTime = System.currentTimeMillis()
        if (registrationFailureCount >= maxRegistrationRetries && 
            currentTime - lastRegistrationFailureTime < 30000) { // 30 seconds
            Log.w(TAG, "⚠️ Too many registration failures ($registrationFailureCount), waiting before retry...")
            // Schedule retry after delay
            handler.postDelayed({
                registrationFailureCount = 0
                startScanning()
            }, 10000) // 10 seconds
            return
        }
        
        // Force stop any existing scan before starting new one
        try {
            if (bluetoothLeScanner != null) {
                Log.d(TAG, "🧹 Cleaning up existing scanner before starting new scan")
                bluetoothLeScanner?.stopScan(scanCallback)
                bluetoothLeScanner = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error cleaning up existing scanner: ${e.message}")
        }
        
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "❌ Failed to get BluetoothLeScanner")
            return
        }
        
        Log.d(TAG, "✅ BluetoothLeScanner obtained successfully")
        
        // Android 8.1 compatibility: if filter has issues, use empty filter list
        val scanFilters = try {
            buildScanFilters()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to build scan filters, using empty filters", e)
            emptyList<ScanFilter>()
        }
        
        val scanSettings = buildScanSettings()
        
        Log.d(TAG, "📋 Scan configuration: filters=${scanFilters.size}, mode=${scanSettings.scanMode}")
        
        try {
            bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
            isScanning = true
            scanStartTime = System.currentTimeMillis()
            scanCount = 0
            Log.i(TAG, "✅ Started iBeacon scanning (with ${scanFilters.size} filters)")
            
            // Start periodic Beacon timeout check
            scheduleBeaconTimeoutCheck()
            
            // Add scan statistics scheduled task
            scheduleScanStatistics()
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: Missing Bluetooth or Location permission", e)
            isScanning = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start scanning", e)
            isScanning = false
        }
    }
    
    /**
     * Stop scanning iBeacon
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) {
            Log.d(TAG, "ℹ️ Not currently scanning")
            // Still try to cleanup in case of inconsistent state
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                bluetoothLeScanner = null
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during cleanup: ${e.message}")
            }
            return
        }
        
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            handler.removeCallbacksAndMessages(null)
            
            // Release scanner reference to allow GC
            bluetoothLeScanner = null
            
            // Output final statistics
            if (scanStartTime > 0) {
                val duration = (System.currentTimeMillis() - scanStartTime) / 1000
                Log.i(TAG, "🛑 Stopped iBeacon scanning")
                Log.i(TAG, "📊 Final stats: duration=${duration}s, total=$scanCount, iBeacons=$ibeaconCount, discovered=${discoveredBeacons.size}")
            } else {
                Log.i(TAG, "🛑 Stopped iBeacon scanning")
            }
            
            // Reset statistics and failure count
            scanStartTime = 0
            scanCount = 0
            ibeaconCount = 0
            otherDeviceCount = 0
            registrationFailureCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop scanning", e)
            // Ensure scanner is released even on error
            bluetoothLeScanner = null
            isScanning = false
        }
    }
    
    /**
     * Build scan filters
     * Android 8.1 compatibility: if filter build fails, return empty list
     */
    private fun buildScanFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        
        try {
            // Filter iBeacon broadcast packets
            // iBeacon uses Apple's company identifier: 0x004C
            // Note: Android 8.1 may handle empty byte arrays differently, so use filterless scanning
            // We will filter iBeacon devices in parseBeacon
            val filter = ScanFilter.Builder()
                .setManufacturerData(
                    0x004C, // Apple company identifier
                    null,   // No mask, match all 0x004C manufacturer data
                    null
                )
                .build()
            filters.add(filter)
            Log.d(TAG, "✅ Scan filter created for Apple manufacturer (0x004C)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to create scan filter, will scan all BLE devices", e)
            // Return empty list, scan all devices
        }
        
        return filters
    }
    
    /**
     * Periodically output scan statistics (for debugging)
     */
    private fun scheduleScanStatistics() {
        handler.postDelayed({
            if (isScanning) {
                val duration = (System.currentTimeMillis() - scanStartTime) / 1000
                Log.d(TAG, "📊 Scan statistics: duration=${duration}s, total=$scanCount, iBeacons=$ibeaconCount, others=$otherDeviceCount, discovered=${discoveredBeacons.size}")
                scheduleScanStatistics()
            }
        }, 10000) // Output every 10 seconds
    }
    
    /**
     * Build scan settings
     * Optimization strategy:
     * 1. SCAN_MODE_LOW_LATENCY: Lowest latency mode, scan window and interval are very short (~11.25ms)
     * 2. setReportDelay(0): Report scan results immediately, no delayed batch reporting
     * This ensures reliable capture when BLE devices send signals every 2 seconds
     */
    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Low latency mode, fast response (suitable for 2-second broadcast cycle)
            .setReportDelay(0) // Report immediately, no delay (ensure first-time signal capture)
            .build()
    }
    
    /**
     * Parse iBeacon data
     */
    private fun parseBeacon(scanResult: ScanResult): BeaconData? {
        val manufacturerData = scanResult.scanRecord?.getManufacturerSpecificData(0x004C)
        
        if (manufacturerData == null || manufacturerData.size < 23) {
            return null
        }
        
        // iBeacon format validation
        // First byte should be 0x02 (iBeacon identifier)
        // Second byte should be 0x15 (data length 21 bytes)
        if (manufacturerData[0].toInt() != 0x02 || manufacturerData[1].toInt() != 0x15) {
            return null
        }
        
        try {
            // Parse UUID (16 bytes, from index 2 to 17)
            val uuidBytes = manufacturerData.copyOfRange(2, 18)
            val uuid = bytesToUuid(uuidBytes)
            
            // Parse Major (2 bytes, from index 18 to 19)
            val major = ByteBuffer.wrap(manufacturerData.copyOfRange(18, 20)).short.toInt() and 0xFFFF
            
            // Parse Minor (2 bytes, from index 20 to 21)
            val minor = ByteBuffer.wrap(manufacturerData.copyOfRange(20, 22)).short.toInt() and 0xFFFF
            
            // Parse TxPower (1 byte, index 22)
            val txPower = manufacturerData[22].toInt()
            
            // Get RSSI
            val rssi = scanResult.rssi
            
            // Calculate distance
            val distance = BeaconData.calculateDistance(rssi, txPower)
            
            return BeaconData(
                uuid = uuid,
                major = major,
                minor = minor,
                rssi = rssi,
                distance = distance
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse beacon data", e)
            return null
        }
    }
    
    /**
     * Convert byte array to UUID string
     */
    private fun bytesToUuid(bytes: ByteArray): String {
        val buffer = ByteBuffer.wrap(bytes)
        val mostSigBits = buffer.long
        val leastSigBits = buffer.long
        val uuid = UUID(mostSigBits, leastSigBits)
        return uuid.toString().uppercase()
    }
    
    /**
     * Get or create distance filter for Beacon
     */
    private fun getDistanceFilter(beaconKey: String): AdvancedDistanceFilter {
        return distanceFilters.getOrPut(beaconKey) {
            // Create independent advanced filter for each Beacon
            // processNoise: 0.05 (assuming device is basically stationary)
            // measurementNoise: 3.0 (BLE RSSI fluctuations are large)
            // medianWindowSize: 3 (short-term median filter)
            AdvancedDistanceFilter(
                processNoise = 0.05,
                measurementNoise = 3.0,
                medianWindowSize = 3
            )
        }
    }
    
    /**
     * Handle discovered Beacon
     */
    private fun handleBeaconDiscovered(beaconData: BeaconData) {
        // Check UUID whitelist (if not empty)
        if (uuidWhitelist.isNotEmpty() && !uuidWhitelist.contains(beaconData.uuid)) {
            // This beacon's UUID is not in whitelist, skip processing
            return
        }
        
        val beaconKey = "${beaconData.uuid}-${beaconData.major}-${beaconData.minor}"
        val existingBeacon = discoveredBeacons[beaconKey]
        
        // Use advanced filter (Kalman + median filter)
        val filter = getDistanceFilter(beaconKey)
        val filteredDistance = filter.filter(beaconData.distance)
        
        // Create filtered Beacon data
        val filteredBeaconData = BeaconData(
            uuid = beaconData.uuid,
            major = beaconData.major,
            minor = beaconData.minor,
            rssi = beaconData.rssi,
            distance = filteredDistance,
            timestamp = beaconData.timestamp
        )
        
        if (existingBeacon == null) {
            // Newly discovered Beacon
            discoveredBeacons[beaconKey] = filteredBeaconData
            Log.i(TAG, "🔵 New beacon discovered: UUID=${beaconData.uuid}, Major=${beaconData.major}, Minor=${beaconData.minor}, RawDistance=${String.format("%.2f", beaconData.distance)}m, FilteredDistance=${String.format("%.2f", filteredDistance)}m, RSSI=${beaconData.rssi}dBm")
            listeners.forEach { it.onBeaconDiscovered(filteredBeaconData) }
        } else {
            // Update existing Beacon
            discoveredBeacons[beaconKey] = filteredBeaconData
            // Note: Do not notify listeners here to avoid ANR from excessive callbacks
            // GeofenceManager will get latest data from getDiscoveredBeacons() through periodic checks
            
            // Log every 10th update (avoid excessive logs)
            if (ibeaconCount % 10 == 0) {
                Log.v(TAG, "🔄 Beacon updated: UUID=${beaconData.uuid}, Major=${beaconData.major}, Minor=${beaconData.minor}, RawDistance=${String.format("%.2f", beaconData.distance)}m, FilteredDistance=${String.format("%.2f", filteredDistance)}m")
            }
        }
    }
    
    /**
     * Periodically check Beacon timeout
     */
    private fun scheduleBeaconTimeoutCheck() {
        handler.postDelayed({
            checkBeaconTimeout()
            if (isScanning) {
                scheduleBeaconTimeoutCheck()
            }
        }, 5000) // Check every 5 seconds
    }
    
    /**
     * Check if Beacon has timed out
     */
    private fun checkBeaconTimeout() {
        val currentTime = System.currentTimeMillis()
        val iterator = discoveredBeacons.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val beacon = entry.value
            
            if (currentTime - beacon.timestamp > BEACON_TIMEOUT_MS) {
                Log.d(TAG, "⚪ Beacon lost: UUID=${beacon.uuid}, Major=${beacon.major}, Minor=${beacon.minor}")
                listeners.forEach { it.onBeaconLost(beacon) }
                iterator.remove()
            }
        }
    }
    
    /**
     * Get all currently discovered Beacons
     */
    fun getDiscoveredBeacons(): List<BeaconData> {
        return discoveredBeacons.values.toList()
    }
    
    /**
     * Set UUID whitelist
     * Only beacons with UUIDs in this set will be processed
     * If empty set, all UUIDs will be processed (backward compatibility)
     */
    fun setUuidWhitelist(uuids: Set<String>) {
        uuidWhitelist.clear()
        uuidWhitelist.addAll(uuids)
        if (uuids.isNotEmpty()) {
            Log.i(TAG, "📋 Updated UUID whitelist: ${uuids.joinToString(", ")}")
        } else {
            Log.i(TAG, "📋 Cleared UUID whitelist (processing all UUIDs)")
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up BeaconScannerManager...")
        stopScanning()
        
        // Force release scanner
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error during final cleanup: ${e.message}")
        }
        bluetoothLeScanner = null
        
        listeners.clear()
        discoveredBeacons.clear()
        distanceFilters.clear()
        uuidWhitelist.clear()
        
        // Reset all counters
        registrationFailureCount = 0
        isScanning = false
        
        Log.i(TAG, "✅ BeaconScannerManager cleanup completed")
    }
}

