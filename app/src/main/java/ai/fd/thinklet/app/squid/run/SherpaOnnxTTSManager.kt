package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Sherpa-ONNX TTS Manager - A replacement for the native Android TTS.
 *
 * Advantages:
 * - Offline TTS, no network required.
 * - No system dependencies, avoiding Pico TTS crash issues.
 * - Faster initialization speed.
 * - Better sound quality and naturalness.
 * - Automatic message queuing: TTS calls before initialization are queued and played after ready.
 */
class SherpaOnnxTTSManager(private val context: Context) {
    
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator by lazy(LazyThreadSafetyMode.NONE) {
        context.getSystemService(Vibrator::class.java)
    }
    
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()
    
    @Volatile
    private var isSpeaking = false
    
    // TTS message queue: stores TTS requests before initialization
    private data class TTSMessage(
        val message: String,
        val queueMode: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val messageQueue = java.util.concurrent.LinkedBlockingQueue<TTSMessage>()
    private val queueProcessor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val maxQueueSize = 10  // Maximum queue length to avoid message backlog
    
    init {
        // Start queue processor
        startQueueProcessor()
        
        // Initialize TTS asynchronously to avoid blocking constructor
        Log.i(TAG, "🚀 Starting async TTS initialization...")
        Thread {
            try {
                initializeTTS()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize TTS in background", e)
                _ttsReady.value = false
            }
        }.start()
    }
    
    private fun initializeTTS() {
        val startTime = System.currentTimeMillis()
        try {
            Log.i(TAG, "📦 Step 1/3: Copying model files to cache...")
            val copyStartTime = System.currentTimeMillis()
            val modelDir = copyAssetsToCache()
            val copyDuration = System.currentTimeMillis() - copyStartTime
            Log.i(TAG, "✅ Model files ready (took ${copyDuration}ms)")
            
            Log.i(TAG, "⚙️ Step 2/3: Loading TTS model...")
            val modelLoadStartTime = System.currentTimeMillis()
            val config = getOfflineTtsConfig(
                modelDir = modelDir,
                modelName = "en_US-lessac-low.onnx",  // Example model
                dataDir = "$modelDir/espeak-ng-data", // Point to the espeak-ng-data subdirectory
                tokens = "tokens.txt",
                numThreads = 2,
                lengthScale = 1.0f,
                debug = false
            )
            
            tts = OfflineTts(null, config)
            val modelLoadDuration = System.currentTimeMillis() - modelLoadStartTime
            Log.i(TAG, "✅ TTS model loaded (took ${modelLoadDuration}ms)")
            
            Log.i(TAG, "🔊 Step 3/3: Initializing audio track...")
            val audioStartTime = System.currentTimeMillis()
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
            val audioDuration = System.currentTimeMillis() - audioStartTime
            Log.i(TAG, "✅ Audio track initialized (took ${audioDuration}ms)")
            
            _ttsReady.value = true
            val totalDuration = System.currentTimeMillis() - startTime
            Log.i(TAG, "✅ 🎉 Sherpa-ONNX TTS fully initialized in ${totalDuration}ms (sample rate: $sampleRate Hz)")
            Log.i(TAG, "📊 Breakdown: Copy=${copyDuration}ms, Model=${modelLoadDuration}ms, Audio=${audioDuration}ms")
            
            // If there are queued messages, notify user
            val queueSize = messageQueue.size
            if (queueSize > 0) {
                Log.i(TAG, "📢 TTS ready! Processing $queueSize queued message(s)...")
            }
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "❌ Failed to initialize Sherpa-ONNX TTS after ${totalDuration}ms", e)
            _ttsReady.value = false
        }
    }
    
    /**
     * Copies model files from assets to the cache directory.
     */
    private fun copyAssetsToCache(): String {
        // Use filesDir instead of cacheDir to keep files after restart.
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
     * Copies a single asset file.
     */
    private fun copyAssetFile(assetPath: String, destFile: File) {
        // Force overwrite
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Recursively copies an asset folder.
     */
    private fun copyAssetFolder(assetDir: String, destDir: File) {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val files = context.assets.list(assetDir)
        if (files.isNullOrEmpty()) {
            return // Empty directory or file
        }

        for (fileName in files) {
            val assetPath = "$assetDir/$fileName"
            val destFile = File(destDir, fileName)

            var isDir = false
            try {
                // Try to list sub-files/directories. If successful and not empty, it's a directory.
                val subFiles = context.assets.list(assetPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    isDir = true
                }
            } catch (e: java.io.IOException) {
                // If an IOException occurs, it's a file, not a directory.
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
     * Start queue processor to continuously process TTS messages
     */
    private fun startQueueProcessor() {
        queueProcessor.submit {
            Log.i(TAG, "📋 Queue processor started")
            while (!Thread.currentThread().isInterrupted) {
                try {
                    // Take message from queue (blocking wait)
                    val ttsMessage = messageQueue.take()
                    
                    // Wait for TTS initialization to complete
                    while (!_ttsReady.value) {
                        Log.d(TAG, "⏳ Waiting for TTS initialization before playing: ${ttsMessage.message}")
                        Thread.sleep(100)
                    }
                    
                    // Handle queue mode
                    if (ttsMessage.queueMode == QUEUE_FLUSH && isSpeaking) {
                        stopSpeaking()
                    }
                    
                    // Wait for current playback to complete (if QUEUE_ADD mode)
                    while (isSpeaking) {
                        Thread.sleep(50)
                    }
                    
                    // Play TTS
                    playTTS(ttsMessage.message)
                    
                } catch (e: InterruptedException) {
                    Log.i(TAG, "📋 Queue processor interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in queue processor", e)
                }
            }
            Log.i(TAG, "📋 Queue processor stopped")
        }
    }
    
    /**
     * Actual method to play TTS
     */
    private fun playTTS(message: String) {
        try {
            isSpeaking = true
            Log.d(TAG, "🔊 Generating speech: $message")
            
            val audio = tts!!.generate(message, sid = 0, speed = 1.0f)
            val samples = audio.samples
            
            if (samples.isEmpty()) {
                Log.w(TAG, "⚠️ Generated audio is empty for: $message")
                return
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
    }
    
    /**
     * Asynchronously plays the text.
     * If TTS is not initialized, messages are automatically queued and played after initialization completes
     * 
     * Important: This method is non-blocking and returns immediately even if TTS is initializing
     */
    fun speak(
        message: String,
        queueMode: Int = QUEUE_FLUSH,
    ) {
        // Check if queue is full
        if (messageQueue.size >= maxQueueSize) {
            Log.w(TAG, "⚠️ TTS queue is full (${messageQueue.size}/$maxQueueSize), dropping oldest message")
            messageQueue.poll()  // Remove oldest message
        }
        
        // Add message to queue
        val ttsMessage = TTSMessage(message, queueMode)
        val added = messageQueue.offer(ttsMessage)
        
        if (added) {
            if (_ttsReady.value) {
                Log.d(TAG, "📝 TTS message queued: $message (queue size: ${messageQueue.size})")
            } else {
                Log.i(TAG, "⏳ TTS message queued (waiting for initialization): $message (queue size: ${messageQueue.size})")
            }
        } else {
            Log.e(TAG, "❌ Failed to queue TTS message: $message")
        }
    }
    
    /**
     * Check if TTS is ready
     */
    fun isReady(): Boolean = _ttsReady.value
    
    /**
     * Get the number of messages currently waiting in queue
     */
    fun getQueueSize(): Int = messageQueue.size
    
    private fun stopSpeaking() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            isSpeaking = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop speaking", e)
        }
    }
    
    // ========== Convenience methods (maintaining the same interface as the original TTSManager) ==========
    
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
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        speak("application prepared")
    }
    
    fun speakBatteryAndNetworkStatus() {
        if (!_ttsReady.value) {
            Log.w(TAG, "⚠️ TTS not ready, skipping status announcement")
            return
        }

        // Use a thread pool to generate audio in parallel, then play sequentially.
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val audioQueue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()

        // Task 1: Generate battery status speech.
        executor.submit {
            try {
                val batteryPercentage = getBatteryPercentage()
                val batteryMessage = "battery status: ${batteryPercentage}% remaining"
                Log.d(TAG, "🔊 Generating battery status speech: $batteryMessage")
                val audio = tts!!.generate(batteryMessage, sid = 0, speed = 1.0f)
                audioQueue.put(audio.samples)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to generate battery status speech", e)
                // Put an empty array even on failure to avoid blocking the playback thread.
                audioQueue.put(FloatArray(0))
            }
        }

        // Task 2: Generate network status speech.
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

        // Task 3: Sequentially play the generated speech.
        Thread {
            try {
                isSpeaking = true
                var audiosPlayed = 0
                while (audiosPlayed < 2 && !Thread.currentThread().isInterrupted) {
                    // Take audio data from the queue, blocking until data is available.
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
        // Reuse the existing network status logic.
        // For simplicity, returning a simple status here.
        return "connected"
    }
    
    fun shutdown() {
        Log.i(TAG, "🛑 Shutting down Sherpa-ONNX TTS...")
        stopSpeaking()
        
        try {
            // Stop queue processor
            queueProcessor.shutdownNow()
            messageQueue.clear()
            Log.d(TAG, "📋 Queue processor and message queue cleared")
            
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
 * Configures Sherpa-ONNX TTS.
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
                lexicon = "",  // Leave empty for Piper models
                dataDir = dataDir,  // Set dataDir
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
