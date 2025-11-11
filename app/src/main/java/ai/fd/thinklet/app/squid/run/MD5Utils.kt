package ai.fd.thinklet.app.squid.run

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * MD5 Utility Class
 * Used to calculate quick hash of files based on metadata (fast, not cryptographically secure).
 */
object MD5Utils {
    
    private const val TAG = "MD5Utils"
    private const val SAMPLE_SIZE = 1024 // Sample 1KB from each position
    
    /**
     * Samples file content from beginning, middle, and end positions.
     * @param file The file to sample.
     * @return ByteArray containing sampled data, or empty array on failure.
     */
    private fun sampleFileContent(file: File): ByteArray {
        return try {
            val fileSize = file.length()
            val samples = mutableListOf<Byte>()
            
            RandomAccessFile(file, "r").use { raf ->
                // If file is smaller than SAMPLE_SIZE, just read the whole file
                if (fileSize <= SAMPLE_SIZE && fileSize > 0) {
                    val smallSample = ByteArray(fileSize.toInt())
                    raf.seek(0)
                    val smallRead = raf.read(smallSample)
                    if (smallRead > 0) {
                        samples.addAll(smallSample.sliceArray(0 until smallRead).toList())
                    }
                } else if (fileSize > SAMPLE_SIZE) {
                    // Sample from beginning
                    val beginSample = ByteArray(SAMPLE_SIZE)
                    raf.seek(0)
                    val beginRead = raf.read(beginSample)
                    if (beginRead > 0) {
                        samples.addAll(beginSample.sliceArray(0 until beginRead).toList())
                    }
                    
                    // Sample from middle if file is large enough
                    if (fileSize > SAMPLE_SIZE * 2) {
                        val middlePos = fileSize / 2
                        val middleSample = ByteArray(SAMPLE_SIZE)
                        raf.seek(middlePos)
                        val middleRead = raf.read(middleSample)
                        if (middleRead > 0) {
                            samples.addAll(middleSample.sliceArray(0 until middleRead).toList())
                        }
                    }
                    
                    // Sample from end
                    val endPos = maxOf(0, fileSize - SAMPLE_SIZE)
                    val endSample = ByteArray(SAMPLE_SIZE)
                    raf.seek(endPos)
                    val endRead = raf.read(endSample)
                    if (endRead > 0) {
                        samples.addAll(endSample.sliceArray(0 until endRead).toList())
                    }
                }
            }
            
            samples.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sample file content: ${file.name}", e)
            ByteArray(0)
        }
    }
    
    /**
     * Calculates a quick hash of a file based on file name, size, and content samples.
     * This is faster than reading the entire file but more accurate than metadata-only hashing.
     * @param file The file for which to calculate the hash.
     * @return The hash string, or an empty string if the calculation fails.
     */
    fun calculateFileMD5(file: File): String {
        return try {
            Log.d(TAG, "Starting quick hash calculation for: ${file.name}")
            
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: ${file.name}")
                return ""
            }
            
            val fileSize = file.length()
            val fileName = file.name
            
            // Sample file content from multiple positions
            val contentSamples = sampleFileContent(file)
            
            // Combine metadata and samples for hashing
            val md5Digest = MessageDigest.getInstance("MD5")
            md5Digest.update("${fileName}:${fileSize}:".toByteArray())
            md5Digest.update(contentSamples)
            
            val hashBytes = md5Digest.digest()
            val hash = hashBytes.joinToString("") { byte -> "%02x".format(byte) }
            
            Log.i(TAG, "Quick hash calculation complete: ${file.name} -> $hash")
            hash
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate hash for: ${file.name}", e)
            "" // Return an empty string to indicate failure.
        }
    }
    
    /**
     * Calculates the MD5 of a file and saves it to a .md5 file.
     * @param file The file for which to calculate the MD5.
     * @return True if the MD5 file was saved successfully, false otherwise.
     */
    fun calculateAndSaveMD5(file: File): Boolean {
        val md5 = calculateFileMD5(file)
        if (md5.isEmpty()) {
            return false
        }
        
        val md5File = File(file.parent, "${file.name}.md5")
        return try {
            md5File.writeText(md5)
            Log.i(TAG, "MD5 file saved: ${md5File.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save MD5 file for: ${file.name}", e)
            false
        }
    }
    
    /**
     * Reads a cached MD5 value (synchronous version).
     * @param file The video file.
     * @return The MD5 string, or an empty string if reading fails.
     */
    fun readMD5FromFile(file: File): String {
        val md5File = File(file.parent, "${file.name}.md5")
        
        if (!md5File.exists()) {
            return ""
        }
        
        return try {
            val cachedMd5 = md5File.readText().trim()
            if (cachedMd5.isNotEmpty() && cachedMd5.length == 32) {
                Log.d(TAG, "Read cached MD5 for: ${file.name}")
                cachedMd5
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read MD5 file for: ${file.name}", e)
            ""
        }
    }
    
    /**
     * Reads a cached MD5 value (asynchronous version, for coroutines).
     * @param file The video file.
     * @return The MD5 string, or an empty string if reading fails.
     */
    suspend fun readMD5FromFileAsync(file: File): String = withContext(Dispatchers.IO) {
        readMD5FromFile(file)
    }
    
    /**
     * Quick hash for strings (used for ETag generation).
     * This is much faster than file MD5 calculation as it only hashes a string.
     * @param input The string to hash.
     * @return The MD5 hash of the string.
     */
    fun quickHash(input: String): String {
        return try {
            val md5Digest = MessageDigest.getInstance("MD5")
            val md5Bytes = md5Digest.digest(input.toByteArray())
            md5Bytes.joinToString("") { byte -> "%02x".format(byte) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate quick hash", e)
            input.hashCode().toString()
        }
    }
}

