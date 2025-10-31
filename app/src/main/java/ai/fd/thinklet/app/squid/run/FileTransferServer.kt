package ai.fd.thinklet.app.squid.run

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
    
    // Cache for file list ETag to reduce network traffic
    private var cachedFileListETag: String? = null
    private var cachedFileListJson: String? = null
    private var cachedFileListTimestamp: Long = 0

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Received request: ${session.method} $uri")

        return try {
            when {
                uri == "/files" && session.method == Method.GET -> handleFileList(session)
                uri.startsWith("/download/") && session.method == Method.GET -> handleDownload(uri, session)
                uri.startsWith("/delete/") && session.method == Method.DELETE -> handleDelete(uri)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: java.net.SocketException) {
            // Client disconnected (e.g., timeout), this is a normal situation, log a warning only.
            Log.w(TAG, "Client disconnected: ${e.message} (This is usually due to client timeout)")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to "Client disconnected"))
            )
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
     * Only returns video files that have a corresponding .md5 file.
     * 
     * Performance optimization: 
     * 1. Use coroutines to read multiple MD5 files in parallel.
     * 2. ETag caching to reduce network traffic when file list hasn't changed.
     */
    private fun handleFileList(session: IHTTPSession): Response {
        val startTime = System.currentTimeMillis()
        
        // Step 1: Generate a quick ETag based on directory contents (file names and modification times)
        // This is much faster than reading all MD5 files
        val validFiles = recordFolder.listFiles { file ->
            if (!file.isFile || !file.name.endsWith(".mp4")) {
                return@listFiles false
            }
            val md5File = File(file.parent, "${file.name}.md5")
            md5File.exists()
        } ?: emptyArray()
        
        // Generate ETag from file names and lastModified timestamps
        // This is a fast operation that doesn't require reading file contents
        val etagSource = validFiles.sortedBy { it.name }.joinToString("|") { 
            "${it.name}:${it.lastModified()}" 
        }
        val currentETag = if (etagSource.isEmpty()) {
            "empty"
        } else {
            MD5Utils.quickHash(etagSource)
        }
        
        val etagGenTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "ETag generated in ${etagGenTime}ms: $currentETag")
        
        // Step 2: Check If-None-Match header for cache validation
        val clientETag = session.headers["if-none-match"]
        if (clientETag != null && clientETag == currentETag) {
            // File list hasn't changed, return 304 Not Modified
            Log.d(TAG, "File list unchanged (ETag match), returning 304 Not Modified")
            val response = newFixedLengthResponse(
                Response.Status.NOT_MODIFIED,
                "application/json",
                ""
            )
            response.addHeader("ETag", currentETag)
            response.addHeader("Cache-Control", "max-age=30") // Cache for 30 seconds
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Expose-Headers", "ETag")
            return response
        }
        
        // Step 3: File list changed or no ETag provided, generate full response
        // Note: ETag mismatch can happen when:
        // - Files were added/removed on Android side
        // - File modification times changed
        // - PC side has stale cache (files deleted on Android but PC cache still has them)
        // In all cases, we return the current file list and PC will update its cache
        if (clientETag != null) {
            Log.d(TAG, "File list changed (ETag mismatch: client=${clientETag.substring(0, minOf(8, clientETag.length))}..., server=${currentETag.substring(0, minOf(8, currentETag.length))}...), generating full response (${validFiles.size} files)")
        } else {
            Log.d(TAG, "No ETag provided, generating full response (${validFiles.size} files)")
        }
        
        // Use cached response if ETag matches our cache
        // Cache is valid for 5 minutes to survive multiple scan cycles (PC scans every 60 seconds)
        if (currentETag == cachedFileListETag && 
            cachedFileListJson != null && 
            System.currentTimeMillis() - cachedFileListTimestamp < 300000) {
            // Use cached JSON response (cache valid for 5 minutes)
            Log.d(TAG, "Using cached JSON response (cache age: ${(System.currentTimeMillis() - cachedFileListTimestamp) / 1000}s)")
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                cachedFileListJson
            )
            response.addHeader("ETag", currentETag)
            response.addHeader("Cache-Control", "max-age=30")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Expose-Headers", "ETag")
            return response
        }
        
        // Step 4: Read all MD5 files in parallel and build response
        val files = runBlocking(Dispatchers.IO) {
            validFiles.map { file ->
                async {
                    // Read MD5 in parallel
                    val md5 = MD5Utils.readMD5FromFileAsync(file)
                    if (md5.isEmpty()) {
                        Log.w(TAG, "MD5 file exists but invalid for: ${file.name}")
                        null
                    } else {
                        mapOf(
                            "name" to file.name,
                            "size" to file.length(),
                            "lastModified" to file.lastModified(),
                            "md5" to md5
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val processingTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "File list prepared: ${files.size} files, took ${processingTime}ms (with ETag caching)")

        // Cache the response
        val jsonResponse = gson.toJson(files)
        cachedFileListETag = currentETag
        cachedFileListJson = jsonResponse
        cachedFileListTimestamp = System.currentTimeMillis()

        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            jsonResponse
        )
        
        response.addHeader("ETag", currentETag)
        response.addHeader("Cache-Control", "max-age=30") // Cache for 30 seconds
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Expose-Headers", "ETag")
        
        return response
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
     * Waits synchronously for the server to fully stop and release the port.
     */
    fun stopServer() {
        try {
            Log.i(TAG, "🛑 Stopping file transfer server on port $listeningPort...")
            
            // NanoHTTPD.stop() closes the server socket and stops accepting new connections.
            stop()
            
            // Wait a moment to ensure all connections are closed and the port is released.
            // NanoHTTPD's stop() is asynchronous, so it needs time to complete cleanup.
            Thread.sleep(200)
            
            Log.i(TAG, "✅ File transfer server stopped, port $listeningPort released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error while stopping file transfer server", e)
            throw e
        }
    }
}

