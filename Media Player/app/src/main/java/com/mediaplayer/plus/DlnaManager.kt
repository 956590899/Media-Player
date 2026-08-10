package com.mediaplayer.plus

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.yinnho.upnpcast.DLNACast
import fi.iki.elonen.NanoHTTPD
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder

object DlnaManager {

    private const val TAG = "DlnaManager"
    private const val PROGRESS_POLLING_INTERVAL_MS = 1500L
    private const val PROGRESS_POLLING_ERROR_DELAY_MS = 2000L
    private const val MAX_PROGRESS_POLLING_FAILURES = 5
    private const val SERVER_PORT = 8088

    data class DlnaDevice(
        val id: String,
        val name: String,
        val address: String,
        val isTV: Boolean
    )

    private val _devices = mutableStateListOf<DlnaDevice>()
    private val _devicesFlow = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devicesFlow: StateFlow<List<DlnaDevice>> = _devicesFlow.asStateFlow()
    val devices: List<DlnaDevice> get() = _devices.toList()

    enum class CastStatus { IDLE, SEARCHING, CASTING, PLAYING, PAUSED, STOPPED, ERROR }
    private val _castStatus = MutableStateFlow(CastStatus.IDLE)
    val castStatus: StateFlow<CastStatus> = _castStatus.asStateFlow()

    private val _castUrl = MutableStateFlow("")
    val castUrl: StateFlow<String> = _castUrl.asStateFlow()

    private val _progress = MutableStateFlow(0L to 0L)
    val progress: StateFlow<Pair<Long, Long>> = _progress.asStateFlow()

    private val _volume = MutableStateFlow(-1 to false)
    val volume: StateFlow<Pair<Int, Boolean>> = _volume.asStateFlow()

    var onTrackEnded: (() -> Unit)? = null
    private var nullProgressCount = 0
    private var lastProgressMs = 0L
    private var lastDurationMs = 0L
    private var stuckProgressCount = 0

    private var initialized = false
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var searchJob: Job? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var applicationContext: Context
    private var fileServer: LocalFileServer? = null

    val currentHostIp: String by lazy { getLocalIpAddress(applicationContext) }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        this.applicationContext = context.applicationContext

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("MediaPlayerPlus_DLNA_Lock")?.apply {
            setReferenceCounted(true)
        }

        applicationScope.launch {
            DLNACast.init(applicationContext)
            Log.d(TAG, "UPnPCast initialized, host IP: $currentHostIp")

            try {
                // 抑制 NanoHTTPD 的 ConnectionResetException 刷屏（电视缓冲大文件时会频繁断开重连，属正常行为）
                java.util.logging.Logger.getLogger("fi.iki.elonen.NanoHTTPD").level =
                    java.util.logging.Level.OFF
                fileServer = LocalFileServer(SERVER_PORT).apply {
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                Log.d(TAG, "LocalFileServer started on port $SERVER_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LocalFileServer", e)
            }
        }
    }

    fun searchDevices(timeout: Long = 6000) {
        if (!initialized) {
            Log.e(TAG, "DlnaManager not initialized. Call init() first.")
            return
        }
        if (_castStatus.value == CastStatus.SEARCHING) return

        _castStatus.value = CastStatus.SEARCHING
        _devices.clear()
        _devicesFlow.value = emptyList()

        try {
            multicastLock?.acquire()
            Log.d(TAG, "MulticastLock acquired.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }

        searchJob?.cancel()
        searchJob = applicationScope.launch(Dispatchers.IO) {
            try {
                DLNACast.init(applicationContext)
                Log.d(TAG, "DLNACast re-initialized before search")
                Log.d(TAG, "Starting DLNA device search with timeout: $timeout ms")
                val found = DLNACast.search(timeout)
                val mapped = found.map { DlnaDevice(it.id, it.name, it.address, it.isTV) }
                withContext(Dispatchers.Main) {
                    _devices.clear()
                    _devices.addAll(mapped)
                    _devicesFlow.value = mapped
                    _castStatus.value = CastStatus.IDLE
                    Log.d(TAG, "DLNA device search completed. Found ${mapped.size} devices.")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "DLNA device search cancelled.")
                withContext(Dispatchers.Main) { _castStatus.value = CastStatus.IDLE }
            } catch (e: Exception) {
                Log.e(TAG, "Search devices error", e)
                withContext(Dispatchers.Main) { _castStatus.value = CastStatus.ERROR }
            } finally {
                try {
                    if (multicastLock?.isHeld == true) multicastLock?.release()
                    Log.d(TAG, "MulticastLock released.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release multicast lock", e)
                }
            }
        }
    }

    fun castToDevice(device: DlnaDevice, url: String, title: String? = null, startPositionMs: Long = 0L, onCastSuccess: (() -> Unit)? = null) {
        if (!initialized) {
            Log.e(TAG, "DlnaManager not initialized. Call init() first.")
            return
        }
        val castUrl = convertToCastUrl(url)
        if (!castUrl.startsWith("http", ignoreCase = true)) {
            Log.e(TAG, "Invalid URL format for DLNA casting. Must be HTTP/HTTPS: $castUrl")
            _castStatus.value = CastStatus.ERROR
            return
        }

        _castStatus.value = CastStatus.CASTING
        _castUrl.value = castUrl
        applicationScope.launch {
            try {
                val dlnaDevice = DLNACast.Device(device.id, device.name, device.address, device.isTV)
                Log.d(TAG, "Attempting to cast '$castUrl' to device: ${device.name} (${device.address})")
                val success = DLNACast.castToDevice(dlnaDevice, castUrl, title)
                if (success) {
                    _castStatus.value = CastStatus.PLAYING
                    startProgressPolling()
                    DlnaCastService.startForeground(applicationContext)
                    if (startPositionMs > 0) {
                        delay(600)
                        DLNACast.seek(startPositionMs)
                        Log.d(TAG, "DLNA seek after cast to $startPositionMs ms")
                    }
                    withContext(Dispatchers.Main) { onCastSuccess?.invoke() }
                    Log.i(TAG, "Successfully casted '$castUrl' to ${device.name}")
                } else {
                    _castStatus.value = CastStatus.ERROR
                    Log.e(TAG, "Cast failed for URL: $castUrl")
                }
            } catch (e: Exception) {
                Log.e(TAG, "CastToDevice error for URL: $castUrl", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun castToAuto(url: String, title: String? = null, startPositionMs: Long = 0L) {
        if (!initialized) {
            Log.e(TAG, "DlnaManager not initialized. Call init() first.")
            return
        }
        val castUrl = convertToCastUrl(url)
        if (!castUrl.startsWith("http", ignoreCase = true)) {
            Log.e(TAG, "Invalid URL format for DLNA casting. Must be HTTP/HTTPS: $castUrl")
            _castStatus.value = CastStatus.ERROR
            return
        }

        _castStatus.value = CastStatus.CASTING
        _castUrl.value = castUrl
        applicationScope.launch {
            try {
                Log.d(TAG, "Attempting to auto-cast '$castUrl'")
                val success = DLNACast.cast(castUrl, title)
                if (success) {
                    _castStatus.value = CastStatus.PLAYING
                    startProgressPolling()
                    DlnaCastService.startForeground(applicationContext)
                    if (startPositionMs > 0) {
                        delay(600)
                        DLNACast.seek(startPositionMs)
                        Log.d(TAG, "DLNA seek after auto-cast to $startPositionMs ms")
                    }
                    Log.i(TAG, "Successfully auto-casted '$castUrl'")
                } else {
                    _castStatus.value = CastStatus.ERROR
                    Log.e(TAG, "Auto-cast failed for URL: $castUrl")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cast auto error for URL: $castUrl", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun play() {
        if (!initialized) return
        applicationScope.launch {
            try {
                DLNACast.play()
                _castStatus.value = CastStatus.PLAYING
                Log.d(TAG, "DLNA Play command sent.")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending DLNA Play command", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun pause() {
        if (!initialized) return
        applicationScope.launch {
            try {
                DLNACast.pause()
                _castStatus.value = CastStatus.PAUSED
                Log.d(TAG, "DLNA Pause command sent.")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending DLNA Pause command", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun stop() {
        if (!initialized) return
        applicationScope.launch {
            try {
                DLNACast.stop()
                DLNACast.clearProgressCache()
                stopProgressPolling()
                searchJob?.cancel()
                searchJob = null
                _progress.value = 0L to 0L
                DlnaCastService.stopService(applicationContext)
                withContext(Dispatchers.Main) { _castStatus.value = CastStatus.IDLE }
                Log.d(TAG, "DLNA Stop command sent, polling stopped, status reset to IDLE.")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending DLNA Stop command", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun seek(positionMs: Long) {
        if (!initialized) return
        applicationScope.launch {
            try {
                DLNACast.seek(positionMs)
                Log.d(TAG, "DLNA Seek command sent to: $positionMs ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending DLNA Seek command to $positionMs ms", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun setVolume(vol: Int) {
        if (!initialized) return
        applicationScope.launch {
            try {
                val clampedVol = vol.coerceIn(0, 100)
                DLNACast.setVolume(clampedVol)
                Log.d(TAG, "DLNA SetVolume command sent: $clampedVol")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending DLNA SetVolume command to $vol", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun setMute(mute: Boolean) {
        if (!initialized) return
        applicationScope.launch {
            try {
                DLNACast.setMute(mute)
                Log.d(TAG, "DLNA SetMute command sent: $mute")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending DLNA SetMute command", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    /**
     * 通过 UPnP SOAP SetAVTransportURI 将 albumArtURI 推送给 DMR。
     *
     * DLNACast 库不直接支持 albumArtURI，因此需要直接发送 SOAP 请求，
     * 在 DIDL-Lite 元数据中包含 <upnp:albumArtURI> 节点。
     */
    fun setAlbumArtURI(device: DlnaDevice, mediaUrl: String, title: String, albumArtUrl: String, duration: Long = 0L) {
        if (!initialized) return
        applicationScope.launch {
            try {
                val controlUrl = resolveAVTransportControlUrl(device) ?: run {
                    Log.w(TAG, "setAlbumArtURI: cannot resolve AVTransport control URL for ${device.name}")
                    return@launch
                }
                Log.d(TAG, "setAlbumArtURI: controlUrl=$controlUrl, artUrl=$albumArtUrl")

                val durationStr = if (duration > 0) {
                    val h = (duration / 3_600_000).toInt()
                    val m = ((duration % 3_600_000) / 60_000).toInt()
                    val s = ((duration % 60_000) / 1000).toDouble()
                    String.format(Locale.US, "%02d:%02d:%0.0f", h, m, s)
                } else ""

                val metadata = buildAlbumArtMetadata(title, albumArtUrl, mediaUrl, durationStr)
                val soapBody = """
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                        <CurrentURIMetaData><![CDATA[$metadata]]></CurrentURIMetaData>
                    </u:SetAVTransportURI>
                """.trimIndent()

                Log.d(TAG, "setAlbumArtURI SOAP body:\n$soapBody")

                // 先等 1.5s 让设备稳定当前播放，再发元数据更新
                delay(1500)

                var success = false
                var attempt = 0
                while (!success && attempt < 3) {
                    attempt++
                    try {
                        val response = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
                        if (response == 200) {
                            Log.i(TAG, "setAlbumArtURI SUCCESS on attempt $attempt (artUrl: $albumArtUrl)")
                            success = true
                        } else {
                            Log.w(TAG, "setAlbumArtURI HTTP $response on attempt $attempt")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "setAlbumArtURI exception on attempt $attempt: ${e.message}")
                    }
                    if (!success) delay(2000)
                }
                if (!success) Log.e(TAG, "setAlbumArtURI FAILED after 3 attempts for ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending setAlbumArtURI", e)
            }
        }
    }

    /**
     * 从设备描述 XML 中解析 AVTransport 服务的 controlURL。
     */
    private suspend fun resolveAVTransportControlUrl(device: DlnaDevice): String? = withContext(Dispatchers.IO) {
        try {
            val descriptionUrl = "http://${device.address}:80/description.xml"
            val conn = java.net.URL(descriptionUrl).openConnection() as? java.net.HttpURLConnection ?: return@withContext null
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            // 查找 AVTransport 服务的 controlURL
            val serviceRegex = "<service>.*?<serviceType>[^<]*AVTransport[^<]*</serviceType>.*?<controlURL>(.*?)</controlURL>.*?</service>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val match = serviceRegex.find(xml)
            val controlUrl = match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            if (controlUrl.isNullOrBlank()) return@withContext null

            if (controlUrl.startsWith("http")) return@withContext controlUrl
            // 拼接 base URL
            val baseUrl = "http://${device.address}:80"
            if (controlUrl.startsWith("/")) "$baseUrl$controlUrl" else "$baseUrl/$controlUrl"
        } catch (e: Exception) {
            Log.e(TAG, "resolveAVTransportControlUrl failed for ${device.name}", e)
            null
        }
    }

    private fun buildAlbumArtMetadata(title: String, albumArtUrl: String, mediaUrl: String, duration: String): String {
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
    <item id="1" parentID="0" restricted="1">
        <dc:title>${escapeXmlAttr(title)}</dc:title>
        <upnp:artist>MediaPlayer</upnp:artist>
        <upnp:albumArtURI>${albumArtUrl}</upnp:albumArtURI>
        <res duration="$duration" protocolInfo="http-get:*:audio/mpeg:*">${escapeXml(mediaUrl)}</res>
    </item></DIDL-Lite>""".trimIndent()
    }

    private fun escapeXml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun escapeXmlAttr(s: String): String = escapeXml(s).replace("'", "&apos;")

    private suspend fun sendSoapRequest(controlUrl: String, soapAction: String, body: String): Int = withContext(Dispatchers.IO) {
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = java.net.URL(controlUrl).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", "\"$soapAction\"")
            conn.setRequestProperty("User-Agent", "MediaPlayerPlus/1.0")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 8000

            val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n<s:Body>\n$body\n</s:Body>\n</s:Envelope>"
            conn.outputStream.bufferedWriter().use { it.write(envelope); it.flush() }
            conn.responseCode
        } catch (e: Exception) {
            Log.e(TAG, "SOAP request failed: ${e.message}")
            -1
        } finally {
            conn?.disconnect()
        }
    }

    fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
        Log.d(TAG, "Progress polling stopped.")
    }

    fun cleanup() {
        stopProgressPolling()
        searchJob?.cancel()
        applicationScope.coroutineContext.cancelChildren()

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            Log.d(TAG, "MulticastLock released during cleanup.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock during cleanup", e)
        }
        multicastLock = null

        try {
            fileServer?.stop()
            fileServer = null
            Log.d(TAG, "LocalFileServer stopped during cleanup.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop LocalFileServer during cleanup", e)
        }

        initialized = false
        _devices.clear()
        _devicesFlow.value = emptyList()
        _castStatus.value = CastStatus.IDLE
        _castUrl.value = ""
        _progress.value = 0L to 0L
        _volume.value = -1 to false
        DLNACast.cleanup()
        Log.d(TAG, "DlnaManager cleaned up all resources.")
    }

    fun convertToCastUrl(path: String): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        if (path.startsWith("content://")) {
            return path
        }
        return try {
            val file = java.io.File(path)
            val encodedFileName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            val encodedFullPath = URLEncoder.encode(path, "UTF-8")
            "http://$currentHostIp:$SERVER_PORT/file/$encodedFileName?path=$encodedFullPath"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode path: $path", e)
            path
        }
    }

    private val progressPollingScope = CoroutineScope(
        SupervisorJob() +
            Executors.newSingleThreadExecutor { runnable ->
                val thread = Thread(runnable, "DlnaProgressPolling")
                thread.isDaemon = false
                thread
            }.asCoroutineDispatcher()
    )

    private fun startProgressPolling() {
        stopProgressPolling()
        nullProgressCount = 0
        lastProgressMs = 0L
        Log.d(TAG, "startProgressPolling: launching polling job, castStatus=${_castStatus.value}")
        progressJob = progressPollingScope.launch {
            Log.d(TAG, "startProgressPolling: job started, castStatus=${_castStatus.value}")
            var failureCount = 0
            var nearEndCount = 0
            var consecutiveSame = 0
            var iteration = 0
            while (progressPollingScope.isActive && (_castStatus.value == CastStatus.PLAYING || _castStatus.value == CastStatus.PAUSED)) {
                try {
                    iteration++
                    Log.d(TAG, "poll #$iteration start")

                    // 直接在协程中调用 DLNACast 的 suspend 函数，用 withTimeoutOrNull 保护
                    val result: Pair<Long, Long>? = withTimeoutOrNull(2000) {
                        DLNACast.getProgress()
                    }
                    Log.d(TAG, "poll #$iteration getProgress returned: ${result?.first} / ${result?.second}")

                    if (result != null) {
                        _progress.value = result
                        failureCount = 0
                        nullProgressCount = 0
                        val pos = result.first
                        val dur = result.second
                        Log.d(TAG, "progress poll: pos=$pos dur=$dur")
                        if (pos == lastProgressMs) {
                            consecutiveSame++
                            Log.d(TAG, "  stuck consecutiveSame=$consecutiveSame")
                        } else {
                            consecutiveSame = 0
                        }
                        lastProgressMs = pos
                        if (dur > 0 && _castStatus.value == CastStatus.PLAYING && pos >= dur - 2000) {
                            nearEndCount++
                            if (nearEndCount >= 2) {
                                Log.d(TAG, "Track ended by duration (pos=$pos dur=$dur). onTrackEnded.")
                                withContext(Dispatchers.Main) { onTrackEnded?.invoke() }
                                break
                            }
                        } else {
                            nearEndCount = 0
                        }
                        if (consecutiveSame >= 4 && _castStatus.value == CastStatus.PLAYING) {
                            Log.d(TAG, "Track ended by stuck progress (pos=$pos stuck=$consecutiveSame). onTrackEnded.")
                            withContext(Dispatchers.Main) { onTrackEnded?.invoke() }
                            break
                        }
                    } else {
                        if (_castStatus.value == CastStatus.PLAYING) {
                            nullProgressCount++
                            if (nullProgressCount >= 5) {
                                Log.d(TAG, "Track ended by null progress x5. onTrackEnded.")
                                withContext(Dispatchers.Main) { onTrackEnded?.invoke() }
                                break
                            }
                        }
                        failureCount++
                        Log.w(TAG, "getProgress null/timeout. nullCount=$nullProgressCount failure=$failureCount")
                    }

                    val vol: Pair<Int?, Boolean?>? = withTimeoutOrNull(1000) {
                        DLNACast.getVolume()
                    }
                    if (vol != null) {
                        _volume.value = (vol.first ?: -1) to (vol.second ?: false)
                    }

                    if (failureCount >= MAX_PROGRESS_POLLING_FAILURES) {
                        Log.e(TAG, "Polling failureCount=$failureCount, stopping.")
                        withContext(Dispatchers.Main) {
                            _castStatus.value = CastStatus.ERROR
                            stop()
                        }
                        break
                    }
                    delay(PROGRESS_POLLING_INTERVAL_MS)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Progress polling job cancelled.")
                    break
                } catch (e: Exception) {
                    failureCount++
                    Log.w(TAG, "Progress polling exception: ${e.message}. failure=$failureCount")
                    delay(PROGRESS_POLLING_ERROR_DELAY_MS)
                    if (failureCount >= MAX_PROGRESS_POLLING_FAILURES) {
                        Log.e(TAG, "Polling exception count=$failureCount, stopping.")
                        withContext(Dispatchers.Main) {
                            _castStatus.value = CastStatus.ERROR
                            stop()
                        }
                        break
                    }
                }
            }
            Log.d(TAG, "Progress polling loop exited.")
        }
    }

    private fun getLocalIpAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.connectionInfo?.ipAddress?.let { ip ->
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
            }
        }

        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isUp && !iface.isLoopback && !iface.displayName.contains("p2p", ignoreCase = true)) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is InetAddress && !addr.isLinkLocalAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address via NetworkInterface: ${e.message}")
        }
        Log.w(TAG, "Failed to get local IP address. Returning fallback IP.")
        return "0.0.0.0"
    }
}