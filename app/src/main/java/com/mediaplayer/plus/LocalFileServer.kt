package com.mediaplayer.plus

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * DLNA 专辑图缓存 + 本地 HTTP 服务路由。
 *
 * 流程：
 * 1. ExoPlayer 提取内嵌专辑图 byte[] → AlbumArtRegistry.register()
 * 2. 构建 albumArtURL = http://{localIp}:{port}/albumart/{mediaId}
 * 3. 通过 UPnP SOAP SetAVTransportURI 将 albumArtURL 推送给 DMR
 * 4. DMR 拉取图片，LocalFileServer 返回 byte[]
 */
object AlbumArtRegistry {
    private const val TAG = "AlbumArtRegistry"

    /** 最大缓存 5 张，防止 OOM */
    private const val MAX_CACHE = 5
    private const val ALBUM_ART_ENDPOINT = "/albumart"

    /** 最近 5 个 mediaId，用于缓存回收 */
    private val recentMediaIds = ConcurrentLinkedDeque<String>()
    /** mediaId -> byte[] 的映射（NanoHTTPD 会跨线程访问，必须线程安全） */
    @Volatile
    private var cache: MutableMap<String, ByteArray> = mutableMapOf()
    @Volatile
    private var mediaIdToMime: MutableMap<String, String> = mutableMapOf()

    /**
     * 注册一张专辑图，返回对应的本地 HTTP URL（null 表示无法暴露）
     */
    fun register(mediaId: String, artworkBytes: ByteArray, localIp: String): String? {
        synchronized(cache) {
            recentMediaIds.remove(mediaId)
            recentMediaIds.addLast(mediaId)

            // 超出最大缓存时淘汰最老的
            if (recentMediaIds.size > MAX_CACHE) {
                val oldest = recentMediaIds.removeFirst()
                cache.remove(oldest)
                mediaIdToMime.remove(oldest)
            }

            val mime = detectMimeType(artworkBytes)
            cache[mediaId] = artworkBytes.copyOf() // 防外部修改
            mediaIdToMime[mediaId] = mime
            Log.d(TAG, "Registered album art: $mediaId (${artworkBytes.size} bytes, $mime)")
        }
        return "http://$localIp:8088$ALBUM_ART_ENDPOINT/$mediaId"
    }

    /**
     * 注销指定专辑图
     */
    fun unregister(mediaId: String) {
        synchronized(cache) {
            recentMediaIds.remove(mediaId)
            cache.remove(mediaId)
            mediaIdToMime.remove(mediaId)
        }
    }

    /**
     * 清理全部缓存
     */
    fun clear() {
        synchronized(cache) {
            cache.clear()
            mediaIdToMime.clear()
            recentMediaIds.clear()
        }
    }

    /**
     * 供 LocalFileServer 调用的同步方法（NanoHTTPD 在后台线程执行 serve()）
     */
    fun getArtworkBytes(mediaId: String): ByteArray? {
        synchronized(cache) {
            return cache[mediaId]?.copyOf()
        }
    }

    fun getMimeType(mediaId: String): String? {
        synchronized(cache) {
            return mediaIdToMime[mediaId]
        }
    }

    fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0].toInt() == 0xFF && bytes[1].toInt() == 0xD8 && bytes[2].toInt() == 0xFF) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0].toInt() == 0x89 && bytes[1].toInt() == 0x50 && bytes[2].toInt() == 0x4E && bytes[3].toInt() == 0x47) {
            return "image/png"
        }
        return "image/jpeg" // 兜底
    }
}

class LocalFileServer(port: Int) : NanoHTTPD(port) {
    private companion object {
        private const val TAG = "LocalFileServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return if (session.method == Method.OPTIONS) {
            val opt = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            opt.addHeader("Access-Control-Allow-Origin", "*")
            opt.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            opt.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Accept-Ranges")
            opt.addHeader("Access-Control-Max-Age", "86400")
            opt
        } else if (uri == "/file" || uri.startsWith("/file/")) {
            val params = session.parms
            // 优先从 path 参数获取完整路径，兜底用 URI 路径
            val filePath: String? = params["path"]
                ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
                ?: (if (uri != "/file") URLDecoder.decode(uri.substringAfterLast("/"), Charsets.UTF_8) else null)
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    val mimeType = getMimeType(file.name)
                    try {
                        val headers = session.headers
                        val rangeHeader = headers["range"] ?: headers["Range"]
                        val response = if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                            getPartialResponse(file, mimeType, rangeHeader)
                        } else {
                            getFullResponse(file, mimeType)
                        }
                        response.addHeader("Content-Type", mimeType)
                        response.addHeader("Accept-Ranges", "bytes")
                        response.addHeader("X-Content-Duration", (file.length() / 1_000_000).toString())
                        response.addHeader("X-Content-Length", file.length().toString())
                        response.addHeader("Cache-Control", "no-cache")
                        response.addHeader("Access-Control-Allow-Origin", "*")
                        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Accept-Ranges")
                        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
                        response.addHeader("transferMode.dlna.org", "Streaming")
                        val suffix = if (file.name.endsWith(".mp4", true)) "MP4"
                            else if (file.name.endsWith(".mkv", true)) "MKV"
                            else if (file.name.endsWith(".mp3", true)) "MP3"
                            else "UNKNOWN"
                        response.addHeader("contentFeatures.dlna.org",
                            "DLNA.ORG_PN=$suffix;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                        response
                    } catch (e: Exception) {
                        Log.e(TAG, "Error serving file: ${file.absolutePath}", e)
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Not Found")
                }
            } else {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing 'path' parameter")
            }
        } else if (uri.startsWith("/albumart/", ignoreCase = false)) {
            val mediaId = uri.substringAfter("/albumart/")
            val bytes = AlbumArtRegistry.getArtworkBytes(mediaId)
            if (bytes != null) {
                val mime = AlbumArtRegistry.getMimeType(mediaId) ?: "image/jpeg"
                val response = newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
                response.addHeader("Cache-Control", "no-cache")
                response.addHeader("Access-Control-Allow-Origin", "*")
                Log.d(TAG, "Served album art: $mediaId (${bytes.size} bytes, $mime)")
                response
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Album art not found: $mediaId")
            }
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun getFullResponse(file: File, mimeType: String): Response {
        val fileStream = file.inputStream()
        val response = newChunkedResponse(Response.Status.OK, mimeType, fileStream)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("X-Content-Duration", (file.length() / 1_000_000).toString())
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("transferMode.dlna.org", "Streaming")
        val suffix = if (file.name.endsWith(".mp4", true)) "MP4"
            else if (file.name.endsWith(".mkv", true)) "MKV"
            else if (file.name.endsWith(".mp3", true)) "MP3"
            else "UNKNOWN"
        response.addHeader("contentFeatures.dlna.org",
            "DLNA.ORG_PN=$suffix;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        Log.d(TAG, "Full response (chunked): ${file.name} (${file.length()} bytes)")
        return response
    }

    private fun getPartialResponse(file: File, mimeType: String, rangeHeader: String): Response {
        val fileLength = file.length()
        val rangeValue = rangeHeader.substringAfter("bytes=", "").trim()

        var start: Long = 0
        var end: Long = fileLength - 1

        try {
            if (rangeValue.startsWith("-")) {
                start = fileLength - rangeValue.substringAfter("-").toLong()
            } else {
                val parts = rangeValue.split("-")
                start = parts[0].toLong()
                if (parts.size > 1 && parts[1].isNotEmpty()) {
                    end = parts[1].toLong()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing range header: $rangeHeader", e)
        }

        if (start > end || start >= fileLength) {
            val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
            res.addHeader("Content-Range", "bytes */$fileLength")
            return res
        }

        if (end >= fileLength) end = fileLength - 1

        val contentLength = end - start + 1
        val fileStream = file.inputStream().apply { skip(start) }

        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fileStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
        response.addHeader("Accept-Ranges", "bytes")
        Log.d(TAG, "Partial response (206): bytes $start-$end/$fileLength, file: ${file.name}")
        return response
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
            else -> "application/octet-stream"
        }
    }
}