package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * Storage capacity information
 */
data class StorageCapacity(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long
) {
    val totalGB: Double get() = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val availableGB: Double get() = availableBytes / (1024.0 * 1024.0 * 1024.0)
    val usedGB: Double get() = usedBytes / (1024.0 * 1024.0 * 1024.0)
    val usagePercent: Double get() = if (totalBytes > 0) (usedBytes * 100.0 / totalBytes) else 0.0
}

/**
 * Callback interface for storage capacity changes
 */
interface StorageCapacityListener {
    /**
     * Called when storage capacity is updated
     * @param internalCapacity Internal storage capacity, null if unavailable
     * @param sdCardCapacity SD card capacity, null if SD card is not available
     */
    fun onStorageCapacityUpdated(internalCapacity: StorageCapacity?, sdCardCapacity: StorageCapacity?)
}

/**
 * Storage Manager
 * Manages recording file storage location (internal storage vs SD card).
 * Supports switching between storage locations and handling files from both locations.
 * Monitors storage capacity and file list changes.
 */
class StorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "StorageManager"
        private const val PREF_NAME = "StorageManager"
        private const val KEY_STORAGE_TYPE = "storage_type" // "internal" or "sd_card"
        private const val FOLDER_NAME = "SquidRun"
        
        // Storage type constants
        const val STORAGE_TYPE_INTERNAL = "internal"
        const val STORAGE_TYPE_SD_CARD = "sd_card"
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    private var capacityListener: StorageCapacityListener? = null
    private var internalFileObserver: FileObserver? = null
    private var sdCardFileObserver: FileObserver? = null
    
    /**
     * Get current storage type preference.
     * @return "internal" or "sd_card"
     */
    fun getCurrentStorageType(): String {
        return sharedPreferences.getString(KEY_STORAGE_TYPE, STORAGE_TYPE_INTERNAL) 
            ?: STORAGE_TYPE_INTERNAL
    }
    
    /**
     * Set storage type preference.
     * @param storageType "internal" or "sd_card"
     * @param restartMonitoring If true, restart file monitoring after storage type change to ensure correct path is monitored
     */
    fun setStorageType(storageType: String, restartMonitoring: Boolean = false) {
        if (storageType != STORAGE_TYPE_INTERNAL && storageType != STORAGE_TYPE_SD_CARD) {
            Log.w(TAG, "Invalid storage type: $storageType, using internal")
            sharedPreferences.edit().putString(KEY_STORAGE_TYPE, STORAGE_TYPE_INTERNAL).apply()
            return
        }
        val oldStorageType = getCurrentStorageType()
        sharedPreferences.edit().putString(KEY_STORAGE_TYPE, storageType).apply()
        Log.i(TAG, "Storage type changed from $oldStorageType to: $storageType")
        
        // Restart file monitoring if requested to ensure correct path is monitored
        if (restartMonitoring) {
            Log.i(TAG, "🔄 Restarting file monitoring after storage type change")
            stopFileListMonitoring()
            startFileListMonitoring()
        }
    }
    
    /**
     * Get the primary recording folder based on current storage preference.
     * This is where new recordings will be saved.
     * @return File object for the recording folder
     */
    fun getRecordingFolder(): File {
        val baseDir = when (getCurrentStorageType()) {
            STORAGE_TYPE_SD_CARD -> {
                // Try to get SD card external files directory
                getSdCardExternalFilesDir() ?: getInternalExternalFilesDir()
            }
            else -> {
                // Use internal storage (default)
                getInternalExternalFilesDir()
            }
        }
        
        return File(baseDir, FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Get all recording folders (both internal and SD card).
     * Used when scanning for files from both locations.
     * @return List of File objects for all recording folders
     */
    fun getAllRecordingFolders(): List<File> {
        val folders = mutableListOf<File>()
        
        // Always include internal storage
        val internalFolder = File(getInternalExternalFilesDir(), FOLDER_NAME)
        if (internalFolder.exists() || internalFolder.mkdirs()) {
            folders.add(internalFolder)
        }
        
        // Include SD card if available
        val sdCardDir = getSdCardExternalFilesDir()
        if (sdCardDir != null) {
            val sdCardFolder = File(sdCardDir, FOLDER_NAME)
            if (sdCardFolder.exists() || sdCardFolder.mkdirs()) {
                folders.add(sdCardFolder)
            }
        }
        
        return folders
    }
    
    /**
     * Get internal storage external files directory.
     * @return File object for internal external files directory
     */
    private fun getInternalExternalFilesDir(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) 
            ?: throw IllegalStateException("Cannot access internal external files directory")
    }
    
    /**
     * Get SD card external files directory.
     * Returns null if SD card is not available.
     * @return File object for SD card external files directory, or null if not available
     */
    private fun getSdCardExternalFilesDir(): File? {
        return try {
            // getExternalFilesDirs() returns an array:
            // [0] = internal storage
            // [1+] = removable storage (SD cards, USB drives, etc.)
            val externalDirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            
            if (externalDirs.size > 1) {
                // Check if the second directory is mounted and writable
                val sdCardDir = externalDirs[1]
                if (sdCardDir != null && Environment.getExternalStorageState(sdCardDir) == Environment.MEDIA_MOUNTED) {
                    Log.i(TAG, "SD card found: ${sdCardDir.absolutePath}")
                    sdCardDir
                } else {
                    Log.w(TAG, "SD card directory exists but is not mounted or writable")
                    null
                }
            } else {
                Log.d(TAG, "No SD card found (only internal storage available)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SD card directory", e)
            null
        }
    }
    
    /**
     * Check if SD card is available.
     * @return true if SD card is available and writable
     */
    fun isSdCardAvailable(): Boolean {
        return getSdCardExternalFilesDir() != null
    }
    
    /**
     * Get current recording folder path as string.
     * @return Absolute path of the current recording folder
     */
    fun getRecordingFolderPath(): String {
        return getRecordingFolder().absolutePath
    }
    
    /**
     * Get storage capacity for internal storage.
     * @return StorageCapacity object, or null if unavailable
     */
    fun getInternalStorageCapacity(): StorageCapacity? {
        return try {
            val internalDir = getInternalExternalFilesDir()
            val stat = android.os.StatFs(internalDir.path)
            
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - availableBytes
            
            StorageCapacity(totalBytes, availableBytes, usedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get internal storage capacity", e)
            null
        }
    }
    
    /**
     * Get storage capacity for SD card.
     * @return StorageCapacity object, or null if SD card is not available
     */
    fun getSdCardStorageCapacity(): StorageCapacity? {
        val sdCardDir = getSdCardExternalFilesDir() ?: return null
        return try {
            val stat = android.os.StatFs(sdCardDir.path)
            
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - availableBytes
            
            StorageCapacity(totalBytes, availableBytes, usedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SD card storage capacity", e)
            null
        }
    }
    
    /**
     * Result of storage capacity check for recording
     */
    sealed class RecordingStorageCheckResult {
        /**
         * Recording can proceed with current storage path
         */
        data class Success(val storageType: String) : RecordingStorageCheckResult()
        
        /**
         * Recording can proceed but storage path was switched
         */
        data class Switched(val oldStorageType: String, val newStorageType: String) : RecordingStorageCheckResult()
        
        /**
         * Recording cannot proceed due to insufficient storage
         */
        data class InsufficientStorage(val internalAvailableGB: Double, val sdCardAvailableGB: Double?) : RecordingStorageCheckResult()
        
        /**
         * Storage check failed due to error
         */
        data class Error(val message: String) : RecordingStorageCheckResult()
    }
    
    /**
     * Check storage capacity and select appropriate storage path for recording.
     * This method implements the following logic:
     * 1. Check current recording path's remaining capacity
     * 2. If current path has > 1GB, use current path
     * 3. If current path has < 1GB, check alternate path
     * 4. If alternate path has > 1GB, switch to alternate path
     * 5. If both paths have < 1GB, return InsufficientStorage
     * 
     * @return RecordingStorageCheckResult indicating the result and any necessary actions
     */
    fun checkAndSelectStorageForRecording(): RecordingStorageCheckResult {
        Log.i(TAG, "🔍 Checking storage capacity for recording")
        
        val currentStorageType = getCurrentStorageType()
        val minRequiredGB = 1.0
        val minRequiredBytes = (minRequiredGB * 1024 * 1024 * 1024).toLong()
        
        // Get capacities for both storage locations
        val internalCapacity = getInternalStorageCapacity()
        val sdCardCapacity = getSdCardStorageCapacity()
        
        // Log current capacities
        if (internalCapacity != null) {
            Log.i(TAG, "📊 Internal storage: ${String.format("%.2f", internalCapacity.availableGB)} GB available")
        } else {
            Log.w(TAG, "⚠️ Failed to get internal storage capacity")
        }
        if (sdCardCapacity != null) {
            Log.i(TAG, "📊 SD card: ${String.format("%.2f", sdCardCapacity.availableGB)} GB available")
        } else {
            Log.d(TAG, "ℹ️ SD card not available")
        }
        
        // Check current storage path capacity
        // Note: If currentStorageType is SD_CARD but SD card is not available,
        // getRecordingFolder() will fallback to internal storage, so we should check internal
        val currentCapacity = when (currentStorageType) {
            STORAGE_TYPE_SD_CARD -> {
                // If SD card is not available, fallback to internal (matching getRecordingFolder behavior)
                sdCardCapacity ?: internalCapacity
            }
            else -> internalCapacity
        }
        
        // Determine actual storage type being used (may differ from preference if SD card unavailable)
        val actualCurrentStorageType = when {
            currentStorageType == STORAGE_TYPE_SD_CARD && sdCardCapacity != null -> STORAGE_TYPE_SD_CARD
            currentStorageType == STORAGE_TYPE_SD_CARD && sdCardCapacity == null -> STORAGE_TYPE_INTERNAL
            else -> STORAGE_TYPE_INTERNAL
        }
        
        // If current path has sufficient capacity, use it
        if (currentCapacity != null && currentCapacity.availableBytes >= minRequiredBytes) {
            // If we're actually using a different storage type than configured, update it
            if (actualCurrentStorageType != currentStorageType) {
                Log.i(TAG, "📝 Updating storage type from ${currentStorageType} to ${actualCurrentStorageType} (SD card unavailable)")
                setStorageType(actualCurrentStorageType, restartMonitoring = true)
            }
            Log.i(TAG, "✅ Current storage path (${actualCurrentStorageType}) has sufficient capacity: ${String.format("%.2f", currentCapacity.availableGB)} GB")
            return RecordingStorageCheckResult.Success(actualCurrentStorageType)
        }
        
        // Current path doesn't have enough space, check alternate path
        val alternateStorageType = when (actualCurrentStorageType) {
            STORAGE_TYPE_SD_CARD -> STORAGE_TYPE_INTERNAL
            else -> {
                // Only try SD card if it's actually available
                if (sdCardCapacity != null) STORAGE_TYPE_SD_CARD else null
            }
        }
        
        val alternateCapacity = when (alternateStorageType) {
            STORAGE_TYPE_SD_CARD -> sdCardCapacity
            STORAGE_TYPE_INTERNAL -> internalCapacity
            else -> null
        }
        
        // If alternate path has sufficient capacity, switch to it
        if (alternateCapacity != null && alternateStorageType != null && alternateCapacity.availableBytes >= minRequiredBytes) {
            Log.i(TAG, "🔄 Switching storage from ${actualCurrentStorageType} to ${alternateStorageType} (${String.format("%.2f", alternateCapacity.availableGB)} GB available)")
            setStorageType(alternateStorageType, restartMonitoring = true)
            return RecordingStorageCheckResult.Switched(actualCurrentStorageType, alternateStorageType)
        }
        
        // Check if we can't get capacity for internal storage (shouldn't happen, but handle it)
        if (internalCapacity == null) {
            val errorMsg = "Failed to check internal storage capacity"
            Log.e(TAG, "❌ $errorMsg")
            return RecordingStorageCheckResult.Error(errorMsg)
        }
        
        // Both paths don't have enough space
        val internalAvailableGB = internalCapacity.availableGB
        val sdCardAvailableGB = sdCardCapacity?.availableGB
        
        Log.w(TAG, "❌ Insufficient storage on both paths - Internal: ${String.format("%.2f", internalAvailableGB)} GB, SD: ${sdCardAvailableGB?.let { String.format("%.2f", it) } ?: "N/A"} GB")
        return RecordingStorageCheckResult.InsufficientStorage(internalAvailableGB, sdCardAvailableGB)
    }
    
    /**
     * Check if current recording path has insufficient capacity (< 1GB).
     * This method only checks the current path, without switching.
     * @return true if current path has less than 1GB available, false otherwise
     */
    fun isCurrentRecordingPathInsufficient(): Boolean {
        val currentStorageType = getCurrentStorageType()
        val minRequiredBytes = (1.0 * 1024 * 1024 * 1024).toLong()
        
        val currentCapacity = when (currentStorageType) {
            STORAGE_TYPE_SD_CARD -> {
                // If SD card is not available, check internal (matching getRecordingFolder behavior)
                getSdCardStorageCapacity() ?: getInternalStorageCapacity()
            }
            else -> getInternalStorageCapacity()
        }
        
        return if (currentCapacity != null) {
            val insufficient = currentCapacity.availableBytes < minRequiredBytes
            if (insufficient) {
                Log.w(TAG, "⚠️ Current recording path (${currentStorageType}) has insufficient capacity: ${String.format("%.2f", currentCapacity.availableGB)} GB available")
            }
            insufficient
        } else {
            // If we can't get capacity, assume it's insufficient to be safe
            Log.w(TAG, "⚠️ Cannot check capacity for current recording path (${currentStorageType}), assuming insufficient")
            true
        }
    }
    
    /**
     * Check and update storage capacity, then notify listener.
     * This method checks both internal storage and SD card capacity.
     * @param triggerSource Source that triggered this check (for logging purposes)
     */
    fun checkAndNotifyCapacity(triggerSource: String = "unknown") {
        Log.i(TAG, "🔍 Checking storage capacity [triggered by: $triggerSource]")
        
        val internalCapacity = getInternalStorageCapacity()
        val sdCardCapacity = getSdCardStorageCapacity()
        
        if (internalCapacity != null) {
            Log.i(TAG, "📊 Internal storage: ${String.format("%.2f", internalCapacity.availableGB)} GB available / ${String.format("%.2f", internalCapacity.totalGB)} GB total (${String.format("%.1f", internalCapacity.usagePercent)}% used) [source: $triggerSource]")
        } else {
            Log.w(TAG, "⚠️ Failed to get internal storage capacity [source: $triggerSource]")
        }
        if (sdCardCapacity != null) {
            Log.i(TAG, "📊 SD card: ${String.format("%.2f", sdCardCapacity.availableGB)} GB available / ${String.format("%.2f", sdCardCapacity.totalGB)} GB total (${String.format("%.1f", sdCardCapacity.usagePercent)}% used) [source: $triggerSource]")
        } else {
            Log.d(TAG, "ℹ️ SD card not available or failed to get capacity [source: $triggerSource]")
        }
        
        capacityListener?.onStorageCapacityUpdated(internalCapacity, sdCardCapacity)
    }
    
    /**
     * Set storage capacity listener.
     * @param listener The listener to receive capacity updates
     */
    fun setStorageCapacityListener(listener: StorageCapacityListener?) {
        capacityListener = listener
    }
    
    /**
     * Start monitoring file list changes in recording folders.
     * When files are added, deleted, or modified, capacity will be checked.
     * Monitors both internal storage and SD card folders to ensure all recording locations are covered.
     */
    fun startFileListMonitoring() {
        stopFileListMonitoring()
        Log.i(TAG, "🔄 Starting file list monitoring for both storage locations...")
        
        // Monitor internal storage folder
        val internalFolder = File(getInternalExternalFilesDir(), FOLDER_NAME)
        if (internalFolder.mkdirs() || internalFolder.exists()) {
            val folderPath = internalFolder.absolutePath
            internalFileObserver = object : FileObserver(folderPath, CREATE or DELETE or CLOSE_WRITE or MOVED_TO or MOVED_FROM) {
                override fun onEvent(event: Int, path: String?) {
                    // Log all events to help debug path issues
                    val eventName = when (event) {
                        CREATE -> "CREATE"
                        DELETE -> "DELETE"
                        CLOSE_WRITE -> "CLOSE_WRITE"
                        MOVED_TO -> "MOVED_TO"
                        MOVED_FROM -> "MOVED_FROM"
                        else -> "UNKNOWN($event)"
                    }
                    
                    if (path != null && path.endsWith(".mp4")) {
                        Log.i(TAG, "📁 Internal storage file list changed: $path (event: $eventName) in folder: $folderPath")
                        checkAndNotifyCapacity("FileObserver[internal:$eventName:$path]")
                    } else if (path != null) {
                        // Log non-mp4 events at debug level to help identify path issues
                        Log.d(TAG, "📁 Internal storage file event: path=$path, event=$eventName in folder: $folderPath")
                    } else {
                        // Log null path events - sometimes DELETE events have null path
                        if (event == DELETE) {
                            Log.i(TAG, "📁 Internal storage DELETE event (path=null) in folder: $folderPath - triggering capacity check")
                            checkAndNotifyCapacity("FileObserver[internal:DELETE:null]")
                        } else {
                            Log.d(TAG, "📁 Internal storage file event: path=null, event=$eventName in folder: $folderPath")
                        }
                    }
                }
            }
            internalFileObserver?.startWatching()
            Log.i(TAG, "✅ Started monitoring internal storage file list at: $folderPath")
        } else {
            Log.w(TAG, "⚠️ Cannot create or access internal storage folder: ${internalFolder.absolutePath}")
        }
        
        // Monitor SD card folder - use the same method as getRecordingFolder() to ensure path consistency
        val sdCardDir = getSdCardExternalFilesDir()
        if (sdCardDir != null) {
            val sdCardFolder = File(sdCardDir, FOLDER_NAME)
            if (sdCardFolder.mkdirs() || sdCardFolder.exists()) {
                val folderPath = sdCardFolder.absolutePath
                sdCardFileObserver = object : FileObserver(folderPath, CREATE or DELETE or CLOSE_WRITE or MOVED_TO or MOVED_FROM) {
                    override fun onEvent(event: Int, path: String?) {
                        // Log all events to help debug path issues
                        val eventName = when (event) {
                            CREATE -> "CREATE"
                            DELETE -> "DELETE"
                            CLOSE_WRITE -> "CLOSE_WRITE"
                            MOVED_TO -> "MOVED_TO"
                            MOVED_FROM -> "MOVED_FROM"
                            else -> "UNKNOWN($event)"
                        }
                        
                        if (path != null && path.endsWith(".mp4")) {
                            Log.i(TAG, "📁 SD card file list changed: $path (event: $eventName) in folder: $folderPath")
                            checkAndNotifyCapacity("FileObserver[sdCard:$eventName:$path]")
                        } else if (path != null) {
                            // Log non-mp4 events at debug level to help identify path issues
                            Log.d(TAG, "📁 SD card file event: path=$path, event=$eventName in folder: $folderPath")
                        } else {
                            // Log null path events - sometimes DELETE events have null path
                            if (event == DELETE) {
                                Log.i(TAG, "📁 SD card DELETE event (path=null) in folder: $folderPath - triggering capacity check")
                                checkAndNotifyCapacity("FileObserver[sdCard:DELETE:null]")
                            } else {
                                Log.d(TAG, "📁 SD card file event: path=null, event=$eventName in folder: $folderPath")
                            }
                        }
                    }
                }
                sdCardFileObserver?.startWatching()
                Log.i(TAG, "✅ Started monitoring SD card file list at: $folderPath")
                
                // Verify that monitoring path matches actual recording folder path
                val actualRecordingFolder = getRecordingFolder()
                val actualPath = actualRecordingFolder.absolutePath
                Log.i(TAG, "📍 Current recording folder path: $actualPath")
                Log.i(TAG, "📍 SD card monitoring path: $folderPath")
                
                if (getCurrentStorageType() == STORAGE_TYPE_SD_CARD) {
                    if (actualPath != folderPath) {
                        Log.w(TAG, "⚠️ WARNING: Recording folder path ($actualPath) differs from monitoring path ($folderPath)")
                        Log.w(TAG, "⚠️ This may cause FileObserver to miss file changes. Consider updating monitoring path.")
                    } else {
                        Log.i(TAG, "✅ Monitoring path matches recording folder path")
                    }
                }
            } else {
                Log.w(TAG, "⚠️ Cannot create or access SD card folder: ${sdCardFolder.absolutePath}")
            }
        } else {
            Log.d(TAG, "ℹ️ SD card not available, skipping SD card file monitoring")
        }
        
        // Summary: Log monitoring status and folder paths
        val monitoringStatus = buildString {
            append("📊 File monitoring status: ")
            if (internalFileObserver != null) {
                append("Internal ✅")
            } else {
                append("Internal ❌")
            }
            if (sdCardFileObserver != null) {
                append(", SD Card ✅")
            } else {
                append(", SD Card ❌")
            }
        }
        Log.i(TAG, monitoringStatus)
        
        // Verify both folders exist and are accessible
        val internalFolderCheck = File(getInternalExternalFilesDir(), FOLDER_NAME)
        val sdCardDirCheck = getSdCardExternalFilesDir()
        val sdCardFolderCheck = sdCardDirCheck?.let { File(it, FOLDER_NAME) }
        
        Log.i(TAG, "📂 Internal storage folder: ${internalFolderCheck.absolutePath} (exists: ${internalFolderCheck.exists()})")
        if (sdCardFolderCheck != null) {
            Log.i(TAG, "📂 SD card folder: ${sdCardFolderCheck.absolutePath} (exists: ${sdCardFolderCheck.exists()})")
        } else {
            Log.i(TAG, "📂 SD card folder: Not available")
        }
    }
    
    /**
     * Stop monitoring file list changes.
     */
    fun stopFileListMonitoring() {
        internalFileObserver?.stopWatching()
        internalFileObserver = null
        sdCardFileObserver?.stopWatching()
        sdCardFileObserver = null
        Log.i(TAG, "Stopped monitoring file list changes")
    }
}

