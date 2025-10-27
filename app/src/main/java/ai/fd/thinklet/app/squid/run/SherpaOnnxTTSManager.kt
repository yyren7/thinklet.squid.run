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
    
    init {
        // Sherpa-ONNX does not require a startup delay and can be initialized immediately.
        Log.i(TAG, "🚀 Initializing Sherpa-ONNX TTS...")
        initializeTTS()
    }
    
    private fun initializeTTS() {
        try {
            // Configure the TTS model path (requires placing model files in the assets/tts_models/ directory).
            val modelDir = copyAssetsToCache()
            
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
            
            // Initialize AudioTrack
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
     * Asynchronously plays the text.
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
        
        // Generate and play audio on a background thread.
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
        val playingFinished = java.util.concurrent.atomic.AtomicBoolean(false)

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
