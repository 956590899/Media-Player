package com.mediaplayer.plus

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
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
    private const val MAX_PROGRESS_POLLING_FAILURES = 10
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

    // 缓存当前投屏的元数据，用于后台同步到 MusicService 通知栏
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbumArtUrl: String? = null
    private var currentDuration: Long = 0L
    private var currentIsVideo: Boolean = false

    private val _volume = MutableStateFlow(-1 to false)
    val volume: StateFlow<Pair<Int, Boolean>> = _volume.asStateFlow()

    var onTrackEnded: (() -> Unit)? = null
    private var nullProgressCount = 0
    private var lastProgressMs = 0L
    private var customControlUrl: String? = null  // 自定义 SOAP cast 的控制 URL，用于进度轮询

    // 进度轮询插值变量（对象级，供 seek() 和轮询循环共享）
    @Volatile private var pollingLastKnownPosMs = 0L
    @Volatile private var pollingLastKnownTimeMs = System.currentTimeMillis()
    @Volatile private var pollingLastKnownDurMs = 0L
    @Volatile private var pollingLastSoapTime = 0L
    @Volatile private var seekLockUntil = 0L
    @Volatile private var seekTargetPos = 0L

    private var initialized = false
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var searchJob: Job? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var applicationContext: Context
    private var fileServer: LocalFileServer? = null

    val currentHostIp: String get() = getLocalIpAddress(applicationContext)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        this.applicationContext = context.applicationContext

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("MediaPlayerPlus_DLNA_MulticastLock")?.apply {
            setReferenceCounted(true)
        }
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MediaPlayerPlus_DLNA_WifiLock")?.apply {
            setReferenceCounted(false)
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
                val ctrl = customControlUrl
                if (ctrl != null) {
                    sendSoapRequest(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>")
                } else {
                    DLNACast.play()
                }
                _castStatus.value = CastStatus.PLAYING
                // 同步状态到 MusicService
                MusicServiceManager.update(applicationContext, currentTitle, currentArtist, true, currentAlbumArtUrl, _progress.value.first, currentDuration)
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
                val ctrl = customControlUrl
                if (ctrl != null) {
                    sendSoapRequest(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Pause", "<u:Pause xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:Pause>")
                } else {
                    DLNACast.pause()
                }
                _castStatus.value = CastStatus.PAUSED
                // 同动状态到 MusicService
                MusicServiceManager.update(applicationContext, currentTitle, currentArtist, false, currentAlbumArtUrl, _progress.value.first, currentDuration)
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
                val ctrl = customControlUrl
                if (ctrl != null) {
                    sendSoapRequest(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Stop", "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:Stop>")
                } else {
                    DLNACast.stop()
                    DLNACast.clearProgressCache()
                }
                stopProgressPolling()
                releaseWakeLock()  // 彻底停止投屏时才释放 WakeLock
                customControlUrl = null
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
                val ctrl = customControlUrl
                if (ctrl != null) {
                    val seekTime = formatTime(positionMs)
                    sendSoapRequest(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Seek", "<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$seekTime</Target></u:Seek>")
                    // 锁定 3 秒：从 seek 位置开始本地插值，忽略 TV 的旧进度
                    pollingLastKnownPosMs = positionMs
                    pollingLastKnownTimeMs = System.currentTimeMillis()
                    seekLockUntil = pollingLastKnownTimeMs + 3000
                    seekTargetPos = positionMs
                } else {
                    DLNACast.seek(positionMs)
                }
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
                    String.format(Locale.US, "%02d:%02d:%02.0f", h, m, s)
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
     * 一次性 SOAP 投屏，无需二次 SetAVTransportURI。
     * 在 SetAVTransportURI 中直接包含完整 DIDL-Lite 元数据（专辑图、歌手、专辑名等），
     * 然后发送 Play，进度轮询使用 SOAP GetPositionInfo 而非 DLNACast 库。
     */
    fun castToDeviceWithCover(
        device: DlnaDevice,
        url: String,
        title: String,
        albumArtUrl: String? = null,
        artist: String? = null,
        album: String? = null,
        duration: Long = 0L,
        startPositionMs: Long = 0L,
        isVideo: Boolean = false,
        onCastSuccess: (() -> Unit)? = null
    ) {
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

        // 切歌时保持当前播放状态，不经过 CASTING，避免播放/暂停图标闪烁
        if (_castStatus.value != CastStatus.PLAYING && _castStatus.value != CastStatus.PAUSED) {
            _castStatus.value = CastStatus.CASTING
        }
        _castUrl.value = castUrl
        currentTitle = title
        currentArtist = artist ?: ""
        currentAlbumArtUrl = albumArtUrl
        currentDuration = duration
        currentIsVideo = isVideo

        applicationScope.launch {
            try {
                val controlUrl = resolveAVTransportControlUrl(device) ?: run {
                    Log.e(TAG, "castToDeviceWithCover: cannot resolve AVTransport control URL for ${device.name}")
                    _castStatus.value = CastStatus.ERROR
                    return@launch
                }
                customControlUrl = controlUrl
                Log.d(TAG, "castToDeviceWithCover: controlUrl=$controlUrl, artUrl=$albumArtUrl")

                val durationStr = if (duration > 0) {
                    val h = (duration / 3_600_000).toInt()
                    val m = ((duration % 3_600_000) / 60_000).toInt()
                    val s = ((duration % 60_000) / 1000).toDouble()
                    String.format(Locale.US, "%02d:%02d:%02.0f", h, m, s)
                } else ""

                val metadata = buildFullDlnaMetadata(title, castUrl, albumArtUrl, artist, album, durationStr, isVideo)
                val soapBody = """
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>${escapeXml(castUrl)}</CurrentURI>
                        <CurrentURIMetaData><![CDATA[$metadata]]></CurrentURIMetaData>
                    </u:SetAVTransportURI>
                """.trimIndent()

                Log.d(TAG, "castToDeviceWithCover SOAP body:\n$soapBody")

                // 发送 SetAVTransportURI
                val response = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
                if (response != 200) {
                    Log.e(TAG, "castToDeviceWithCover: SetAVTransportURI failed HTTP $response")
                    _castStatus.value = CastStatus.ERROR
                    return@launch
                }
                Log.d(TAG, "castToDeviceWithCover: SetAVTransportURI OK")

                // 发送 Play
                val playBody = """
                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Speed>1</Speed>
                    </u:Play>
                """.trimIndent()
                val playResponse = sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", playBody)
                if (playResponse != 200) {
                    Log.w(TAG, "castToDeviceWithCover: Play returned HTTP $playResponse, continuing anyway")
                }

                // 如果有 seek 位置，发送 Seek
                if (startPositionMs > 1000) {
                    delay(600)
                    val seekTime = formatTime(startPositionMs)
                    val seekBody = """
                        <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <Unit>REL_TIME</Unit>
                            <Target>$seekTime</Target>
                        </u:Seek>
                    """.trimIndent()
                    sendSoapRequest(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Seek", seekBody)
                    Log.d(TAG, "castToDeviceWithCover: Seek to $seekTime")
                }

                _castStatus.value = CastStatus.PLAYING
                acquireWakeLock() // 确保投屏开始时 WakeLock 已挂载（startProgressPolling 内也会调用，此处为双重保险）
                startProgressPolling()
                DlnaCastService.startForeground(applicationContext)
                
                // 立即更新 MusicService 通知栏为播放状态，确保系统识别为活跃媒体
                MusicServiceManager.update(
                    applicationContext,
                    currentTitle,
                    currentArtist,
                    true,
                    currentAlbumArtUrl,
                    startPositionMs,
                    currentDuration
                )
                
                withContext(Dispatchers.Main) { onCastSuccess?.invoke() }
                Log.i(TAG, "castToDeviceWithCover: success for '${title}' to ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "castToDeviceWithCover error", e)
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    /**
     * 根据文件后缀动态返回 DLNA protocolInfo，避免硬编码 video/mp4 导致 MKV 等格式投屏失败。
     */
    private fun getProtocolInfo(mediaUrl: String, isVideo: Boolean): String {
        if (!isVideo) return "http-get:*:audio/mpeg:*"
        val lower = mediaUrl.lowercase()
        return when {
            lower.contains(".mkv") -> "http-get:*:video/x-matroska:*"
            lower.contains(".flv") -> "http-get:*:video/x-flv:*"
            lower.contains(".ts")  -> "http-get:*:video/mp2t:*"
            lower.contains(".avi") -> "http-get:*:video/x-msvideo:*"
            lower.contains(".webm") -> "http-get:*:video/webm:*"
            lower.contains(".mov") -> "http-get:*:video/quicktime:*"
            else -> "http-get:*:video/mp4:DLNA.ORG_PN=MP4"
        }
    }

    /**
     * 构造完整的 DIDL-Lite 元数据，包含专辑图、歌手、专辑名。
     * 与 buildAlbumArtMetadata 不同，这个版本包含 <upnp:artist> 和 <upnp:album> 等完整信息。
     */
    private fun buildFullDlnaMetadata(
        title: String,
        mediaUrl: String,
        albumArtUrl: String?,
        artist: String?,
        album: String?,
        duration: String,
        isVideo: Boolean = false
    ): String {
        val artEntry = if (albumArtUrl != null) {
            "<upnp:albumArtURI dlna:profileID=\"JPEG_TN\">${escapeXml(albumArtUrl)}</upnp:albumArtURI>"
        } else ""
        val artistEntry = if (!artist.isNullOrEmpty()) {
            "<upnp:artist>${escapeXml(artist)}</upnp:artist>"
        } else "<upnp:artist>MediaPlayer</upnp:artist>"
        val albumEntry = if (!album.isNullOrEmpty()) {
            "<upnp:album>${escapeXml(album)}</upnp:album>"
        } else ""
        val upnpClass = if (isVideo) "object.item.videoItem.movie" else "object.item.audioItem.musicTrack"
        val protocolInfo = getProtocolInfo(mediaUrl, isVideo)
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" 
            xmlns:dc="http://purl.org/dc/elements/1.1/" 
            xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" 
            xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">
    <item id="1" parentID="0" restricted="1">
        <dc:title>${escapeXmlAttr(title)}</dc:title>
        $artistEntry
        $albumEntry
        <upnp:class>$upnpClass</upnp:class>
        $artEntry
        <res duration="$duration" protocolInfo="$protocolInfo">${escapeXml(mediaUrl)}</res>
    </item></DIDL-Lite>""".trimIndent()
    }

    /**
     * 通过 SOAP GetPositionInfo 获取当前播放进度。
     * 返回 Pair<positionMs, durationMs>，失败返回 null。
     */
    private suspend fun getPositionInfoSoap(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val controlUrl = customControlUrl ?: return@withContext null
        try {
            val soapBody = """
                <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                </u:GetPositionInfo>
            """.trimIndent()

            val conn = java.net.URL(controlUrl).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"")
            conn.setRequestProperty("User-Agent", "MediaPlayerPlus/1.0")
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 5000

            val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n<s:Body>\n$soapBody\n</s:Body>\n</s:Envelope>"
            conn.outputStream.bufferedWriter().use { it.write(envelope); it.flush() }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val relTime = extractXmlTag(xml, "RelTime") ?: return@withContext null
            val trackDuration = extractXmlTag(xml, "TrackDuration") ?: return@withContext null
            val pos = parseTimeToMs(relTime)
            val dur = parseTimeToMs(trackDuration)
            Pair(pos, dur)
        } catch (e: Exception) {
            Log.w(TAG, "getPositionInfoSoap failed: ${e.message}")
            null
        }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val regex = "<$tag>(.*?)</$tag>".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun parseTimeToMs(time: String): Long {
        // 格式: HH:MM:SS 或 HH:MM:SS.mmm
        val parts = time.split(":")
        if (parts.size < 3) return 0L
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val s = parts[2].substringBefore(".").toDouble()
            (h * 3600 + m * 60 + s).toLong() * 1000
        } catch (e: Exception) { 0L }
    }

    private fun formatTime(ms: Long): String {
        val h = (ms / 3_600_000).toInt()
        val m = ((ms % 3_600_000) / 60_000).toInt()
        val s = ((ms % 60_000) / 1000).toDouble()
        return String.format(Locale.US, "%02d:%02d:%02.0f", h, m, s)
    }

    /**
     * 从设备描述 XML 中解析 AVTransport 服务的 controlURL。
     * 尝试多个常见端口和路径，不再硬编码 :80/description.xml。
     */
    private suspend fun resolveAVTransportControlUrl(device: DlnaDevice): String? = withContext(Dispatchers.IO) {
        val ports = listOf(80, 8080, 49152, 49153, 49154, 49155, 8200, 38520)
        val paths = listOf("/description.xml", "/rootDesc.xml", "/dmr.xml", "/upnp/desc.xml")
        for (port in ports) {
            for (path in paths) {
                val controlUrl = tryFetchControlUrl(device.address, port, path)
                if (controlUrl != null) {
                    Log.d(TAG, "resolveAVTransportControlUrl SUCCESS: ${device.address}:$port$path -> $controlUrl")
                    return@withContext controlUrl
                }
            }
        }
        Log.w(TAG, "resolveAVTransportControlUrl FAILED for ${device.name}")
        null
    }

    private fun tryFetchControlUrl(host: String, port: Int, path: String): String? {
        try {
            val descriptionUrl = "http://$host:$port$path"
            val conn = java.net.URL(descriptionUrl).openConnection() as? java.net.HttpURLConnection ?: return null
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val serviceRegex = "<service>.*?<serviceType>[^<]*AVTransport[^<]*</serviceType>.*?<controlURL>(.*?)</controlURL>.*?</service>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val match = serviceRegex.find(xml)
            val controlUrl = match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            if (controlUrl.startsWith("http")) return controlUrl
            val baseUrl = "http://$host:$port"
            return if (controlUrl.startsWith("/")) "$baseUrl$controlUrl" else "$baseUrl/$controlUrl"
        } catch (e: Exception) { return null }
    }

    private fun buildAlbumArtMetadata(title: String, albumArtUrl: String, mediaUrl: String, duration: String): String {
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" 
            xmlns:dc="http://purl.org/dc/elements/1.1/" 
            xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" 
            xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">
    <item id="1" parentID="0" restricted="1">
        <dc:title>${escapeXmlAttr(title)}</dc:title>
        <upnp:artist>MediaPlayer</upnp:artist>
        <upnp:class>object.item.audioItem.musicTrack</upnp:class>
        <upnp:albumArtURI dlna:profileID="JPEG_TN">${escapeXml(albumArtUrl)}</upnp:albumArtURI>
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
        // 不释放 WakeLock！切歌时轮询重置不能释放锁，否则后台会被冻结。
        // WakeLock 只在彻底 stop() 时释放。
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
            // 替换 127.0.0.1 为手机实际 IP，否则电视无法访问本地代理流
            return path.replace("127.0.0.1", currentHostIp)
        }
        if (path.startsWith("content://")) {
            return path
        }
        return try {
            val file = java.io.File(path)
            val encodedFileName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            val encodedFullPath = URLEncoder.encode(path, "UTF-8")
            var url = "http://$currentHostIp:$SERVER_PORT/file/$encodedFileName?path=$encodedFullPath"
            // 伪装：对 MKV/AVI/FLV 等非 MP4 格式追加 ?ext=.mp4，让挑剔的电视误以为是 MP4，
            // 实际传输的仍是原封不动的 MKV 字节流，电视根据 EBML 头部自动识别并硬解
            if (file.name.endsWith(".mkv", true) || file.name.endsWith(".avi", true) ||
                file.name.endsWith(".flv", true) || file.name.endsWith(".ts", true)) {
                url += "&ext=.mp4"
            }
            url
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

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MediaPlayerPlus:DlnaWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 最长持有 2 小时
                Log.d(TAG, "WakeLock acquired for DLNA background polling.")
            }
            // 同时获取 WifiLock，确保后台网络传输稳定
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(TAG, "WifiLock acquired for DLNA background playback.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock/WifiLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock/WifiLock", e)
        }
    }

    private fun startProgressPolling() {
        acquireWakeLock() // 锁屏时保持 CPU 唤醒，确保进度轮询正常运行
        stopProgressPolling()
        nullProgressCount = 0
        lastProgressMs = 0L
        // 重置插值状态和 seek 锁定
        pollingLastKnownPosMs = 0L
        pollingLastKnownTimeMs = System.currentTimeMillis()
        pollingLastKnownDurMs = 0L
        pollingLastSoapTime = 0L
        seekLockUntil = 0L
        _progress.value = 0L to 0L
        Log.d(TAG, "startProgressPolling: launching polling job, customControlUrl=$customControlUrl, castStatus=${_castStatus.value}")
        
        progressJob = progressPollingScope.launch {
            Log.d(TAG, "startProgressPolling: job started, castStatus=${_castStatus.value}")
            var failureCount = 0
            var consecutiveSame = 0
            var iteration = 0
            val pollStartTime = System.currentTimeMillis() // 记录开始投屏的时间点，用于缓冲保护
            
            while (progressPollingScope.isActive && (_castStatus.value == CastStatus.PLAYING || _castStatus.value == CastStatus.PAUSED)) {
                try {
                    iteration++
                    val useSoap = customControlUrl != null
                    val now = System.currentTimeMillis()
                    val inSeekLock = seekLockUntil > 0 && now < seekLockUntil
                    val shouldQuerySoap = !useSoap || (now - pollingLastSoapTime >= 1500)

                    if (shouldQuerySoap) {
                        Log.d(TAG, "poll #$iteration start (SOAP query)")
                        val result: Pair<Long, Long>? = if (useSoap) {
                            withTimeoutOrNull(5000) { getPositionInfoSoap() }
                        } else {
                            withTimeoutOrNull(4000) { DLNACast.getProgress() }
                        }
                        Log.d(TAG, "poll #$iteration getProgress returned: ${result?.first} / ${result?.second} (soap=$useSoap)")

                        if (result != null) {
                            val resultBehind = result.first < seekTargetPos
                            if (inSeekLock && resultBehind) {
                                if (result.first > pollingLastKnownPosMs) {
                                    pollingLastKnownPosMs = result.first
                                    pollingLastKnownTimeMs = now
                                }
                                pollingLastKnownDurMs = result.second
                                pollingLastSoapTime = now
                                failureCount = 0
                                nullProgressCount = 0
                                lastProgressMs = result.first
                                Log.d(TAG, "progress poll: seekLock-ignored SOAP pos=${result.first}")
                            } else {
                                if (inSeekLock) seekLockUntil = 0  // TV 已追上，解锁
                                val pos = result.first
                                val dur = result.second
                                // 【关键】保存上一次的总时长，用于精准捕捉 dur 从有效值变为 0 的播放完成信号
                                val previousDurMs = pollingLastKnownDurMs

                                _progress.value = result
                                pollingLastKnownPosMs = pos
                                pollingLastKnownTimeMs = now
                                pollingLastKnownDurMs = dur
                                pollingLastSoapTime = now
                                failureCount = 0
                                nullProgressCount = 0
                                
                                // 同步到 MusicService 通知栏，保持系统活跃状态
                                MusicServiceManager.updatePosition(
                                    applicationContext,
                                    _castStatus.value == CastStatus.PLAYING,
                                    pos,
                                    dur,
                                    1.0f
                                )

                                Log.d(TAG, "progress poll: pos=$pos dur=$dur (was $previousDurMs)")
                                
                                // 只有在 pos > 0 已经开始播放后，才记录"卡住"次数
                                // 刚开始投屏 pos=0 dur=0 属于电视缓冲大文件，不计入卡住
                                if (pos > 0 && pos == lastProgressMs) {
                                    consecutiveSame++
                                    Log.d(TAG, "  stuck consecutiveSame=$consecutiveSame")
                                } else {
                                    consecutiveSame = 0
                                }
                                lastProgressMs = pos

                                // SOAP 查询结果：距离结束 2.5 秒内立即触发切歌（无需 nearEndCount 次数累加）
                                if (dur > 0 && _castStatus.value == CastStatus.PLAYING && !inSeekLock) {
                                    if (pos >= dur - 2500) {
                                        Log.d(TAG, "Track ended early by SOAP poll (pos=$pos dur=$dur). Triggering onTrackEnded.")
                                        applicationScope.launch(Dispatchers.Main) { onTrackEnded?.invoke() }
                                        break
                                    }
                                } else if (dur == 0L && previousDurMs > 0L && _castStatus.value == CastStatus.PLAYING && !inSeekLock) {
                                    // TV 已经播放完返回 dur=0（使用 previousDurMs 判断，因为 pollingLastKnownDurMs 已被覆盖为 0）
                                    Log.d(TAG, "Track finished (dur=0, was $previousDurMs). Triggering onTrackEnded.")
                                    applicationScope.launch(Dispatchers.Main) { onTrackEnded?.invoke() }
                                    break
                                }

                                // 只有在 pos > 0（确实播放过）或者开播已经超过 20 秒后，卡住才判定为播放结束
                                val isPastInitialBuffering = (now - pollStartTime) > 20000
                                if (consecutiveSame >= 5 && (pos > 0 || isPastInitialBuffering) && _castStatus.value == CastStatus.PLAYING && !inSeekLock) {
                                    Log.d(TAG, "Track ended by stuck progress (pos=$pos stuck=$consecutiveSame). Triggering onTrackEnded.")
                                    applicationScope.launch(Dispatchers.Main) { onTrackEnded?.invoke() }
                                    break
                                }
                            }
                        } else {
                            if (_castStatus.value == CastStatus.PLAYING) {
                                nullProgressCount++
                                if (nullProgressCount >= 5) {
                                    Log.d(TAG, "Track ended by null progress x5. Triggering onTrackEnded.")
                                    applicationScope.launch(Dispatchers.Main) { onTrackEnded?.invoke() }
                                    break
                                }
                            }
                            failureCount++
                            Log.w(TAG, "getProgress null/timeout. nullCount=$nullProgressCount failure=$failureCount")
                        }
                    } else if (useSoap && _castStatus.value == CastStatus.PLAYING) {
                        // 本地 200ms 插值计算：实时预测是否到了最后 2 秒
                        val elapsed = now - pollingLastKnownTimeMs
                        val interpolatedPos = (pollingLastKnownPosMs + elapsed).coerceAtMost(pollingLastKnownDurMs)
                        _progress.value = interpolatedPos to pollingLastKnownDurMs

                        // 只有在获取到了有效的总时长 (dur > 0) 且已开始播放 (pos > 0) 且不在 seek 时，才进行插值切歌预测
                        if (pollingLastKnownDurMs > 0 && pollingLastKnownPosMs > 0 && !inSeekLock && interpolatedPos >= pollingLastKnownDurMs - 2000) {
                            Log.d(TAG, "Track ended early by local interpolation ($interpolatedPos / $pollingLastKnownDurMs). Triggering onTrackEnded.")
                            applicationScope.launch(Dispatchers.Main) { onTrackEnded?.invoke() }
                            break
                        }
                    }

                    // 获取音量（仅 DLNACast 路径）
                    if (!useSoap) {
                        val vol: Pair<Int?, Boolean?>? = withTimeoutOrNull(1000) {
                            DLNACast.getVolume()
                        }
                        if (vol != null) {
                            _volume.value = (vol.first ?: -1) to (vol.second ?: false)
                        }
                    }

                    if (failureCount >= MAX_PROGRESS_POLLING_FAILURES) { // 增加容错，从 5 次提高到 10 次，应对深睡唤醒延迟
                        Log.e(TAG, "Polling failureCount=$failureCount, stopping.")
                        withContext(Dispatchers.Main) {
                            _castStatus.value = CastStatus.ERROR
                            stop()
                        }
                        break
                    }
                    delay(200)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Progress polling job cancelled.")
                    break
                } catch (e: Exception) {
                    failureCount++
                    Log.w(TAG, "Progress polling exception: ${e.message}. failure=$failureCount")
                    delay(200)
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