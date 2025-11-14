package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Geofence zone definition
 * @param id Geofence ID
 * @param name Geofence name
 * @param beaconUuid iBeacon UUID
 * @param beaconMajor iBeacon Major value (optional, null means match all Major)
 * @param beaconMinor iBeacon Minor value (optional, null means match all Minor)
 * @param radiusMeters Geofence radius (meters)
 * @param enabled Whether this geofence is enabled
 */
data class GeofenceZone(
    val id: String,
    val name: String,
    val beaconUuid: String,
    val beaconMajor: Int? = null,
    val beaconMinor: Int? = null,
    val radiusMeters: Double = 5.0,
    val enabled: Boolean = true
) {
    /**
     * Check if Beacon matches this geofence
     */
    fun matchesBeacon(beacon: BeaconData): Boolean {
        if (beacon.uuid != beaconUuid) return false
        if (beaconMajor != null && beacon.major != beaconMajor) return false
        if (beaconMinor != null && beacon.minor != beaconMinor) return false
        return true
    }
    
    /**
     * Check if Beacon is within geofence range
     */
    fun isBeaconInside(beacon: BeaconData): Boolean {
        return matchesBeacon(beacon) && beacon.distance <= radiusMeters
    }
}

/**
 * Geofence state
 */
enum class GeofenceState {
    INSIDE,  // Inside geofence
    OUTSIDE, // Outside geofence
    UNKNOWN  // Unknown state
}

/**
 * Geofence event type
 */
enum class GeofenceEventType {
    ENTER,  // Enter geofence
    EXIT,   // Exit geofence
    DWELL   // Dwell in geofence
}

/**
 * Geofence event
 */
data class GeofenceEvent(
    val type: GeofenceEventType,
    val zone: GeofenceZone,
    val beacon: BeaconData,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Geofence listener interface
 */
interface GeofenceEventListener {
    fun onGeofenceEnter(event: GeofenceEvent)
    fun onGeofenceExit(event: GeofenceEvent)
    fun onGeofenceDwell(event: GeofenceEvent)
}

/**
 * Electronic geofence manager
 * Implements electronic geofence functionality based on iBeacon
 */
class GeofenceManager(
    private val context: Context,
    private val beaconScanner: BeaconScannerManager
) {
    companion object {
        private const val TAG = "GeofenceManager"
        private const val DWELL_TIME_MS = 10000L // Dwell time: 10 seconds
        private const val HYSTERESIS_FACTOR = 1.2 // Hysteresis factor: exit distance = enter distance × 1.2, avoid frequent switching
        private const val TIMEOUT_INSIDE_MS = 60000L // Timeout when inside geofence: 60 seconds (longer to avoid false exits)
        private const val TIMEOUT_OUTSIDE_MS = 30000L // Timeout when outside geofence: 30 seconds
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Monitoring state flag
    private var isMonitoring = false
    
    // Geofence configuration
    private val geofenceZones = ConcurrentHashMap<String, GeofenceZone>()
    
    // Geofence state
    private val geofenceStates = ConcurrentHashMap<String, GeofenceState>()
    
    // Recent Beacon information (recent Beacon for each geofence)
    private val recentBeacons = ConcurrentHashMap<String, BeaconData>()
    
    // Timestamp of entering geofence
    private val enterTimestamps = ConcurrentHashMap<String, Long>()
    
    // Dwell notification flag
    private val dwellNotified = ConcurrentHashMap<String, Boolean>()
    
    // Unmatched Beacon counter (for log control)
    private val unmatchedBeaconCounts = ConcurrentHashMap<String, Int>()
    
    // Listener list
    private val listeners = mutableListOf<GeofenceEventListener>()
    
    // Current overall geofence state (whether inside any geofence)
    private val _isInsideAnyGeofence = MutableStateFlow(false)
    val isInsideAnyGeofence: StateFlow<Boolean> = _isInsideAnyGeofence.asStateFlow()
    
    // Current active geofence list
    private val _activeGeofences = MutableStateFlow<List<String>>(emptyList())
    val activeGeofences: StateFlow<List<String>> = _activeGeofences.asStateFlow()
    
    // Beacon scan listener
    private val beaconScanListener = object : BeaconScanListener {
        override fun onBeaconDiscovered(beacon: BeaconData) {
            handleBeaconDiscovered(beacon)
        }
        
        override fun onBeaconLost(beacon: BeaconData) {
            handleBeaconLost(beacon)
        }
        
        override fun onScanError(errorCode: Int) {
            Log.e(TAG, "❌ Beacon scan error: $errorCode")
        }
    }
    
    init {
        // Register Beacon scan listener
        beaconScanner.addListener(beaconScanListener)
        
        // Start periodic check task
        startPeriodicCheck()
    }
    
    /**
     * Add geofence zone
     */
    fun addGeofenceZone(zone: GeofenceZone) {
        // Validate geofence configuration
        if (zone.radiusMeters <= 0 || zone.radiusMeters > 100) {
            Log.w(TAG, "⚠️ Geofence radius abnormal: ${zone.radiusMeters}m (recommended range: 1-100m)")
        }
        
        geofenceZones[zone.id] = zone
        geofenceStates[zone.id] = GeofenceState.UNKNOWN
        val exitThreshold = zone.radiusMeters * HYSTERESIS_FACTOR
        Log.i(TAG, "✅ Added geofence: ${zone.name} (ID: ${zone.id}) | radius=${zone.radiusMeters}m | exit threshold=${String.format("%.2f", exitThreshold)}m | UUID=${zone.beaconUuid} | Major=${zone.beaconMajor} | Minor=${zone.beaconMinor}")
        
        // Update UUID whitelist in BeaconScannerManager
        updateUuidWhitelist()
    }
    
    /**
     * Clear all geofence zones
     */
    fun clearAllZones() {
        geofenceZones.clear()
        geofenceStates.clear()
        recentBeacons.clear()
        enterTimestamps.clear()
        dwellNotified.clear()
        updateUuidWhitelist()
        updateActiveGeofences()
        Log.i(TAG, "🗑️ Cleared all geofence zones")
    }
    
    /**
     * Update geofence zones from a list
     * This replaces all existing zones with the new list
     */
    fun updateGeofenceZones(zones: List<GeofenceZone>) {
        clearAllZones()
        zones.forEach { addGeofenceZone(it) }
        Log.i(TAG, "🔄 Updated geofence zones: ${zones.size} zones configured")
    }
    
    /**
     * Remove geofence zone
     */
    fun removeGeofenceZone(zoneId: String) {
        geofenceZones.remove(zoneId)
        geofenceStates.remove(zoneId)
        recentBeacons.remove(zoneId)
        enterTimestamps.remove(zoneId)
        dwellNotified.remove(zoneId)
        Log.i(TAG, "🗑️ Removed geofence zone: $zoneId")
        updateActiveGeofences()
        
        // Update UUID whitelist in BeaconScannerManager
        updateUuidWhitelist()
    }
    
    /**
     * Get all geofence zones
     */
    fun getAllGeofenceZones(): List<GeofenceZone> {
        return geofenceZones.values.toList()
    }
    
    /**
     * Get geofence state
     */
    fun getGeofenceState(zoneId: String): GeofenceState {
        return geofenceStates[zoneId] ?: GeofenceState.UNKNOWN
    }
    
    /**
     * Get all active geofence zones with their states
     * Returns a map of zone ID -> (zone name, state)
     */
    fun getAllGeofenceStates(): Map<String, Pair<String, GeofenceState>> {
        return geofenceZones.mapValues { (_, zone) ->
            Pair(zone.name, geofenceStates[zone.id] ?: GeofenceState.UNKNOWN)
        }
    }
    
    /**
     * Get current geofence zone name (if inside any), null if outside all
     */
    fun getCurrentGeofenceZone(): String? {
        geofenceZones.forEach { (zoneId, zone) ->
            if (geofenceStates[zoneId] == GeofenceState.INSIDE) {
                return zone.name
            }
        }
        return null
    }
    
    /**
     * Get distances to all monitored BLE devices
     * Returns a map of zone ID -> (zone name, distance in meters, state)
     */
    fun getBleDistances(): Map<String, Triple<String, Double?, GeofenceState>> {
        return geofenceZones.mapValues { (zoneId, zone) ->
            val beacon = recentBeacons[zoneId]
            val distance = beacon?.distance
            val state = geofenceStates[zoneId] ?: GeofenceState.UNKNOWN
            Triple(zone.name, distance, state)
        }
    }
    
    /**
     * Add event listener
     */
    fun addEventListener(listener: GeofenceEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * Remove event listener
     */
    fun removeEventListener(listener: GeofenceEventListener) {
        listeners.remove(listener)
    }
    
    /**
     * Start geofence monitoring
     */
    fun startMonitoring() {
        isMonitoring = true
        beaconScanner.startScanning()
        
        // Note: Do not reset recentBeacons timestamps here
        // The monitoring state check in checkGeofenceStates() will prevent false positives during pause
        
        Log.i(TAG, "🟢 Started geofence monitoring (zones: ${geofenceZones.size}, active: ${geofenceStates.count { it.value == GeofenceState.INSIDE }})")
    }
    
    /**
     * Stop geofence monitoring
     */
    fun stopMonitoring() {
        isMonitoring = false
        beaconScanner.stopScanning()
        Log.i(TAG, "🔴 Stopped geofence monitoring (preserving state for resume)")
    }
    
    /**
     * Handle discovered Beacon (called only on first discovery)
     */
    private fun handleBeaconDiscovered(beacon: BeaconData) {
        scope.launch {
            var matchedAnyZone = false
            
            geofenceZones.values.forEach { zone ->
                if (!zone.enabled) return@forEach
                
                if (zone.matchesBeacon(beacon)) {
                    matchedAnyZone = true
                    recentBeacons[zone.id] = beacon
                    
                    val currentState = geofenceStates[zone.id] ?: GeofenceState.UNKNOWN
                    
                    // Use hysteresis threshold: when inside geofence, need to exceed larger distance to consider exit
                    val exitThreshold = zone.radiusMeters * HYSTERESIS_FACTOR
                    val isInside = beacon.distance <= zone.radiusMeters
                    val isOutside = beacon.distance > exitThreshold
                    
                    // Changed to Log.i to ensure log visibility
                    Log.i(TAG, "📍 Geofence match: '${zone.name}' | distance=${String.format("%.2f", beacon.distance)}m | radius=${zone.radiusMeters}m | exit threshold=${String.format("%.2f", exitThreshold)}m | current state=$currentState | enter check=$isInside | exit check=$isOutside")
                    
                    when {
                        isInside && (currentState == GeofenceState.OUTSIDE || currentState == GeofenceState.UNKNOWN) -> {
                            // Enter geofence
                            handleEnterGeofence(zone, beacon)
                        }
                        isInside && currentState == GeofenceState.INSIDE -> {
                            // Dwell in geofence
                            handleDwellInGeofence(zone, beacon)
                        }
                        isOutside && currentState == GeofenceState.INSIDE -> {
                            // Exit geofence (distance exceeds hysteresis threshold)
                            Log.w(TAG, "⚠️ Distance exit condition triggered: distance=${String.format("%.2f", beacon.distance)}m > threshold=${String.format("%.2f", exitThreshold)}m")
                            handleExitGeofence(zone, beacon, "distance_threshold")
                        }
                        else -> {
                            // When between radius and hysteresis threshold, maintain current state (avoid frequent switching)
                            Log.v(TAG, "🔹 Maintain current state: '${zone.name}' | distance=${String.format("%.2f", beacon.distance)}m between radius(${zone.radiusMeters}m) and threshold(${String.format("%.2f", exitThreshold)}m)")
                        }
                    }
                }
            }
            
            // If Beacon doesn't match any geofence, log (first 10 different UUIDs only to avoid excessive logs)
            if (!matchedAnyZone && geofenceZones.isNotEmpty()) {
                val firstZone = geofenceZones.values.first()
                val uuidMatch = beacon.uuid == firstZone.beaconUuid
                
                // Only log UUID mismatch cases (because UUID is the main matching condition)
                if (!uuidMatch) {
                    val count = unmatchedBeaconCounts.getOrDefault(beacon.uuid, 0)
                    if (count < 3) { // Each UUID logged at most 3 times
                        unmatchedBeaconCounts[beacon.uuid] = count + 1
                        Log.v(TAG, "ℹ️ Beacon not matched: UUID=${beacon.uuid} (expected ${firstZone.beaconUuid}), Major=${beacon.major}, Minor=${beacon.minor}")
                    }
                }
            }
        }
    }
    
    /**
     * Handle Beacon data update (get latest data from BeaconScannerManager periodically)
     * This is necessary because BeaconScannerManager only calls onBeaconDiscovered for NEW beacons,
     * not for updates to existing beacons (to avoid excessive listener callbacks)
     * This method syncs the latest beacon data including updated timestamps and distances
     */
    private fun updateBeaconsFromScanner() {
        val latestBeacons = beaconScanner.getDiscoveredBeacons()
        
        geofenceZones.values.forEach { zone ->
            if (!zone.enabled) return@forEach
            
            // Find latest beacon matching this geofence
            val matchingBeacon = latestBeacons.find { zone.matchesBeacon(it) }
            
            if (matchingBeacon != null) {
                // Update data in recentBeacons (including timestamp)
                val previousBeacon = recentBeacons[zone.id]
                recentBeacons[zone.id] = matchingBeacon
                
                val currentState = geofenceStates[zone.id] ?: GeofenceState.UNKNOWN
                val exitThreshold = zone.radiusMeters * HYSTERESIS_FACTOR
                val isInside = matchingBeacon.distance <= zone.radiusMeters
                val isOutside = matchingBeacon.distance > exitThreshold
                
                // Only output log on state change
                if (previousBeacon == null || 
                    (isInside && currentState != GeofenceState.INSIDE) ||
                    (isOutside && currentState == GeofenceState.INSIDE)) {
                    Log.d(TAG, "🔄 Updated geofence data: '${zone.name}' | distance=${String.format("%.2f", matchingBeacon.distance)}m | state=$currentState")
                }
                
                when {
                    isInside && (currentState == GeofenceState.OUTSIDE || currentState == GeofenceState.UNKNOWN) -> {
                        handleEnterGeofence(zone, matchingBeacon)
                    }
                    isInside && currentState == GeofenceState.INSIDE -> {
                        handleDwellInGeofence(zone, matchingBeacon)
                    }
                    isOutside && currentState == GeofenceState.INSIDE -> {
                        Log.w(TAG, "⚠️ Distance exit condition triggered: distance=${String.format("%.2f", matchingBeacon.distance)}m > threshold=${String.format("%.2f", exitThreshold)}m")
                        handleExitGeofence(zone, matchingBeacon, "distance_threshold")
                    }
                }
            }
        }
    }
    
    /**
     * Handle Beacon loss
     */
    private fun handleBeaconLost(beacon: BeaconData) {
        scope.launch {
            geofenceZones.values.forEach { zone ->
                if (zone.matchesBeacon(beacon)) {
                    val currentState = geofenceStates[zone.id]
                    if (currentState == GeofenceState.INSIDE) {
                        // Beacon signal lost, consider exited geofence
                        Log.w(TAG, "⚠️ Beacon signal lost: ${zone.name} | UUID=${beacon.uuid} | last distance=${String.format("%.2f", beacon.distance)}m")
                        handleExitGeofence(zone, beacon, "beacon_lost")
                    }
                    recentBeacons.remove(zone.id)
                }
            }
        }
    }
    
    /**
     * Handle entering geofence
     */
    private fun handleEnterGeofence(zone: GeofenceZone, beacon: BeaconData) {
        geofenceStates[zone.id] = GeofenceState.INSIDE
        enterTimestamps[zone.id] = System.currentTimeMillis()
        dwellNotified[zone.id] = false
        
        val event = GeofenceEvent(
            type = GeofenceEventType.ENTER,
            zone = zone,
            beacon = beacon
        )
        
        Log.i(TAG, "🟢 Entered geofence: ${zone.name} | distance=${String.format("%.2f", beacon.distance)}m | radius=${zone.radiusMeters}m | RSSI=${beacon.rssi}dBm | filtered distance=${String.format("%.2f", beacon.distance)}m")
        listeners.forEach { it.onGeofenceEnter(event) }
        
        updateActiveGeofences()
    }
    
    /**
     * Handle exiting geofence
     */
    private fun handleExitGeofence(zone: GeofenceZone, beacon: BeaconData, reason: String = "distance") {
        geofenceStates[zone.id] = GeofenceState.OUTSIDE
        enterTimestamps.remove(zone.id)
        dwellNotified.remove(zone.id)
        
        val event = GeofenceEvent(
            type = GeofenceEventType.EXIT,
            zone = zone,
            beacon = beacon
        )
        
        val exitThreshold = zone.radiusMeters * HYSTERESIS_FACTOR
        val beaconAge = System.currentTimeMillis() - beacon.timestamp
        Log.i(TAG, "🔴 Exited geofence: ${zone.name} | reason=$reason | distance=${String.format("%.2f", beacon.distance)}m | exit threshold=${String.format("%.2f", exitThreshold)}m | beacon age=${beaconAge}ms | RSSI=${beacon.rssi}dBm")
        listeners.forEach { it.onGeofenceExit(event) }
        
        updateActiveGeofences()
    }
    
    /**
     * Handle dwelling in geofence
     */
    private fun handleDwellInGeofence(zone: GeofenceZone, beacon: BeaconData) {
        val enterTime = enterTimestamps[zone.id] ?: return
        val currentTime = System.currentTimeMillis()
        val dwellTime = currentTime - enterTime
        
        // If dwell time exceeds threshold and not yet notified, trigger dwell event
        if (dwellTime >= DWELL_TIME_MS && dwellNotified[zone.id] != true) {
            dwellNotified[zone.id] = true
            
            val event = GeofenceEvent(
                type = GeofenceEventType.DWELL,
                zone = zone,
                beacon = beacon
            )
            
            Log.i(TAG, "⏱️ Dwelling in geofence: ${zone.name}, Duration: ${dwellTime / 1000}s")
            listeners.forEach { it.onGeofenceDwell(event) }
        }
    }
    
    /**
     * Update UUID whitelist in BeaconScannerManager based on current geofence zones
     */
    private fun updateUuidWhitelist() {
        val uuids = geofenceZones.values
            .filter { it.enabled }
            .map { it.beaconUuid }
            .toSet()
        
        beaconScanner.setUuidWhitelist(uuids)
    }
    
    /**
     * Update active geofence list
     */
    private fun updateActiveGeofences() {
        val activeZones = geofenceStates
            .filter { it.value == GeofenceState.INSIDE }
            .keys
            .toList()
        
        _activeGeofences.value = activeZones
        _isInsideAnyGeofence.value = activeZones.isNotEmpty()
    }
    
    /**
     * Start periodic check task
     * Periodically check geofence state and handle possible timeout situations
     * Note: updateBeaconsFromScanner() is needed because BeaconScannerManager only notifies
     * listeners on first discovery, not on every update (to avoid excessive callbacks)
     */
    private fun startPeriodicCheck() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000) // Check every 3 seconds
                if (isMonitoring) {
                    // Update beacon data from scanner (needed to refresh timestamps and distances)
                    updateBeaconsFromScanner()
                }
                checkGeofenceStates()
            }
        }
    }
    
    /**
     * Check geofence state
     */
    private fun checkGeofenceStates() {
        // If monitoring is stopped, skip check (avoid using expired data during pause to trigger false positives)
        if (!isMonitoring) {
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        geofenceZones.values.forEach { zone ->
            val recentBeacon = recentBeacons[zone.id]
            val currentState = geofenceStates[zone.id]
            
            // If recent Beacon data has expired and currently inside geofence, check if should exit
            if (recentBeacon != null && 
                currentState == GeofenceState.INSIDE) {
                val beaconAge = currentTime - recentBeacon.timestamp
                
                // Use adaptive timeout based on last known distance:
                // - If last distance was inside geofence, use longer timeout (60s) to avoid false exits
                // - If last distance was outside geofence, use shorter timeout (30s)
                val wasInside = recentBeacon.distance <= zone.radiusMeters
                val timeoutMs = if (wasInside) TIMEOUT_INSIDE_MS else TIMEOUT_OUTSIDE_MS
                
                if (beaconAge > timeoutMs) {
                    Log.w(TAG, "⚠️ Beacon data timeout: ${zone.name} | age=${beaconAge}ms (>${timeoutMs}ms) | last distance=${String.format("%.2f", recentBeacon.distance)}m | wasInside=$wasInside")
                    handleExitGeofence(zone, recentBeacon, "timeout_${timeoutMs / 1000}s")
                }
            }
        }
    }
    
    /**
     * Get summary of current state of all geofences
     */
    fun getGeofenceSummary(): String {
        val sb = StringBuilder()
        sb.append("=== Geofence Summary ===\n")
        sb.append("Active Geofences: ${activeGeofences.value.size}\n")
        sb.append("Inside Any Geofence: ${isInsideAnyGeofence.value}\n\n")
        
        geofenceZones.values.forEach { zone ->
            val state = geofenceStates[zone.id] ?: GeofenceState.UNKNOWN
            val beacon = recentBeacons[zone.id]
            
            sb.append("Zone: ${zone.name}\n")
            sb.append("  State: $state\n")
            if (beacon != null) {
                sb.append("  Distance: ${String.format("%.2f", beacon.distance)}m\n")
                sb.append("  RSSI: ${beacon.rssi}dBm\n")
            }
            sb.append("\n")
        }
        
        return sb.toString()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopMonitoring()
        beaconScanner.removeListener(beaconScanListener)
        listeners.clear()
        geofenceZones.clear()
        geofenceStates.clear()
        recentBeacons.clear()
        enterTimestamps.clear()
        dwellNotified.clear()
        Log.i(TAG, "🧹 GeofenceManager cleaned up")
    }
}

