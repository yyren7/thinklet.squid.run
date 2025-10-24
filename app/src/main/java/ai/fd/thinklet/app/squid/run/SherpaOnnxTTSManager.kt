package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Sherpa-ONNX TTS Manager - 替代Android原生TTS
 * 
 * 优势：
 * - 离线TTS，无需网络
 * - 无系统依赖，避免Pico TTS崩溃问题
 * - 更快的初始化速度
 * - 更好的音质和自然度
 */
class SherpaOnnxTTSManager(private val context: Context) {
    
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()
    
    @Volatile
    private var isSpeaking = false
    
    init {
        // Sherpa-ONNX无需开机延迟，可以立即初始化
        Log.i(TAG, "🚀 Initializing Sherpa-ONNX TTS...")
        initializeTTS()
    }
    
    private fun initializeTTS() {
        try {
            // 配置TTS模型路径（需要将模型文件放在assets/tts_models/目录下）
            val modelDir = copyAssetsToCache()
            
            val config = getOfflineTtsConfig(
                modelDir = modelDir,
                modelName = "en_US-lessac-low.onnx",  // 示例模型
                dataDir = "$modelDir/espeak-ng-data", // 指向 espeak-ng-data 子目录
                tokens = "tokens.txt",
                numThreads = 2,
                lengthScale = 1.0f,
                debug = false
            )
            
            tts = OfflineTts(null, config)
            
            // 初始化AudioTrack
            val sampleRate = tts!!.sampleRate()
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            _ttsReady.value = true
            Log.i(TAG, "✅ Sherpa-ONNX TTS initialized successfully (sample rate: $sampleRate Hz)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Sherpa-ONNX TTS", e)
            _ttsReady.value = false
        }
    }
    
    /**
     * 将assets中的模型文件复制到缓存目录
     */
    private fun copyAssetsToCache(): String {
        // 使用 filesDir 而不是 cacheDir，重启后仍然保留
        val modelDir = File(context.filesDir, "sherpa_tts_models")

        val markerFile = File(modelDir, ".copied")
        if (markerFile.exists()) {
            Log.i(TAG, "📁 Model files already exist, skipping copy")
            return modelDir.absolutePath
        }

        Log.i(TAG, "📦 Copying model files (first time only)...")
        modelDir.mkdirs()

        copyAssetFile("tts_models/en_US-lessac-low.onnx", File(modelDir, "en_US-lessac-low.onnx"))
        copyAssetFile("tts_models/tokens.txt", File(modelDir, "tokens.txt"))
        copyAssetFolder("tts_models/espeak-ng-data", File(modelDir, "espeak-ng-data"))

        markerFile.createNewFile()
        Log.i(TAG, "✅ Model files copied successfully")

        return modelDir.absolutePath
    }

    /**
     * 复制单个 asset 文件
     */
    private fun copyAssetFile(assetPath: String, destFile: File) {
        // 强制覆盖
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 递归复制 asset 文件夹
     */
    private fun copyAssetFolder(assetDir: String, destDir: File) {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val files = context.assets.list(assetDir)
        if (files.isNullOrEmpty()) {
            return // 空目录或文件
        }

        for (fileName in files) {
            val assetPath = "$assetDir/$fileName"
            val destFile = File(destDir, fileName)

            var isDir = false
            try {
                // 尝试列出子文件/目录，如果成功且不为空，则认为是目录
                val subFiles = context.assets.list(assetPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    isDir = true
                }
            } catch (e: java.io.IOException) {
                // 如果发生IOException，说明它是一个文件而不是目录
                isDir = false
            }

            if (isDir) {
                copyAssetFolder(assetPath, destFile)
            } else {
                copyAssetFile(assetPath, destFile)
            }
        }
    }
    
    /**
     * 异步播放文本
     */
    fun speak(
        message: String,
        queueMode: Int = QUEUE_FLUSH,
    ) {
        if (!_ttsReady.value) {
            Log.w(TAG, "⚠️ TTS not ready, skipping speech: $message")
            return
        }
        
        if (isSpeaking && queueMode == QUEUE_FLUSH) {
            stopSpeaking()
        }
        
        // 在后台线程生成和播放音频
        Thread {
            try {
                isSpeaking = true
                Log.d(TAG, "🔊 Generating speech: $message")
                
                val audio = tts!!.generate(message, sid = 0, speed = 1.0f)
                val samples = audio.samples
                
                if (samples.isEmpty()) {
                    Log.w(TAG, "⚠️ Generated audio is empty for: $message")
                    return@Thread
                }
                
                Log.d(TAG, "🎵 Playing audio (${samples.size} samples)")
                audioTrack?.play()
                audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                audioTrack?.stop()
                
                Log.d(TAG, "✅ Speech completed: $message")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to speak: $message", e)
            } finally {
                isSpeaking = false
            }
        }.start()
    }
    
    private fun stopSpeaking() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            isSpeaking = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop speaking", e)
        }
    }
    
    // ========== 便捷方法（保持与原TTSManager相同的接口） ==========
    
    fun speakPowerDown() {
        speak("power down")
    }
    
    fun speakRecordingStarted() {
        speak("recording started")
    }
    
    fun speakRecordingFinished() {
        speak("recording finished")
    }
    
    fun speakApplicationPrepared() {
        speak("application prepared")
    }
    
    fun speakBatteryAndNetworkStatus() {
        if (!_ttsReady.value) {
            Log.w(TAG, "⚠️ TTS not ready, skipping status announcement")
            return
        }

        // 使用线程池并行生成音频，然后顺序播放
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val audioQueue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        val playingFinished = java.util.concurrent.atomic.AtomicBoolean(false)

        // 任务1：生成电池状态语音
        executor.submit {
            try {
                val batteryPercentage = getBatteryPercentage()
                val batteryMessage = "battery status: ${batteryPercentage}% remaining"
                Log.d(TAG, "🔊 Generating battery status speech: $batteryMessage")
                val audio = tts!!.generate(batteryMessage, sid = 0, speed = 1.0f)
                audioQueue.put(audio.samples)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to generate battery status speech", e)
                // 即使失败也要放入空数组，以避免阻塞播放线程
                audioQueue.put(FloatArray(0))
            }
        }

        // 任务2：生成网络状态语音
        executor.submit {
            try {
                val networkStatus = getNetworkStatus()
                val networkMessage = "network status: $networkStatus"
                Log.d(TAG, "🔊 Generating network status speech: $networkMessage")
                val audio = tts!!.generate(networkMessage, sid = 0, speed = 1.0f)
                audioQueue.put(audio.samples)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to generate network status speech", e)
                audioQueue.put(FloatArray(0))
            }
        }

        // 任务3：顺序播放生成的语音
        Thread {
            try {
                isSpeaking = true
                var audiosPlayed = 0
                while (audiosPlayed < 2 && !Thread.currentThread().isInterrupted) {
                    // 从队列中取出音频数据，会阻塞直到有数据为止
                    val samples = audioQueue.take()
                    if (samples.isNotEmpty()) {
                        Log.d(TAG, "🎵 Playing audio part ${audiosPlayed + 1} (${samples.size} samples)")
                        audioTrack?.play()
                        audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        audioTrack?.stop()
                    }
                    audiosPlayed++
                }
                Log.d(TAG, "✅ Status announcement completed")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Playback thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed during status announcement playback", e)
            } finally {
                isSpeaking = false
                executor.shutdown()
            }
        }.start()
    }
    
    internal fun getBatteryPercentage(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery percentage", e)
            0
        }
    }
    
    internal fun getNetworkStatus(): String {
        // 复用原有的网络状态获取逻辑
        // 为简化，这里返回简单状态
        return "connected"
    }
    
    fun shutdown() {
        Log.i(TAG, "🛑 Shutting down Sherpa-ONNX TTS...")
        stopSpeaking()
        
        try {
            audioTrack?.release()
            audioTrack = null
            
            tts?.release()
            tts = null
            
            _ttsReady.value = false
            Log.d(TAG, "✅ Sherpa-ONNX TTS shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during TTS shutdown", e)
        }
    }
    
    companion object {
        private const val TAG = "SherpaOnnxTTSManager"
        const val QUEUE_FLUSH = 0
        const val QUEUE_ADD = 1
    }
}

/**
 * 配置Sherpa-ONNX TTS
 */
private fun getOfflineTtsConfig(
    modelDir: String,
    modelName: String,
    dataDir: String,
    tokens: String,
    numThreads: Int,
    lengthScale: Float,
    debug: Boolean
): OfflineTtsConfig {
    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = "$modelDir/$modelName",
                lexicon = "",  // Piper 模型留空
                dataDir = dataDir,  // 设置 dataDir
                tokens = "$modelDir/$tokens",
                lengthScale = lengthScale
            ),
            numThreads = numThreads,
            debug = debug,
            provider = "cpu"
        ),
        ruleFsts = "",
        maxNumSentences = 1
    )
}
