package ai.fd.thinklet.app.squid.run

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * MD5 工具类
 * 用于计算文件的 MD5 值
 */
object MD5Utils {
    
    private const val TAG = "MD5Utils"
    private const val CHUNK_SIZE = 8192 // 8KB chunks to reduce memory usage
    
    /**
     * 计算文件的 MD5 值
     * @param file 要计算 MD5 的文件
     * @return MD5 字符串，如果计算失败返回空字符串
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
     * 计算文件的 MD5 并保存为 .md5 文件
     * @param file 要计算 MD5 的文件
     * @return 是否成功保存 MD5 文件
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
     * 读取已缓存的 MD5 值
     * @param file 视频文件
     * @return MD5 字符串，如果读取失败返回空字符串
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
}

