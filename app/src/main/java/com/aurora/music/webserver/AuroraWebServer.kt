package com.aurora.music.webserver

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AuroraWebServer(
    private val context: Context,
    private val storageBridge: WebServerStorageBridge,
    private val port: Int = 8080
) {
    companion object {
        private const val TAG = "AuroraWebServer"
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()
    private val serverStartTime = AtomicLong(0)
    private val requestCount = AtomicLong(0)

    val activePort: Int
        get() = serverSocket?.localPort ?: port

    fun isServerRunning(): Boolean = isRunning.get()

    /**
     * Starts the HTTP server on background threads with port-in-use handling.
     */
    fun start(): Boolean {
        if (isRunning.get()) return true

        try {
            // Attempt requested port first; if busy, bind to open port 0
            serverSocket = try {
                ServerSocket(port)
            } catch (_: Exception) {
                ServerSocket(0)
            }
            isRunning.set(true)
            serverStartTime.set(System.currentTimeMillis())

            threadPool.execute {
                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        // TCP socket optimization: disable Nagle's algorithm for low-latency audio packet dispatch
                        clientSocket.tcpNoDelay = true
                        clientSocket.sendBufferSize = 64 * 1024
                        threadPool.execute {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            }
            Log.i(TAG, "Aurora Web Server started on port $activePort")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server on port $port", e)
            isRunning.set(false)
            return false
        }
    }

    /**
     * Cleanly stops the HTTP server and frees sockets.
     */
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Aurora Web Server stopped")
    }

    private fun handleClient(socket: Socket) {
        requestCount.incrementAndGet()
        try {
            socket.soTimeout = 20000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]

            // Path Traversal Security: Reject immediately if path contains relative directory traversal
            if (rawPath.contains("..") || rawPath.contains("%2e%2e", ignoreCase = true) || rawPath.contains("\\")) {
                sendJsonError(output, 403, "Access denied: Path traversal prohibited")
                output.flush()
                return
            }

            // Read request headers
            val headers = mutableMapOf<String, String>()
            var headerLine: String?
            while (input.readLine().also { headerLine = it } != null) {
                if (headerLine.isNullOrBlank()) break
                val colonIdx = headerLine!!.indexOf(':')
                if (colonIdx > 0) {
                    val key = headerLine!!.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine!!.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            routeRequest(method, rawPath, headers, output)
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun routeRequest(
        method: String,
        rawPath: String,
        headers: Map<String, String>,
        output: OutputStream
    ) {
        val path = rawPath.substringBefore('?')
        val queryString = rawPath.substringAfter('?', "")
        val queryParams = parseQueryParams(queryString)

        if (method == "OPTIONS") {
            sendCorsHeaders(output, 204, "No Content")
            return
        }

        when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                val data = WebAssets.getIndexHtml(context)
                sendResponse(output, 200, "OK", "text/html; charset=UTF-8", data)
            }

            method == "GET" && path == "/api/status" -> {
                handleApiStatus(output)
            }

            method == "GET" && path == "/api/tracks" -> {
                handleApiTracks(output)
            }

            method == "GET" && path == "/api/locations" -> {
                handleApiLocations(output)
            }

            method == "GET" && path == "/api/stream" -> {
                val trackId = queryParams["id"]
                val mode = queryParams["mode"] // "direct" or "decoded"
                if (trackId == null) {
                    sendJsonError(output, 400, "Missing track id parameter")
                } else {
                    handleStreamTrack(trackId, mode, queryParams, headers, output)
                }
            }

            method == "GET" && path == "/api/cover" -> {
                val trackId = queryParams["id"]
                val isThumb = queryParams["thumb"] != "0"
                if (trackId != null) {
                    handleAlbumCover(trackId, isThumb, output)
                } else {
                    sendResponse(output, 404, "Not Found", "text/plain", "Missing id".toByteArray())
                }
            }

            method == "DELETE" && path == "/api/track" -> {
                val trackId = queryParams["id"]
                if (trackId == null) {
                    sendJsonError(output, 400, "Missing track id parameter")
                } else {
                    handleDeleteTrack(trackId, output)
                }
            }

            else -> {
                sendResponse(output, 404, "Not Found", "text/plain", "404 Not Found".toByteArray())
            }
        }
    }

    private fun handleApiStatus(output: OutputStream) {
        val uptimeSeconds = if (serverStartTime.get() > 0) {
            (System.currentTimeMillis() - serverStartTime.get()) / 1000
        } else 0L

        val locations = storageBridge.getSavedLocations()
        val json = JSONObject().apply {
            put("status", "online")
            put("port", activePort)
            put("ip", NetworkUtils.getLocalIpAddress(context))
            put("uptimeSeconds", uptimeSeconds)
            put("requestCount", requestCount.get())
            put("locationCount", locations.size)
        }
        sendJsonResponse(output, 200, json.toString())
    }

    private fun handleApiTracks(output: OutputStream) {
        val allTracks = storageBridge.getCachedTracks()
        val array = JSONArray()

        for (t in allTracks) {
            val obj = JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("durationMs", t.durationMs)
                put("durationFormatted", t.durationFormatted)
                put("sizeBytes", t.sizeBytes)
                put("sizeFormatted", t.sizeFormatted)
                put("format", t.format)
                put("fileName", t.fileName)
                put("isAlac", t.isAlac)
            }
            array.put(obj)
        }
        sendJsonResponse(output, 200, array.toString())
    }

    private fun handleApiLocations(output: OutputStream) {
        val locations = storageBridge.getSavedLocations()
        val array = JSONArray()
        for (loc in locations) {
            val obj = JSONObject().apply {
                put("id", loc.id)
                put("name", loc.name)
                put("trackCount", loc.trackCount)
                put("isSystemLibrary", loc.isSystemLibrary)
            }
            array.put(obj)
        }
        sendJsonResponse(output, 200, array.toString())
    }

    private fun handleStreamTrack(
        trackId: String,
        mode: String?,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        output: OutputStream
    ) {
        val track = storageBridge.getTrackById(trackId)
        if (track == null) {
            sendJsonError(output, 404, "Track not found")
            return
        }

        // 1. Decoded Lossless PCM WAV Stream (for unplayable ALAC/FLAC or explicitly requested decoded mode)
        if (mode == "decoded") {
            val seekSec = queryParams["seek"]?.toDoubleOrNull() ?: 0.0
            val startMs = (seekSec * 1000).toLong().coerceAtLeast(0L)
            val decodedStream = storageBridge.openDecodedWavStream(track, startMs)
            if (decodedStream != null) {
                try {
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: audio/wav\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n" +
                            "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray(Charsets.UTF_8))

                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (decodedStream.read(buffer).also { read = it } > 0) {
                        try {
                            output.write(buffer, 0, read)
                        } catch (_: java.io.IOException) {
                            break
                        }
                    }
                } finally {
                    try { decodedStream.close() } catch (_: Exception) {}
                }
                return
            }
        }

        // 2. Direct Stream with Fast Kernel Range Seeking (for FLAC, ALAC, M4A, MP3, AAC, WAV, OGG)
        val pfd = storageBridge.openTrackFileDescriptor(track)
        if (pfd != null) {
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            try {
                val mimeType = when (track.format.lowercase()) {
                    "flac" -> "audio/flac"
                    "m4a", "alac" -> "audio/mp4"
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "ogg", "opus" -> "audio/ogg"
                    "aac" -> "audio/aac"
                    else -> "audio/mp4"
                }

                val totalBytes = try {
                    val sz = channel.size()
                    if (sz > 0) sz else (if (track.sizeBytes > 0) track.sizeBytes else 0L)
                } catch (_: Exception) {
                    if (track.sizeBytes > 0) track.sizeBytes else 0L
                }

                val rangeHeader = headers["range"]

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val rangeVal = rangeHeader.substringAfter("bytes=").trim()
                    val dashIdx = rangeVal.indexOf('-')
                    var start: Long = 0L
                    var end: Long = if (totalBytes > 0) totalBytes - 1 else Long.MAX_VALUE

                    if (dashIdx == 0) {
                        // Suffix range: bytes=-500 (last 500 bytes)
                        val suffixLen = rangeVal.substring(1).toLongOrNull() ?: 0L
                        if (totalBytes > 0) {
                            start = maxOf(0L, totalBytes - suffixLen)
                            end = totalBytes - 1
                        }
                    } else if (dashIdx > 0) {
                        val firstPart = rangeVal.substring(0, dashIdx).trim()
                        val secondPart = rangeVal.substring(dashIdx + 1).trim()
                        start = firstPart.toLongOrNull() ?: 0L
                        if (secondPart.isNotEmpty()) {
                            end = secondPart.toLongOrNull() ?: (if (totalBytes > 0) totalBytes - 1 else Long.MAX_VALUE)
                        } else if (totalBytes > 0) {
                            end = totalBytes - 1
                        }
                    }

                    if (totalBytes > 0 && start >= totalBytes) {
                        val header416 = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                                "Content-Range: bytes */$totalBytes\r\n" +
                                "Content-Length: 0\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(header416.toByteArray(Charsets.UTF_8))
                        return
                    }

                    if (totalBytes > 0) {
                        end = minOf(end, totalBytes - 1)
                    }
                    val contentLength = if (end >= start) (end - start + 1) else 0L

                    val headerBuilder = StringBuilder()
                    headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                    headerBuilder.append("Content-Type: $mimeType\r\n")
                    headerBuilder.append("Content-Length: $contentLength\r\n")
                    headerBuilder.append("Content-Range: bytes $start-$end/${if (totalBytes > 0) totalBytes.toString() else "*"}\r\n")
                    headerBuilder.append("Accept-Ranges: bytes\r\n")
                    headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                    headerBuilder.append("Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n")
                    headerBuilder.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                    headerBuilder.append("Cache-Control: public, max-age=3600\r\n")
                    headerBuilder.append("Connection: close\r\n\r\n")
                    output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))

                    // Instantaneous O(1) kernel seek
                    channel.position(start)

                    val buffer = ByteArray(64 * 1024)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = fis.read(buffer, 0, toRead)
                        if (read <= 0) break
                        try {
                            output.write(buffer, 0, read)
                        } catch (_: java.io.IOException) {
                            break
                        }
                        remaining -= read
                    }
                } else {
                    val headerBuilder = StringBuilder()
                    headerBuilder.append("HTTP/1.1 200 OK\r\n")
                    headerBuilder.append("Content-Type: $mimeType\r\n")
                    if (totalBytes > 0) {
                        headerBuilder.append("Content-Length: $totalBytes\r\n")
                    }
                    headerBuilder.append("Accept-Ranges: bytes\r\n")
                    headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                    headerBuilder.append("Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n")
                    headerBuilder.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                    headerBuilder.append("Cache-Control: public, max-age=3600\r\n")
                    headerBuilder.append("Connection: close\r\n\r\n")
                    output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))

                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (fis.read(buffer).also { read = it } > 0) {
                        try {
                            output.write(buffer, 0, read)
                        } catch (_: java.io.IOException) {
                            break
                        }
                    }
                }
            } finally {
                try { fis.close() } catch (_: Exception) {}
                try { pfd.close() } catch (_: Exception) {}
            }
            return
        }

        // 3. Fallback stream with Range Support
        val inStream = storageBridge.openTrackInputStream(track)
        if (inStream == null) {
            sendJsonError(output, 500, "Unable to read audio file")
            return
        }

        try {
            val mimeType = when (track.format.lowercase()) {
                "flac" -> "audio/flac"
                "m4a", "alac" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg", "opus" -> "audio/ogg"
                "aac" -> "audio/aac"
                else -> "audio/mp4"
            }

            val totalBytes = if (track.sizeBytes > 0) track.sizeBytes else inStream.available().toLong()
            val rangeHeader = headers["range"]

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeVal = rangeHeader.substringAfter("bytes=").trim()
                val dashIdx = rangeVal.indexOf('-')
                var start: Long = 0L
                var end: Long = if (totalBytes > 0) totalBytes - 1 else Long.MAX_VALUE

                if (dashIdx == 0) {
                    val suffixLen = rangeVal.substring(1).toLongOrNull() ?: 0L
                    if (totalBytes > 0) {
                        start = maxOf(0L, totalBytes - suffixLen)
                        end = totalBytes - 1
                    }
                } else if (dashIdx > 0) {
                    val firstPart = rangeVal.substring(0, dashIdx).trim()
                    val secondPart = rangeVal.substring(dashIdx + 1).trim()
                    start = firstPart.toLongOrNull() ?: 0L
                    if (secondPart.isNotEmpty()) {
                        end = secondPart.toLongOrNull() ?: (if (totalBytes > 0) totalBytes - 1 else Long.MAX_VALUE)
                    } else if (totalBytes > 0) {
                        end = totalBytes - 1
                    }
                }

                if (totalBytes > 0 && start >= totalBytes) {
                    val header416 = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */$totalBytes\r\n" +
                            "Content-Length: 0\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header416.toByteArray(Charsets.UTF_8))
                    return
                }

                if (totalBytes > 0) {
                    end = minOf(end, totalBytes - 1)
                }
                val contentLength = if (end >= start) (end - start + 1) else 0L

                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                headerBuilder.append("Content-Type: $mimeType\r\n")
                headerBuilder.append("Content-Length: $contentLength\r\n")
                headerBuilder.append("Content-Range: bytes $start-$end/${if (totalBytes > 0) totalBytes.toString() else "*"}\r\n")
                headerBuilder.append("Accept-Ranges: bytes\r\n")
                headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                headerBuilder.append("Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n")
                headerBuilder.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                headerBuilder.append("Cache-Control: public, max-age=3600\r\n")
                headerBuilder.append("Connection: close\r\n\r\n")
                output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))

                skipFully(inStream, start)

                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = inStream.read(buffer, 0, toRead)
                    if (read <= 0) break
                    try {
                        output.write(buffer, 0, read)
                    } catch (_: java.io.IOException) {
                        break
                    }
                    remaining -= read
                }
            } else {
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
                headerBuilder.append("Content-Type: $mimeType\r\n")
                if (totalBytes > 0) {
                    headerBuilder.append("Content-Length: $totalBytes\r\n")
                }
                headerBuilder.append("Accept-Ranges: bytes\r\n")
                headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                headerBuilder.append("Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n")
                headerBuilder.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                headerBuilder.append("Cache-Control: public, max-age=3600\r\n")
                headerBuilder.append("Connection: close\r\n\r\n")
                output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))

                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (inStream.read(buffer).also { read = it } > 0) {
                    try {
                        output.write(buffer, 0, read)
                    } catch (_: java.io.IOException) {
                        break
                    }
                }
            }
        } finally {
            try { inStream.close() } catch (_: Exception) {}
        }
    }

    private fun skipFully(inStream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buf = ByteArray(minOf(remaining, 32768L).toInt())
        while (remaining > 0) {
            val skipped = inStream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = inStream.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private fun handleAlbumCover(trackId: String, isThumb: Boolean, output: OutputStream) {
        val track = storageBridge.getTrackById(trackId)
        val art = if (track != null) {
            storageBridge.getAlbumArtThumbnail(track, if (isThumb) 80 else 240)
        } else null

        if (art != null && art.isNotEmpty()) {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${art.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: public, max-age=604800, immutable\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(art)
        } else {
            sendResponse(output, 404, "Not Found", "text/plain", "No cover art".toByteArray())
        }
    }

    private fun handleDeleteTrack(trackId: String, output: OutputStream) {
        val deleted = kotlinx.coroutines.runBlocking {
            storageBridge.deleteTrack(trackId)
        }
        val json = JSONObject().apply {
            put("success", deleted)
            if (!deleted) {
                put("error", "Could not delete file from device storage")
            }
        }
        sendJsonResponse(output, if (deleted) 200 else 400, json.toString())
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        sendResponse(output, statusCode, if (statusCode == 200) "OK" else "Error", "application/json; charset=UTF-8", bytes)
    }

    private fun sendJsonError(output: OutputStream, statusCode: Int, message: String) {
        val json = JSONObject().apply {
            put("success", false)
            put("error", message)
        }
        sendJsonResponse(output, statusCode, json.toString())
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        data: ByteArray
    ) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: public, max-age=60\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(data)
    }

    private fun sendCorsHeaders(output: OutputStream, statusCode: Int, statusText: String) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, DELETE, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n" +
                "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isBlank()) return params
        for (pair in query.split("&")) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                try {
                    val key = URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8")
                    params[key] = value
                } catch (_: Exception) {}
            }
        }
        return params
    }
}
