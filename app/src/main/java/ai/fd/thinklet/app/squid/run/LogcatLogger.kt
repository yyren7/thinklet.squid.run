package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * LogcatLogger - 系统日志记录服务
 * 
 * 功能：
 * - 捕获系统logcat日志并写入文件
 * - 文件分页：每个文件10MB，超过后创建新文件
 * - 文件管理：最多保留20个文件，超过后删除最老的
 * - 崩溃报告：使用UncaughtExceptionHandler捕获崩溃信息
 * - 智能日志限流：多层限流机制，防止日志量激增
 *   - 优先级保护：ERROR和WARNING级别日志永不跳过，确保关键错误信息不丢失
 *   - 全局限流：全局10秒窗口内最多500条日志（50条/秒）
 *   - 标签限流：每个tag在10秒窗口内最多20条日志（2条/秒）
 *   - 磁盘压力感知：根据总日志大小动态调整限流策略
 *     - LOW (< 50%): 正常限流
 *     - NORMAL (50-80%): 正常限流
 *     - HIGH (80-95%): 加强限流（跳过比例×3）
 *     - CRITICAL (> 95%): 严格限流（跳过比例×5，全局跳过90%）
 *   - 固定步长跳过：使用固定步长策略替代随机跳过，更可预测
 *   - 性能优化：使用ArrayDeque替代MutableList，提高清理效率
 * - 保存位置：应用外部存储目录下的logs文件夹（与视频保存位置一致，用户可访问）
 */
class LogcatLogger private constructor(context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "LogcatLogger"
        private const val LOG_DIR_NAME = "logs"
        private const val LOG_FILE_PREFIX = "logcat_"
        private const val LOG_FILE_SUFFIX = ".txt"
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
        private const val MAX_FILE_COUNT = 20
        
        // Rate limiting configuration
        private const val RATE_LIMIT_WINDOW_MS = 10000L // 10 seconds sliding window
        private const val RATE_LIMIT_MAX_COUNT_PER_TAG = 20 // Max logs per tag per window (2 logs/sec)
        private const val RATE_LIMIT_MAX_COUNT_GLOBAL = 500 // Max logs globally per window (50 logs/sec)
        private const val RATE_LIMIT_CLEANUP_INTERVAL_MS = 30000L // Cleanup old entries every 30 seconds
        
        // Disk pressure thresholds (based on total log size)
        // Note: Using integer division to avoid floating point in const val
        private const val TOTAL_LOG_SIZE_THRESHOLD_NORMAL = MAX_FILE_SIZE * MAX_FILE_COUNT / 2L // 50% of total capacity (100MB)
        private const val TOTAL_LOG_SIZE_THRESHOLD_HIGH = MAX_FILE_SIZE * MAX_FILE_COUNT * 4L / 5L // 80% of total capacity (160MB)
        private const val TOTAL_LOG_SIZE_THRESHOLD_CRITICAL = MAX_FILE_SIZE * MAX_FILE_COUNT * 19L / 20L // 95% of total capacity (190MB)
        
        @Volatile
        private var INSTANCE: LogcatLogger? = null
        
        fun getInstance(context: Context): LogcatLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogcatLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val context: Context = context.applicationContext
    private var logDir: File? = null
    private val isRunning = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private var logProcess: Process? = null
    private var currentLogFile: File? = null
    private var currentFileSize: Long = 0
    private var fileIndex: Int = 0
    private val defaultExceptionHandler: Thread.UncaughtExceptionHandler? = 
        Thread.getDefaultUncaughtExceptionHandler()
    
    private val loggerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // BufferedOutputStream for efficient file writing
    private var bufferedOutputStream: BufferedOutputStream? = null
    private var lastFlushTime = 0L
    private val FLUSH_INTERVAL_MS = 1000L // Flush every 1 second
    
    // Rate limiting: track log count per tag in sliding window (using ArrayDeque for better performance)
    private val tagLogTimestamps = mutableMapOf<String, ArrayDeque<Long>>()
    private val globalLogTimestamps = ArrayDeque<Long>() // Global rate limiting
    private var lastCleanupTime = 0L
    private val rateLimitLock = Any() // Synchronization lock for rate limiting
    
    // Per-tag skip counters for fixed-step skipping strategy
    private val tagSkipCounters = mutableMapOf<String, Int>()
    
    // Disk pressure tracking
    private var lastTotalSizeCheckTime = 0L
    private var lastTotalLogSize = 0L
    private val TOTAL_SIZE_CHECK_INTERVAL_MS = 5000L // Check total size every 5 seconds

    init {
        // 设置当前线程为默认的未捕获异常处理器
        // 注意：目录和文件操作延迟到权限授予后初始化
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "LogcatLogger instance created (directory initialization deferred until permissions granted)")
    }
    
    /**
     * 初始化日志目录和文件索引（应在权限授予后调用）
     */
    private fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                // 创建日志目录 - 使用外部存储，与视频保存位置一致
                // 路径: /storage/emulated/0/Android/data/ai.fd.thinklet.app.squid.run/files/logs/
                val externalFilesDir = context.getExternalFilesDir(null)
                    ?: throw IllegalStateException("Cannot access external files directory")
                logDir = File(externalFilesDir, LOG_DIR_NAME)
                if (!logDir!!.exists()) {
                    logDir!!.mkdirs()
                }
                
                // 初始化文件索引
                fileIndex = getNextFileIndex()
                
                Log.i(TAG, "LogcatLogger initialized, log directory: ${logDir!!.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize log directory", e)
                isInitialized.set(false)
                throw e
            }
        }
    }

    /**
     * 启动日志记录（应在权限授予后调用）
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            loggerScope.launch {
                try {
                    // 先初始化目录和文件索引
                    initialize()
                    cleanupOldLogs()
                    startLogcatCapture()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start logcat capture", e)
                    isRunning.set(false)
                }
            }
            Log.i(TAG, "LogcatLogger started")
        } else {
            Log.w(TAG, "LogcatLogger is already running")
        }
    }

    /**
     * 停止日志记录
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                // Flush and close BufferedOutputStream
                bufferedOutputStream?.flush()
                bufferedOutputStream?.close()
                bufferedOutputStream = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing buffered output stream", e)
            }
            
            logProcess?.destroy()
            logProcess = null
            loggerScope.cancel()
            
            // Cleanup rate limiting data
            synchronized(rateLimitLock) {
                tagLogTimestamps.clear()
                globalLogTimestamps.clear()
                tagSkipCounters.clear()
            }
            
            Log.i(TAG, "LogcatLogger stopped")
        }
    }

    /**
     * 启动logcat捕获进程
     */
    private fun startLogcatCapture() {
        try {
            // 尝试续写最后一个日志文件，如果不存在或已满则创建新文件
            resumeOrCreateLogFile()
            
            // 启动logcat进程
            // 注意：在Android 4.1+之后，读取logcat需要READ_LOGS权限（需要系统签名或root）
            // 如果没有权限，logcat命令可能会失败，但不会影响应用运行
            // 优化：只捕获本应用的 Info 级别及以上日志，其他应用日志静默
            val packageName = context.packageName
            val processBuilder = ProcessBuilder(
                "logcat",
                "-v", "threadtime",  // 线程时间格式（包含日期、时间、PID、TID、优先级、标签）
                "${packageName}:I",  // 捕获本应用 Info 及以上级别日志（优化后）
                "*:S"  // 其他应用静默（大幅减少日志量）
            )
            
            logProcess = processBuilder.start()
            
            // 在后台线程中读取logcat输出
            loggerScope.launch {
                readLogcatOutput(logProcess!!)
            }
            
            Log.i(TAG, "Logcat capture process started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat process", e)
            throw e
        }
    }

    /**
     * 读取logcat输出并写入文件
     */
    private suspend fun readLogcatOutput(process: Process) {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        
        try {
            while (isRunning.get()) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break
                
                // Check rate limit before writing
                if (shouldWriteLog(line)) {
                    writeToFile(line)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error reading logcat output", e)
            }
        } finally {
            reader.close()
        }
    }
    
    /**
     * Log priority levels
     */
    private enum class LogPriority(val char: Char) {
        VERBOSE('V'),
        DEBUG('D'),
        INFO('I'),
        WARNING('W'),
        ERROR('E');
        
        companion object {
            fun fromChar(char: Char): LogPriority? {
                return values().find { it.char == char }
            }
        }
    }
    
    /**
     * Extract tag and priority from logcat log line
     * Format: "MM-DD HH:MM:SS.mmm  PID  TID Priority Tag: message"
     * Example: "01-01 12:00:00.123  1234  5678 I TagName: log message"
     * Returns Pair(tag, priority)
     */
    private fun extractTagAndPriority(logLine: String): Pair<String, LogPriority> {
        // Try to match logcat threadtime format
        // Pattern: date time pid tid priority tag: message
        val parts = logLine.split("\\s+".toRegex(), limit = 6)
        if (parts.size >= 6) {
            // Check if 5th part is priority (V/D/I/W/E) and 6th part starts with tag:
            val priorityPart = parts[4]
            val tagPart = parts[5]
            if (priorityPart.length == 1 && tagPart.contains(":")) {
                val priority = LogPriority.fromChar(priorityPart[0]) ?: LogPriority.INFO
                val tag = tagPart.substringBefore(":")
                return Pair(tag.ifEmpty { "Unknown" }, priority)
            }
        }
        
        // Fallback: try to find tag pattern "Priority TagName:"
        val tagMatch = Regex("\\s+([VDIWE])\\s+([^:]+):").find(logLine)
        if (tagMatch != null) {
            val priorityChar = tagMatch.groupValues[1][0]
            val priority = LogPriority.fromChar(priorityChar) ?: LogPriority.INFO
            val tag = tagMatch.groupValues[2].trim()
            return Pair(tag, priority)
        }
        
        return Pair("Unknown", LogPriority.INFO)
    }
    
    /**
     * Extract tag from logcat log line (backward compatibility)
     */
    private fun extractTagFromLogLine(line: String): String {
        return extractTagAndPriority(line).first
    }
    
    /**
     * Check if log should be written based on rate limiting and disk pressure
     * Returns true if log should be written, false if it should be skipped
     * 
     * Priority protection:
     * - ERROR and WARNING logs are NEVER skipped (critical for debugging)
     * - INFO logs can be skipped based on rate limiting
     * - DEBUG and VERBOSE logs follow normal rate limiting
     */
    private fun shouldWriteLog(logLine: String): Boolean {
        synchronized(rateLimitLock) {
            val currentTime = System.currentTimeMillis()
            val (tag, priority) = extractTagAndPriority(logLine)
            
            // Priority protection: Error and Warning logs are NEVER skipped
            // This ensures critical error information is always preserved
            if (priority == LogPriority.ERROR || priority == LogPriority.WARNING) {
                // Always write error and warning logs, but still record timestamp for rate tracking
                val timestamps = tagLogTimestamps.getOrPut(tag) { ArrayDeque() }
                val windowStart = currentTime - RATE_LIMIT_WINDOW_MS
                while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                    timestamps.removeFirst()
                }
                timestamps.addLast(currentTime)
                globalLogTimestamps.addLast(currentTime)
                return true
            }
            
            // Check disk pressure periodically
            val diskPressureLevel = checkDiskPressure(currentTime)
            
            // Cleanup old entries periodically
            if (currentTime - lastCleanupTime > RATE_LIMIT_CLEANUP_INTERVAL_MS) {
                cleanupOldTimestamps(currentTime)
                lastCleanupTime = currentTime
            }
            
            // Clean old timestamps from global queue
            val windowStart = currentTime - RATE_LIMIT_WINDOW_MS
            while (globalLogTimestamps.isNotEmpty() && globalLogTimestamps.first() < windowStart) {
                globalLogTimestamps.removeFirst()
            }
            
            // Check global rate limit first
            if (globalLogTimestamps.size >= RATE_LIMIT_MAX_COUNT_GLOBAL) {
                // Global limit exceeded: apply stricter limits based on disk pressure
                val globalSkipStep = when (diskPressureLevel) {
                    DiskPressureLevel.CRITICAL -> 10 // Skip 9 out of 10 logs
                    DiskPressureLevel.HIGH -> 5      // Skip 4 out of 5 logs
                    DiskPressureLevel.NORMAL -> 2    // Skip 1 out of 2 logs
                    DiskPressureLevel.LOW -> 1        // Skip none (but still check per-tag)
                }
                
                val globalCounter = tagSkipCounters.getOrPut("__GLOBAL__") { 0 }
                tagSkipCounters["__GLOBAL__"] = (globalCounter + 1) % globalSkipStep
                
                if (tagSkipCounters["__GLOBAL__"] != 0) {
                    return false // Skip this log
                }
            }
            
            // Get or create timestamp deque for this tag
            val timestamps = tagLogTimestamps.getOrPut(tag) { ArrayDeque() }
            
            // Remove old timestamps efficiently (ArrayDeque: O(n) but better than MutableList.removeAll)
            while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                timestamps.removeFirst()
            }
            
            // Check per-tag rate limit
            if (timestamps.size >= RATE_LIMIT_MAX_COUNT_PER_TAG) {
                // Over limit: use fixed-step skipping strategy
                // Calculate skip step based on how much we're over and disk pressure
                val overLimitRatio = timestamps.size.toDouble() / RATE_LIMIT_MAX_COUNT_PER_TAG
                val baseSkipStep = when {
                    overLimitRatio >= 3.0 -> 3  // Skip 2 out of 3 logs
                    overLimitRatio >= 2.0 -> 2  // Skip 1 out of 2 logs
                    else -> 1                   // Skip none (but still check)
                }
                
                // Apply disk pressure multiplier
                val skipStep = when (diskPressureLevel) {
                    DiskPressureLevel.CRITICAL -> baseSkipStep * 5  // Much stricter
                    DiskPressureLevel.HIGH -> baseSkipStep * 3
                    DiskPressureLevel.NORMAL -> baseSkipStep
                    DiskPressureLevel.LOW -> baseSkipStep
                }
                
                val counter = tagSkipCounters.getOrPut(tag) { 0 }
                tagSkipCounters[tag] = (counter + 1) % skipStep
                
                if (tagSkipCounters[tag] != 0) {
                    return false // Skip this log
                }
            } else {
                // Under limit: reset skip counter
                tagSkipCounters[tag] = 0
            }
            
            // All checks passed: write log and record timestamps
            timestamps.addLast(currentTime)
            globalLogTimestamps.addLast(currentTime)
            return true
        }
    }
    
    /**
     * Disk pressure levels based on total log size
     */
    private enum class DiskPressureLevel {
        LOW,        // < 50% capacity
        NORMAL,     // 50-80% capacity
        HIGH,       // 80-95% capacity
        CRITICAL    // > 95% capacity
    }
    
    /**
     * Check current disk pressure based on total log size
     */
    private fun checkDiskPressure(currentTime: Long): DiskPressureLevel {
        // Check total size periodically to avoid frequent file system operations
        if (currentTime - lastTotalSizeCheckTime < TOTAL_SIZE_CHECK_INTERVAL_MS) {
            // Use cached value, but still check if we're near critical threshold
            val totalSize = lastTotalLogSize
            return when {
                totalSize >= TOTAL_LOG_SIZE_THRESHOLD_CRITICAL -> DiskPressureLevel.CRITICAL
                totalSize >= TOTAL_LOG_SIZE_THRESHOLD_HIGH -> DiskPressureLevel.HIGH
                totalSize >= TOTAL_LOG_SIZE_THRESHOLD_NORMAL -> DiskPressureLevel.NORMAL
                else -> DiskPressureLevel.LOW
            }
        }
        
        // Calculate total log size
        val totalSize = calculateTotalLogSize()
        lastTotalLogSize = totalSize
        lastTotalSizeCheckTime = currentTime
        
        return when {
            totalSize >= TOTAL_LOG_SIZE_THRESHOLD_CRITICAL -> {
                Log.w(TAG, "⚠️ Critical disk pressure: ${totalSize / 1024 / 1024}MB / ${MAX_FILE_SIZE * MAX_FILE_COUNT / 1024 / 1024}MB")
                DiskPressureLevel.CRITICAL
            }
            totalSize >= TOTAL_LOG_SIZE_THRESHOLD_HIGH -> {
                Log.w(TAG, "⚠️ High disk pressure: ${totalSize / 1024 / 1024}MB / ${MAX_FILE_SIZE * MAX_FILE_COUNT / 1024 / 1024}MB")
                DiskPressureLevel.HIGH
            }
            totalSize >= TOTAL_LOG_SIZE_THRESHOLD_NORMAL -> DiskPressureLevel.NORMAL
            else -> DiskPressureLevel.LOW
        }
    }
    
    /**
     * Calculate total size of all log files
     */
    private fun calculateTotalLogSize(): Long {
        val dir = logDir ?: return 0L
        try {
            val files = dir.listFiles { file ->
                file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
            } ?: return 0L
            
            return files.sumOf { it.length() }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating total log size", e)
            return 0L
        }
    }
    
    /**
     * Cleanup old timestamps outside the sliding window for all tags
     * Optimized for ArrayDeque: remove from front until we find timestamps in window
     */
    private fun cleanupOldTimestamps(currentTime: Long) {
        val windowStart = currentTime - RATE_LIMIT_WINDOW_MS
        val tagsToRemove = mutableListOf<String>()
        
        tagLogTimestamps.forEach { (tag, timestamps) ->
            // Efficiently remove old timestamps from front
            while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                timestamps.removeFirst()
            }
            // Remove empty tag entries to prevent memory leak
            if (timestamps.isEmpty()) {
                tagsToRemove.add(tag)
            }
        }
        
        tagsToRemove.forEach { tagLogTimestamps.remove(it) }
        
        // Cleanup global timestamps
        while (globalLogTimestamps.isNotEmpty() && globalLogTimestamps.first() < windowStart) {
            globalLogTimestamps.removeFirst()
        }
        
        // Cleanup skip counters for tags that no longer exist
        val existingTags = tagLogTimestamps.keys.toSet()
        tagSkipCounters.keys.removeAll { it != "__GLOBAL__" && !existingTags.contains(it) }
    }

    /**
     * 写入日志到文件（优化版：使用 BufferedOutputStream）
     */
    private fun writeToFile(line: String) {
        try {
            // 检查是否需要创建新文件
            if (currentLogFile == null) {
                createNewLogFile()
            }
            
            val file = currentLogFile ?: return
            
            val logLine = "$line\n"
            val bytes = logLine.toByteArray(Charsets.UTF_8)
            
            // 检查文件大小：使用 currentFileSize + 新数据大小来判断，避免频繁读取文件
            // 如果 currentFileSize 可能不准确（如应用重启后），则同步一次实际文件大小
            if (currentFileSize == 0L && file.exists() && file.length() > 0) {
                // 如果 currentFileSize 为0但文件不为空，说明可能是续写的情况，同步实际大小
                currentFileSize = file.length()
            }
            
            // 检查写入后是否会超过限制（在写入前检查，避免写入后才发现超限）
            if (currentFileSize + bytes.size >= MAX_FILE_SIZE) {
                // 关闭旧的 BufferedOutputStream
                bufferedOutputStream?.flush()
                bufferedOutputStream?.close()
                bufferedOutputStream = null
                
                createNewLogFile()
                cleanupOldLogs()
                
                // 确保新文件的 BufferedOutputStream 已初始化
                if (bufferedOutputStream == null && currentLogFile != null) {
                    bufferedOutputStream = BufferedOutputStream(
                        FileOutputStream(currentLogFile, true),
                        8192  // 8KB buffer
                    )
                }
            } else {
                // 确保 BufferedOutputStream 已初始化
                if (bufferedOutputStream == null && currentLogFile != null) {
                    bufferedOutputStream = BufferedOutputStream(
                        FileOutputStream(currentLogFile, true),
                        8192  // 8KB buffer
                    )
                }
            }
            
            // 使用 BufferedOutputStream 写入（显著减少 I/O 操作）
            bufferedOutputStream?.write(bytes)
            currentFileSize += bytes.size
            
            // 定期 flush，而不是每次都 flush（减少磁盘写入）
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlushTime > FLUSH_INTERVAL_MS) {
                bufferedOutputStream?.flush()
                lastFlushTime = currentTime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log file", e)
        }
    }

    /**
     * 尝试续写最后一个日志文件，如果不存在或已满则创建新文件
     */
    private fun resumeOrCreateLogFile() {
        try {
            // 查找最后一个日志文件（按修改时间排序）
            val lastFile = getLastLogFile()
            
            if (lastFile != null && lastFile.exists()) {
                val fileSize = lastFile.length()
                // 如果文件未满（小于MAX_FILE_SIZE），则续写
                if (fileSize < MAX_FILE_SIZE) {
                    currentLogFile = lastFile
                    currentFileSize = fileSize
                    // 从文件名中提取索引
                    fileIndex = extractFileIndex(lastFile.name)
                    Log.i(TAG, "Resuming log file: ${lastFile.name} (current size: ${fileSize / 1024}KB)")
                    return
                } else {
                    Log.i(TAG, "Last log file is full (${fileSize / 1024}KB), creating new file")
                }
            }
            
            // 如果最后一个文件不存在或已满，创建新文件
            createNewLogFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume or create log file", e)
            // 如果出错，尝试创建新文件
            createNewLogFile()
        }
    }

    /**
     * 获取最后一个日志文件（按修改时间排序，最新的）
     */
    private fun getLastLogFile(): File? {
        val dir = logDir ?: return null
        val files = dir.listFiles { file ->
            file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        } ?: return null
        
        if (files.isEmpty()) return null
        
        // 按修改时间排序，返回最新的文件
        return files.sortedByDescending { it.lastModified() }.firstOrNull()
    }

    /**
     * 从文件名中提取文件索引
     */
    private fun extractFileIndex(fileName: String): Int {
        val indexMatch = Regex("_\\d+${LOG_FILE_SUFFIX.replace(".", "\\.")}$").find(fileName)
        return if (indexMatch != null) {
            val indexStr = indexMatch.value.removePrefix("_").removeSuffix(LOG_FILE_SUFFIX)
            indexStr.toIntOrNull() ?: 0
        } else {
            0
        }
    }

    /**
     * 创建新的日志文件
     */
    private fun createNewLogFile() {
        val dir = logDir ?: run {
            Log.e(TAG, "Cannot create log file: directory not initialized")
            return
        }
        
        try {
            // 获取下一个文件索引
            fileIndex = getNextFileIndex() + 1
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${LOG_FILE_PREFIX}${timestamp}_${fileIndex}${LOG_FILE_SUFFIX}"
            val newFile = File(dir, fileName)
            
            // 如果文件已存在，增加索引直到找到不存在的文件名
            var index = fileIndex
            var targetFile = newFile
            while (targetFile.exists()) {
                index++
                val altFileName = "${LOG_FILE_PREFIX}${timestamp}_${index}${LOG_FILE_SUFFIX}"
                targetFile = File(dir, altFileName)
            }
            
            targetFile.createNewFile()
            currentLogFile = targetFile
            fileIndex = index
            currentFileSize = 0
            
            // 为新文件创建 BufferedOutputStream
            bufferedOutputStream = BufferedOutputStream(
                FileOutputStream(targetFile, true),
                8192  // 8KB buffer
            )
            lastFlushTime = System.currentTimeMillis()
            
            Log.i(TAG, "Created new log file: ${targetFile.name} at ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new log file", e)
        }
    }

    /**
     * 获取下一个文件索引（返回当前最大索引）
     */
    private fun getNextFileIndex(): Int {
        val dir = logDir ?: return 0
        val files = dir.listFiles { file ->
            file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        } ?: return 0
        
        if (files.isEmpty()) return 0
        
        // 从所有文件名中提取索引，返回最大值
        var maxIndex = 0
        files.forEach { file ->
            val name = file.name
            // 匹配格式: logcat_yyyyMMdd_HHmmss_数字.txt
            val indexMatch = Regex("_\\d+${LOG_FILE_SUFFIX.replace(".", "\\.")}$").find(name)
            if (indexMatch != null) {
                val indexStr = indexMatch.value.removePrefix("_").removeSuffix(LOG_FILE_SUFFIX)
                val index = indexStr.toIntOrNull() ?: 0
                if (index > maxIndex) {
                    maxIndex = index
                }
            }
        }
        
        return maxIndex
    }

    /**
     * 清理旧的日志文件，保持最多MAX_FILE_COUNT个文件
     */
    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        try {
            val files = dir.listFiles { file ->
                file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
            } ?: return
            
            if (files.size <= MAX_FILE_COUNT) {
                return
            }
            
            // 按修改时间排序，删除最老的文件
            files.sortBy { it.lastModified() }
            val filesToDelete = files.take(files.size - MAX_FILE_COUNT)
            
            filesToDelete.forEach { file ->
                try {
                    if (file.delete()) {
                        Log.i(TAG, "Deleted old log file: ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete old log file: ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old logs", e)
        }
    }

    /**
     * 未捕获异常处理器 - 捕获崩溃信息
     */
    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            // 如果目录未初始化，尝试初始化（可能在权限授予前发生崩溃）
            if (!isInitialized.get()) {
                try {
                    initialize()
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot initialize log directory for crash report", e)
                    defaultExceptionHandler?.uncaughtException(thread, exception)
                    return
                }
            }
            
            // 确保有当前日志文件
            if (currentLogFile == null) {
                createNewLogFile()
            }
            
            // 记录崩溃信息到日志文件
            val crashInfo = StringBuilder()
            crashInfo.append("\n")
            crashInfo.append("=".repeat(80)).append("\n")
            crashInfo.append("CRASH REPORT - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            crashInfo.append("=".repeat(80)).append("\n")
            crashInfo.append("Thread: ${thread.name} (ID: ${thread.id})\n")
            crashInfo.append("Exception: ${exception.javaClass.name}\n")
            crashInfo.append("Message: ${exception.message}\n")
            crashInfo.append("\n")
            crashInfo.append("Stack Trace:\n")
            
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            exception.printStackTrace(pw)
            crashInfo.append(sw.toString())
            
            // 记录所有线程的堆栈跟踪
            crashInfo.append("\n")
            crashInfo.append("All Threads Stack Trace:\n")
            crashInfo.append("-".repeat(80)).append("\n")
            Thread.getAllStackTraces().forEach { (t, stackTrace) ->
                crashInfo.append("Thread: ${t.name} (ID: ${t.id}) - State: ${t.state}\n")
                stackTrace.forEach { element ->
                    crashInfo.append("  at $element\n")
                }
                crashInfo.append("\n")
            }
            
            crashInfo.append("=".repeat(80)).append("\n")
            crashInfo.append("\n")
            
            // 直接写入崩溃信息到文件（不使用writeToFile，避免文件大小检查）
            currentLogFile?.let { file ->
                try {
                    val bytes = crashInfo.toString().toByteArray(Charsets.UTF_8)
                    FileOutputStream(file, true).use { fos ->
                        fos.write(bytes)
                        fos.flush()
                    }
                    Log.e(TAG, "Crash captured and logged to: ${file.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write crash log", e)
                }
            } ?: run {
                // 如果当前文件不存在，尝试创建并写入
                try {
                    createNewLogFile()
                    currentLogFile?.let { file ->
                        val bytes = crashInfo.toString().toByteArray(Charsets.UTF_8)
                        FileOutputStream(file, true).use { fos ->
                            fos.write(bytes)
                            fos.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create crash log file", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        } finally {
            // 调用默认的异常处理器
            defaultExceptionHandler?.uncaughtException(thread, exception)
        }
    }

    /**
     * 获取日志目录路径
     */
    fun getLogDirectory(): File? {
        return logDir
    }

    /**
     * 获取所有日志文件列表
     */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { file ->
            file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        } ?: return emptyList()
        
        return files.sortedByDescending { it.lastModified() }
    }
}



