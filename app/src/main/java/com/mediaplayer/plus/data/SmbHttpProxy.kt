package com.mediaplayer.plus.data

import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import jcifs.smb.SmbRandomAccessFile

/**
 * 本地 HTTP 代理服务器 — 将 SMB 文件流式传输给播放器
 * 增强版：支持完整的 Range 请求，支持高效 Seek，适配元数据解析
 */
class SmbHttpProxy(private val smbManager: SmbManager) {

    companion object {
        private const val TAG = "SmbHttpProxy"
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var port: Int = 0

    private val streamSources = ConcurrentHashMap<String, SmbStreamSource>()

    data class SmbStreamSource(
        val server: SmbServer,
        val smbPath: String,
        val fileName: String,
        val size: Long
    )

    fun start(): Int {
        if (running.get()) return port
        serverSocket = ServerSocket(0)
        port = serverSocket!!.localPort
        running.set(true)
        thread(name = "SmbHttpProxy", isDaemon = true) {
            Log.d(TAG, "HTTP proxy started on port $port")
            while (running.get()) {
                try {
                    val client = serverSocket!!.accept()
                    thread(name = "SmbHttpProxy-Client", isDaemon = true) {
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Accept error", e)
                }
            }
        }
        return port
    }

    fun registerStream(server: SmbServer, smbPath: String, fileName: String, size: Long): String {
        val id = java.util.UUID.randomUUID().toString().take(8)
        streamSources[id] = SmbStreamSource(server, smbPath, fileName, size)
        val encodedName = URLEncoder.encode(fileName, "UTF-8")
        return "http://127.0.0.1:$port/stream/$id/$encodedName"
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        streamSources.clear()
        smbManager.clearContextCache()
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 30000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                // 🌟 支持完整 Range 解析 (bytes=start-end)
                var rangeStart = 0L
                var rangeEnd = -1L
                var hasRange = false
                var headerLine: String?
                while (readLine(input).also { headerLine = it }.let { it != null && it.isNotEmpty() }) {
                    if (headerLine != null && headerLine.startsWith("Range:", ignoreCase = true)) {
                        hasRange = true
                        val rangeVal = headerLine.substringAfter("bytes=").trim()
                        val rangeParts = rangeVal.split("-")
                        rangeStart = rangeParts[0].toLongOrNull() ?: 0L
                        if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                            rangeEnd = rangeParts[1].toLongOrNull() ?: -1L
                        }
                    }
                }

                val pathSegments = path.split("/").filter { it.isNotEmpty() }
                if (pathSegments.size < 2 || pathSegments[0] != "stream") {
                    writeResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                    return
                }
                val streamId = pathSegments[1]
                val source = streamSources[streamId]
                if (source == null) {
                    writeResponse(output, 404, "Not Found", "text/plain", "Stream not found".toByteArray())
                    return
                }

                // 🌟 使用 SmbRandomAccessFile 实现物理 Seek，不再使用低效的 skip()
                // 这对 MediaMetadataRetriever 解析元数据（通常需要读文件尾部）至关重要
                try {
                    val ctx = smbManager.createContextForStream(source.server)
                    val smbFile = jcifs.smb.SmbFile(source.smbPath, ctx)
                    // 优先使用注册时的大小，若为0则从SmbFile获取实际大小
                    var totalSize = source.size
                    if (totalSize <= 0) {
                        totalSize = smbFile.length()
                        Log.d(TAG, "Registered size was 0, got actual size from SmbFile: $totalSize for ${source.fileName}")
                    }
                    if (totalSize <= 0) {
                        Log.e(TAG, "SMB file size is 0 or unknown: ${source.smbPath}")
                        writeResponse(output, 500, "Internal Error", "text/plain", "File size is 0 or unknown".toByteArray())
                        return
                    }
                    
                    SmbRandomAccessFile(smbFile, "r").use { raf ->
                        if (rangeStart > 0) {
                            raf.seek(rangeStart)
                        }
                        
                        val effectiveEnd = if (rangeEnd != -1L && rangeEnd < totalSize) rangeEnd else totalSize - 1
                        val contentLength = effectiveEnd - rangeStart + 1

                        val statusLine = if (hasRange) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                        val mimeType = getMimeType(source.fileName)
                        val headers = buildString {
                            append("$statusLine\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            append("Content-Type: $mimeType\r\n")
                            append("Content-Length: $contentLength\r\n")
                            if (hasRange) {
                                append("Content-Range: bytes $rangeStart-$effectiveEnd/$totalSize\r\n")
                            }
                            append("Connection: close\r\n")
                            append("\r\n")
                        }
                        output.write(headers.toByteArray())
                        output.flush()

                        // 流式传输数据：使用 128KB 缓冲区平衡内存与速度
                        val buffer = ByteArray(128 * 1024)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                        output.flush()
                        Log.d(TAG, "Streamed $contentLength bytes for ${source.fileName} (Range: $rangeStart-$effectiveEnd)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SMB Proxy Error: ${source.smbPath}", e)
                    writeResponse(output, 500, "Internal Error", "text/plain", "Error: ${e.message}".toByteArray())
                }
            }
        } catch (e: SocketException) {
            // Ignore reset
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".mp3", true) -> "audio/mpeg"
            fileName.endsWith(".wav", true) -> "audio/x-wav"
            fileName.endsWith(".flac", true) -> "audio/flac"
            fileName.endsWith(".ogg", true) -> "audio/ogg"
            fileName.endsWith(".aac", true) -> "audio/aac"
            fileName.endsWith(".m4a", true) -> "audio/mp4"
            fileName.endsWith(".wma", true) -> "audio/x-ms-wma"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mkv", true) -> "video/x-matroska"
            fileName.endsWith(".avi", true) -> "video/x-msvideo"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            fileName.endsWith(".webm", true) -> "video/webm"
            fileName.endsWith(".flv", true) -> "video/x-flv"
            fileName.endsWith(".ts", true) -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }

    private fun writeResponse(output: java.io.OutputStream, code: Int, status: String, contentType: String, body: ByteArray) {
        val response = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            prev = c
        }
    }
}
