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
 * - 文件管理：最多保留5个文件，超过后删除最老的
 * - 崩溃报告：使用UncaughtExceptionHandler捕获崩溃信息
 * - 保存位置：应用外部存储目录下的logs文件夹（与视频保存位置一致，用户可访问）
 */
class LogcatLogger private constructor(context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "LogcatLogger"
        private const val LOG_DIR_NAME = "logs"
        private const val LOG_FILE_PREFIX = "logcat_"
        private const val LOG_FILE_SUFFIX = ".txt"
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
        private const val MAX_FILE_COUNT = 5
        
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
            logProcess?.destroy()
            logProcess = null
            loggerScope.cancel()
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
            val processBuilder = ProcessBuilder(
                "logcat",
                "-v", "threadtime",  // 线程时间格式（包含日期、时间、PID、TID、优先级、标签）
                "*:V"  // 捕获所有级别的日志
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
                
                // 写入文件
                writeToFile(line)
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
     * 写入日志到文件
     */
    private fun writeToFile(line: String) {
        try {
            var file = currentLogFile ?: run {
                createNewLogFile()
                currentLogFile
            } ?: return
            
            // 检查文件大小，如果超过限制则创建新文件
            val actualFileSize = file.length()
            if (actualFileSize >= MAX_FILE_SIZE) {
                createNewLogFile()
                cleanupOldLogs()
                file = currentLogFile ?: return
            }
            
            val logLine = "$line\n"
            val bytes = logLine.toByteArray(Charsets.UTF_8)
            
            FileOutputStream(file, true).use { fos ->
                fos.write(bytes)
                currentFileSize = file.length()
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



