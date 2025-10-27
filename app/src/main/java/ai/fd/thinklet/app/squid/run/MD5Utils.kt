package ai.fd.thinklet.app.squid.run

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * MD5 Utility Class
 * Used to calculate the MD5 hash of files.
 */
object MD5Utils {
    
    private const val TAG = "MD5Utils"
    private const val CHUNK_SIZE = 8192 // 8KB chunks to reduce memory usage
    
    /**
     * Calculates the MD5 hash of a file.
     * @param file The file for which to calculate the MD5.
     * @return The MD5 string, or an empty string if the calculation fails.
     */
    fun calculateFileMD5(file: File): String {
        return try {
            Log.d(TAG, "Starting MD5 calculation for: ${file.name}")
            val md5Digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    md5Digest.update(buffer, 0, bytesRead)
                }
            }
            
            val md5Bytes = md5Digest.digest()
            val md5String = md5Bytes.joinToString("") { byte -> "%02x".format(byte) }
            Log.i(TAG, "MD5 calculation complete: ${file.name} -> $md5String")
            md5String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate MD5 for: ${file.name}", e)
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
}

