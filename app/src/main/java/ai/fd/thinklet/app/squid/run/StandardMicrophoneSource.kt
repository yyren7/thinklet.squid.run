package ai.fd.thinklet.app.squid.run

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource

/**
 * Standard microphone audio source with mute functionality.
 */
class StandardMicrophoneSource(
    private val audioSourceType: Int = MediaRecorder.AudioSource.DEFAULT
) : AudioSource() {

    private var isMuted: Boolean = false
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var thread: Thread? = null

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        return try {
            val channel = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channel, AudioFormat.ENCODING_PCM_16BIT)
            
            if (bufferSize <= 0) {
                return false
            }
            
            audioRecord = AudioRecord(audioSourceType, sampleRate, channel, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
            audioRecord?.state == AudioRecord.STATE_INITIALIZED
        } catch (e: Exception) {
            false
        }
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        val record = audioRecord ?: return
        
        if (isRecording) return
        
        isRecording = true
        record.startRecording()
        
        thread = Thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val buffer = ByteArray(bufferSize)
            
            while (isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val data = if (isMuted) {
                        ByteArray(read) // Muted data (all zeros)
                    } else {
                        buffer.copyOf(read)
                    }
                    val timeStamp = System.nanoTime() / 1000L
                    getMicrophoneData.inputPCMData(Frame(data, 0, read, timeStamp))
                }
            }
        }.apply { start() }
    }

    override fun stop() {
        isRecording = false
        thread?.interrupt()
        thread = null
        audioRecord?.stop()
    }

    override fun isRunning(): Boolean {
        return isRecording
    }

    override fun release() {
        stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun mute() {
        isMuted = true
    }

    fun unMute() {
        isMuted = false
    }
}
