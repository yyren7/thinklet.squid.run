package ai.fd.thinklet.app.squid.run

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecordingSegmentManager
 * 
 * Manages automatic video recording segmentation based on file size.
 * When a recording file approaches the specified size limit (default 100MB),
 * it triggers a segment switch to start recording to a new file.
 * 
 * This ensures continuous recording without file corruption or size issues.
 */
class RecordingSegmentManager(
    private val maxSegmentSizeBytes: Long = DEFAULT_MAX_SEGMENT_SIZE,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL,
    private val triggerThresholdRatio: Float = DEFAULT_TRIGGER_THRESHOLD
) {
    companion object {
        private const val TAG = "RecordSegmentManager"
        
        // Default: 20MB (20 * 1024 * 1024 bytes)
        const val DEFAULT_MAX_SEGMENT_SIZE = 20_971_520L
        
        // Check every 5 seconds
        const val DEFAULT_CHECK_INTERVAL = 5_000L
        
        // Trigger at 95% of max size (19MB for 20MB limit)
        const val DEFAULT_TRIGGER_THRESHOLD = 0.95f
    }
    
    private var monitorJob: Job? = null
    private var currentRecordingFile: File? = null
    private var isEnabled: Boolean = false
    private var segmentIndex: Int = 0
    private var baseFileName: String = ""
    
    // Callback when segment switch is needed
    var onSegmentSwitchNeeded: ((currentFile: File, nextFilePath: String) -> Unit)? = null
    
    /**
     * Start monitoring the recording file size
     * 
     * @param recordingFile Current recording file
     * @param scope CoroutineScope for the monitoring job
     */
    fun startMonitoring(recordingFile: File, scope: CoroutineScope) {
        Log.i(TAG, "📊 Starting segment monitoring for: ${recordingFile.name}")
        Log.i(TAG, "   Max size: ${maxSegmentSizeBytes / (1024 * 1024)}MB, Trigger at: ${(maxSegmentSizeBytes * triggerThresholdRatio) / (1024 * 1024)}MB")
        
        currentRecordingFile = recordingFile
        isEnabled = true
        
        // Extract segment index from initial filename (e.g., part000 -> 0)
        segmentIndex = extractSegmentIndex(recordingFile.name)
        
        // Extract base filename (without extension and part number)
        baseFileName = extractBaseFileName(recordingFile.name)
        
        // Cancel any existing monitoring job
        monitorJob?.cancel()
        
        // Start new monitoring job
        monitorJob = scope.launch(Dispatchers.IO) {
            try {
                monitorFileSize()
            } catch (e: CancellationException) {
                Log.d(TAG, "Monitoring job cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in monitoring job", e)
            }
        }
    }
    
    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        Log.i(TAG, "⏹️ Stopping segment monitoring")
        isEnabled = false
        monitorJob?.cancel()
        monitorJob = null
        currentRecordingFile = null
        segmentIndex = 0
    }
    
    /**
     * Update the current recording file (called after segment switch)
     */
    fun updateCurrentFile(newFile: File) {
        Log.d(TAG, "📝 Updated current recording file to: ${newFile.name}")
        currentRecordingFile = newFile
        segmentIndex++
    }
    
    /**
     * Monitor file size in background
     */
    private suspend fun monitorFileSize() {
        while (isEnabled) {
            delay(checkIntervalMs)
            
            val file = currentRecordingFile
            if (file == null) {
                Log.w(TAG, "⚠️ Current recording file is null, stopping monitoring")
                break
            }
            
            if (!file.exists()) {
                Log.w(TAG, "⚠️ Recording file does not exist: ${file.name}")
                continue
            }
            
            val currentSize = file.length()
            val currentSizeMB = currentSize / (1024 * 1024)
            val triggerSize = (maxSegmentSizeBytes * triggerThresholdRatio).toLong()
            
            Log.v(TAG, "📏 Current file size: ${currentSizeMB}MB / ${maxSegmentSizeBytes / (1024 * 1024)}MB")
            
            if (currentSize >= triggerSize) {
                Log.i(TAG, "🔄 File size limit reached (${currentSizeMB}MB), triggering segment switch")
                
                // Generate next segment file path
                val nextFilePath = generateNextSegmentPath(file)
                
                // Notify callback to perform the switch
                withContext(Dispatchers.Main) {
                    onSegmentSwitchNeeded?.invoke(file, nextFilePath)
                }
                
                // Wait for the switch to complete before continuing monitoring
                delay(2000)
            }
        }
        
        Log.d(TAG, "Monitoring loop ended")
    }
    
    /**
     * Generate the next segment file path
     */
    private fun generateNextSegmentPath(currentFile: File): String {
        val parentDir = currentFile.parentFile
        val nextIndex = segmentIndex + 1
        
        // Format: recording_timestamp_part001.mp4
        val nextFileName = "${baseFileName}_part${String.format("%03d", nextIndex)}.mp4"
        
        return File(parentDir, nextFileName).absolutePath
    }
    
    /**
     * Extract base filename from current filename
     * E.g., "recording_20231114_123456_part000.mp4" -> "recording_20231114_123456"
     */
    private fun extractBaseFileName(fileName: String): String {
        // Remove extension
        val nameWithoutExt = fileName.substringBeforeLast(".")
        
        // Remove part number pattern (_partXXX)
        val pattern = Regex("_part\\d{3}")
        val baseName = nameWithoutExt.replace(pattern, "")
        
        return if (baseName.isNotEmpty()) baseName else "recording"
    }
    
    /**
     * Extract segment index from filename
     * E.g., "recording_20231114_123456_part000.mp4" -> 0
     */
    private fun extractSegmentIndex(fileName: String): Int {
        val pattern = Regex("_part(\\d{3})")
        val matchResult = pattern.find(fileName)
        return matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
    
    /**
     * Check if segment recording is enabled
     */
    fun isMonitoring(): Boolean = isEnabled
    
    /**
     * Get current segment index
     */
    fun getCurrentSegmentIndex(): Int = segmentIndex
}


