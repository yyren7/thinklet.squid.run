package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Lightweight HTTP file server.
 * Specialized for video file transfer with a low-power design.
 *
 * Features:
 * - MD5 Caching: Calculated once and saved as a .md5 file to avoid repeated calculations.
 * - Streaming: Does not cache the entire file in memory.
 * - Range Support: Resumable downloads.
 * - Dual-end Verification: Provides MD5 to the PC client to verify transfer integrity.
 */
class FileTransferServer(
    private val context: Context,
    port: Int = 8889
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "FileTransferServer"
        private const val CHUNK_SIZE = 8192 // 8KB chunks to reduce memory usage
    }

    private val gson = Gson()
    private val recordFolder: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SquidRun").apply {
            if (!exists()) mkdirs()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Received request: ${session.method} $uri")

        return try {
            when {
                uri == "/files" && session.method == Method.GET -> handleFileList()
                uri.startsWith("/download/") && session.method == Method.GET -> handleDownload(uri, session)
                uri.startsWith("/delete/") && session.method == Method.DELETE -> handleDelete(uri)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle request", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }

    /**
     * Get the file list.
     * API: GET /files
     * Response: [{"name": "xxx.mp4", "size": 12345, "lastModified": 1234567890, "md5": "abc123..."}]
     * 只返回存在同名 .md5 文件的视频文件
     */
    private fun handleFileList(): Response {
        val files = recordFolder.listFiles { file ->
            // 只返回 .mp4 文件，且必须存在对应的 .md5 文件
            if (!file.isFile || !file.name.endsWith(".mp4")) {
                return@listFiles false
            }
            
            val md5File = File(file.parent, "${file.name}.md5")
            md5File.exists()
        }?.mapNotNull { file ->
            // 读取 MD5 值
            val md5 = MD5Utils.readMD5FromFile(file)
            if (md5.isEmpty()) {
                Log.w(TAG, "MD5 file exists but invalid for: ${file.name}")
                return@mapNotNull null
            }
            
            mapOf(
                "name" to file.name,
                "size" to file.length(),
                "lastModified" to file.lastModified(),
                "md5" to md5
            )
        } ?: emptyList()

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(files)
        )
    }

    /**
     * Download a file (with support for resumable downloads).
     * API: GET /download/{filename}
     * Supports the Range request header.
     */
    private fun handleDownload(uri: String, session: IHTTPSession): Response {
        val filename = uri.substringAfter("/download/")
        val file = File(recordFolder, filename)

        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "File not found"))
            )
        }

        // Check for Range request (resumable download).
        val rangeHeader = session.headers["range"]
        return if (rangeHeader != null) {
            handleRangeRequest(file, rangeHeader)
        } else {
            handleFullFileRequest(file)
        }
    }

    /**
     * Handle a full file request.
     */
    private fun handleFullFileRequest(file: File): Response {
        return try {
            val inputStream = FileInputStream(file)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "video/mp4",
                inputStream,
                file.length()
            )
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read file: ${file.name}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to "Failed to read file: ${e.message}"))
            )
        }
    }

    /**
     * Handle a Range request (resumable download).
     * Range format: bytes=start-end
     */
    private fun handleRangeRequest(file: File, rangeHeader: String): Response {
        return try {
            val fileSize = file.length()
            val range = parseRange(rangeHeader, fileSize)
            
            if (range == null) {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    "application/json",
                    gson.toJson(mapOf("error" to "Invalid Range"))
                )
            }

            val (start, end) = range
            val contentLength = end - start + 1
            
            val inputStream = FileInputStream(file)
            inputStream.skip(start)
            
            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                "video/mp4",
                inputStream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")
            
            Log.d(TAG, "Range request: $start-$end/$fileSize")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle Range request", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to "Failed to handle Range request: ${e.message}"))
            )
        }
    }

    /**
     * Parse the Range request header.
     * Format: bytes=start-end or bytes=start-
     */
    private fun parseRange(rangeHeader: String, fileSize: Long): Pair<Long, Long>? {
        return try {
            val range = rangeHeader.substringAfter("bytes=")
            val parts = range.split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                parts[1].toLong()
            } else {
                fileSize - 1
            }
            
            if (start >= fileSize || start > end) {
                null
            } else {
                Pair(start, end.coerceAtMost(fileSize - 1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Range: $rangeHeader", e)
            null
        }
    }

    /**
     * Delete a file.
     * API: DELETE /delete/{filename}
     */
    private fun handleDelete(uri: String): Response {
        val filename = uri.substringAfter("/delete/")
        val file = File(recordFolder, filename)

        return if (file.exists() && file.delete()) {
            // Also delete the corresponding .md5 file.
            val md5File = File(recordFolder, "$filename.md5")
            if (md5File.exists()) {
                md5File.delete()
                Log.i(TAG, "MD5 file deleted: $filename.md5")
            }
            
            Log.i(TAG, "File deleted: $filename")
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(mapOf("success" to true, "message" to "File deleted"))
            )
        } else {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to "File not found or failed to delete"))
            )
        }
    }

    /**
     * Start the server.
     */
    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "File transfer server started on port $listeningPort")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server", e)
            throw e
        }
    }

    /**
     * Stop the server.
     */
    fun stopServer() {
        stop()
        Log.i(TAG, "File transfer server stopped")
    }
}

