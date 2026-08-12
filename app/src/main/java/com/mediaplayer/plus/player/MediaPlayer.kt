package com.mediaplayer.plus.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.*
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.nio.charset.StandardCharsets

// =====================================================================
// 播放状态与枚举
// =====================================================================
enum class RepeatMode { OFF, ALL, ONE }

enum class VideoScaleMode(val label: String) {
    FIT("自动适应"), CROP("全屏裁切"), STRETCH("拉伸铺满"),
    RATIO_16_9("强制 16:9"), RATIO_4_3("强制 4:3"), ORIGINAL("原始比例")
}

data class TrackInfo(val id: Int = -1, val title: String = "", val isSelected: Boolean = false)

data class PlayerState(
    val status: Status = Status.IDLE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val mediaPath: String = "",
    val albumArtUrl: String? = null,
    val albumArtBytes: ByteArray? = null,
    val isVideo: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoAspectRatio: Float = 16f / 9f,
    val nativeAspectRatio: Float = 16f / 9f,
    val videoScaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val playbackSpeed: Float = 1.0f,
    val volume: Int = 100,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffle: Boolean = false,
    val sleepTimerRemainingMs: Long = 0L,
    val audioInfo: String = "",
    val lyrics: List<LyricEntry> = emptyList(),
    val currentLyricIndex: Int = -1,
    val errorMessage: String? = null,
    val themeColor: Long = 0xFF6200EE,
    val currentDecoder: String = "自动解码",
    val libassEnabled: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val videoTracks: List<TrackInfo> = emptyList(),
    val cues: List<androidx.media3.common.text.Cue> = emptyList(),
    val eqPresets: List<String> = emptyList(),
    val currentEqPreset: Int = 0,
    val eqBandLevels: List<Int> = List(5) { 0 },
    val eqBandCount: Int = 5,
    val eqLevelMin: Int = -1500,
    val eqLevelMax: Int = 1500
) {
    enum class Status { IDLE, PREPARING, READY, ENDED, ERROR }
}

@UnstableApi
interface MediaPlayer {
    val state: StateFlow<PlayerState>
    var onFileEnd: (() -> Unit)?
    var onMetadataParsed: ((mediaId: String, title: String?, artist: String?, albumArt: ByteArray?) -> Unit)?
    fun init(context: Context)
    fun load(url: String, title: String? = null, artist: String? = null, albumArtUrl: String? = null, startPaused: Boolean = false, duration: Long = 0L, isVideo: Boolean? = null, mediaId: String? = null, albumArtBytes: ByteArray? = null)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seek(positionMs: Long)
    fun seekByOffset(offsetMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Int)
    fun setRepeatMode(mode: RepeatMode)
    fun setShuffle(shuffle: Boolean)
    fun stop()
    fun release()
    fun setSurface(surface: Surface?)
    fun setAudioFilter(audioFilter: String)
    fun selectAudioTrack(id: Int)
    fun selectSubtitleTrack(id: Int)
    fun selectVideoTrack(id: Int)
    fun addSubtitleFile(uri: Uri)
    fun addAudioFile(uri: Uri)
    fun setDecoderMode(mode: String)
    fun setLibassEnabled(enabled: Boolean)
    fun setSubtitleDisplaySize(width: Int, height: Int)
    fun cycleAspectRatio()
    fun refreshTrackList()
    fun getEqualizerPresets(): List<String>
    fun getEqualizerBandCount(): Int
    fun getEqualizerBandLevel(bandIndex: Int): Int
    fun setEqualizerBandLevel(bandIndex: Int, level: Int)
    fun setEqualizerPreset(presetIndex: Int)
    fun resetEqualizer()
    fun getEqualizerBandLevelRange(): Pair<Int, Int>
    fun updateExternalProgress(positionMs: Long)
}

@UnstableApi
class MediaPlayerImpl(private val lyricRepository: LyricRepository = DefaultLyricRepository()) : MediaPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override var onFileEnd: (() -> Unit)? = null
    override var onMetadataParsed: ((String, String?, String?, ByteArray?) -> Unit)? = null

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentSurface: Surface? = null
    private var lastLoadedUrl: String? = null
    @Volatile private var loadingUrl: String? = null
    @Volatile private var isManualLoading = false
    private var lastEndedUrl: String? = null
    private var currentMediaUrl: String = ""
    var lyricsFilterEnabled: Boolean = true
    @Volatile private var currentDecoder = "自动解码"
    private var currentVideoScaleMode = VideoScaleMode.FIT
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var currentAudioSessionId = -1
    private var assHandler: AssHandler? = null
    private var libassRawInterceptorFactory: LibassRawInterceptorFactory? = null
    private lateinit var appContext: Context
    private lateinit var exoPlayer: ExoPlayer
    private var playerListener: Player.Listener? = null
    private var subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList()

    companion object {
        private const val TAG = "ExoPlayerImpl"
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m3u8", "ts", "m2ts", "vob", "rmvb")
        private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
        private val LANG_MAP = mapOf("chi" to "国语", "chs" to "国语", "cht" to "国语", "zh" to "国语", "jpn" to "日语", "ja" to "日语", "kor" to "韩语", "ko" to "韩语", "eng" to "英语", "en" to "英语")
        private val PRESET_ZH = mapOf(
            "Flat" to "平坦",
            "Normal" to "标准",
            "Rock" to "摇滚",
            "Pop" to "流行",
            "Jazz" to "爵士",
            "Classical" to "古典",
            "Country" to "乡村",
            "Blues" to "蓝调",
            "Hip Hop" to "嘻哈",
            "Headphones" to "耳机",
            "Soft Rock" to "柔和摇滚",
            "Dance" to "舞曲",
            "Vocal Boost" to "人声增强",
            "Full Bass" to "重低音",
            "Full Treble" to "高音增强",
            "Bass Boost" to "低音增强",
            "Treble Boost" to "高音增强",
            "Vocal Reduct" to "人声减弱",
            "Late Night" to "深夜",
            "Folk" to "民谣",
            "Heavy Metal" to "重金属"
        )
        fun localizePreset(name: String): String = PRESET_ZH[name] ?: name
    }

    override fun init(context: Context) {
        appContext = context.applicationContext
        val trackSelector = DefaultTrackSelector(appContext).apply {
            setParameters(parameters.buildUpon().setMaxVideoSizeSd().build())
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1500, 5000)
            .build()
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableAudioTrackPlaybackParams(true)
            .setEnableAudioFloatOutput(true)

        val assHandler = AssHandler(AssRenderType.CUES)
        this.assHandler = assHandler
        this.libassRawInterceptorFactory = LibassRawInterceptorFactory(DefaultSubtitleParserFactory(), assHandler, useLibass = _state.value.libassEnabled)
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setSubtitleParserFactory(libassRawInterceptorFactory!!)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()

        assHandler.init(exoPlayer)
        exoPlayer.addListener(assHandler)

        playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                when (s) {
                    Player.STATE_BUFFERING -> _state.value = _state.value.copy(isBuffering = true, status = PlayerState.Status.PREPARING)
                    Player.STATE_READY -> {
                        isManualLoading = false
                        _state.value = _state.value.copy(isBuffering = false, status = PlayerState.Status.READY)
                        refreshTracks(); updateAudioInfo(); setupEqualizer(); pullMediaMetadata()
                        val d = exoPlayer.duration; if (d > 0) _state.value = _state.value.copy(durationMs = d)
                    }
                    Player.STATE_ENDED -> handlePlaybackEnded()
                    Player.STATE_IDLE -> _state.value = _state.value.copy(status = PlayerState.Status.IDLE)
                }
            }
            override fun onIsPlayingChanged(p: Boolean) { if (!isManualLoading || p) _state.value = _state.value.copy(isPlaying = p) }
            override fun onTracksChanged(t: Tracks) { refreshTracks() }
            override fun onTimelineChanged(t: Timeline, r: Int) { val d = exoPlayer.duration; if (d > 0) _state.value = _state.value.copy(durationMs = d) }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    assHandler.setVideoSize(v.width, v.height)
                    val r = v.width.toFloat() / v.height.toFloat()
                    _state.value = _state.value.copy(videoWidth = v.width, videoHeight = v.height, videoAspectRatio = r, nativeAspectRatio = r)
                }
            }
            override fun onCues(cg: androidx.media3.common.text.CueGroup) { _state.value = _state.value.copy(cues = cg.cues) }
            override fun onMediaMetadataChanged(mm: MediaMetadata) {
                val t = mm.title?.toString(); val a = mm.artist?.toString(); val u = mm.artworkUri?.toString(); val d = mm.artworkData
                val cur = _state.value
                _state.value = cur.copy(
                    title = t?.takeIf { it.isNotBlank() } ?: cur.title,
                    artist = a?.takeIf { it.isNotBlank() } ?: cur.artist,
                    albumArtUrl = u?.takeIf { it.isNotBlank() } ?: cur.albumArtUrl,
                    albumArtBytes = d ?: cur.albumArtBytes
                )
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                currentAudioSessionId = audioSessionId
                setupEqualizer()
            }
        }
        exoPlayer.addListener(playerListener!!)
        uiHandler.post(object : Runnable {
            override fun run() {
                val isP = exoPlayer.isPlaying; val cur = _state.value
                if (cur.durationMs <= 0L) { val d = exoPlayer.duration; if (d > 0) _state.value = cur.copy(durationMs = d) }
                if (isP) { 
                    val p = exoPlayer.currentPosition
                    updateLyricIndex(p)
                    _state.value = _state.value.copy(currentPositionMs = p) 
                }
                uiHandler.postDelayed(this, 200)
            }
        })
    }

    override fun load(url: String, title: String?, artist: String?, albumArtUrl: String?, startPaused: Boolean, duration: Long, isVideo: Boolean?, mediaId: String?, albumArtBytes: ByteArray?) {
        if (url == lastLoadedUrl && _state.value.status == PlayerState.Status.PREPARING) return
        if (url == loadingUrl) return // 同一 URL 正在加载，跳过重复请求
        lastLoadedUrl = url; currentMediaUrl = url; isManualLoading = true
        val isVid = isVideo ?: (isVideoFile(url) || (title != null && isVideoFile(title)))
        val effectiveMediaId = mediaId ?: url
        val cleanTitle = title?.trim()?.let { t ->
            val dot = t.lastIndexOf('.')
            if (dot > 0 && t.substring(dot + 1).lowercase() in VIDEO_EXTENSIONS) t.substring(0, dot) else t
        } ?: url.substringAfterLast("/").substringBeforeLast(".")

        _state.value = _state.value.copy(
            status = PlayerState.Status.PREPARING,
            isPlaying = !startPaused,
            title = cleanTitle,
            artist = artist ?: "",
            mediaPath = effectiveMediaId,
            albumArtUrl = albumArtUrl,
            albumArtBytes = albumArtBytes,
            isVideo = isVid,
            currentPositionMs = 0,
            durationMs = duration,
            lyrics = emptyList(),
            currentLyricIndex = -1
        )
        loadingUrl = url
        playerScope.launch {
            try {
                val parsedUri = if (url.startsWith("http") || url.startsWith("smb") || url.startsWith("content")) Uri.parse(url) else Uri.fromFile(java.io.File(url))
                val mb = MediaItem.Builder().setUri(parsedUri)
                if (url.startsWith("http://127.0.0.1") && isVid) {
                    val ext = title?.substringAfterLast(".")?.lowercase() ?: url.substringAfterLast(".").lowercase()
                    when (ext) {
                        "mp4" -> mb.setMimeType(MimeTypes.VIDEO_MP4)
                        "mkv" -> mb.setMimeType(MimeTypes.VIDEO_MATROSKA)
                        "webm" -> mb.setMimeType(MimeTypes.VIDEO_WEBM)
                        "avi" -> mb.setMimeType("video/x-msvideo")
                        "mov" -> mb.setMimeType("video/quicktime")
                    }
                }
                if (subtitleConfigs.isNotEmpty()) {
                    mb.setSubtitleConfigurations(subtitleConfigs)
                }
                exoPlayer.setMediaItem(mb.build())
                exoPlayer.playWhenReady = !startPaused
                if (startPaused) exoPlayer.pause() 
                exoPlayer.prepare()
                currentSurface?.let { if (it.isValid) exoPlayer.setVideoSurface(it) }
                if (isVid) _state.value = _state.value.copy(videoAspectRatio = 16f/9f, nativeAspectRatio = 16f/9f)
                com.mediaplayer.plus.ui.FloatingLyricsService.updateLyricLine("")
                if (!isVid && currentMediaUrl.isNotEmpty()) loadLyricsAsync(currentMediaUrl, effectiveMediaId)
            } finally {
                loadingUrl = null
            }
        }
    }

    private fun loadLyricsAsync(mediaUrl: String, mediaId: String) {
        playerScope.launch {
            if (_state.value.lyrics.isNotEmpty()) return@launch
            var rawL: String? = null; var rawA: ByteArray? = null
            try {
                withContext(Dispatchers.IO) {
                    val fullBytes = readExactId3Bytes(mediaUrl) ?: return@withContext
                    if (fullBytes.size < 10) return@withContext
                    
                    val flags = fullBytes[5].toInt() and 0xFF
                    val isUnsync = (flags and 0x80) != 0
                    val tagSize = readSynchsafeInt(fullBytes, 6)
                    
                    if (tagSize > 0 && fullBytes.size >= 10 + tagSize) {
                        // 🌟 处理 Unsynchronization: ID3v2 规范要求去除 $FF $00 中的 $00
                        val processingBytes = if (isUnsync) {
                            val result = ByteArray(tagSize)
                            var rPos = 0
                            var wPos = 0
                            val src = fullBytes.copyOfRange(10, 10 + tagSize)
                            while (rPos < src.size) {
                                result[wPos++] = src[rPos]
                                if (src[rPos] == 0xFF.toByte() && rPos + 1 < src.size && src[rPos + 1] == 0.toByte()) {
                                    rPos += 2
                                } else {
                                    rPos++
                                }
                            }
                            if (wPos < result.size) result.copyOf(wPos) else result
                        } else {
                            fullBytes.copyOfRange(10, 10 + tagSize)
                        }

                        var lp = 0; var extT: String? = null; var extA: String? = null
                        parseId3Frames(processingBytes, 0, processingBytes.size, isUnsync) { art, lyr, t, a, fid ->
                            if (art != null) rawA = art; if (t != null) extT = t; if (a != null) extA = a
                            if (!lyr.isNullOrBlank() && (lyr.contains("[") || lyr.length > 50)) {
                                val p = when (fid.uppercase()) { "USLT" -> 3; "COMM" -> 2; else -> 1 }
                                if (p > lp) { rawL = lyr; lp = p }
                            }
                        }
                        if (extT != null || extA != null || rawA != null) {
                            withContext(Dispatchers.Main) {
                                val c = _state.value; _state.value = c.copy(title = extT ?: c.title, artist = extA ?: c.artist, albumArtBytes = rawA ?: c.albumArtBytes)
                                onMetadataParsed?.invoke(mediaId, extT, extA, rawA)
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "ID3 error", e) }
            if (!rawL.isNullOrBlank()) {
                val parsed = parseLyricsWithFallback(rawL!!.replace(Regex("""\s*/\s*\["""), "\n[").replace(Regex("""\s*/\s*"""), "\n"))
                if (parsed.isNotEmpty()) {
                    val filtered = if (lyricsFilterEnabled) LyricParser.filterLyrics(parsed) else parsed
                    _state.value = _state.value.copy(lyrics = filtered); return@launch
                }
            }
            // Fallback to external lyrics
            val decodedName = try {
                val rawName = mediaUrl.substringAfterLast("/")
                java.net.URLDecoder.decode(rawName, "UTF-8").substringBeforeLast(".")
            } catch (e: Exception) {
                mediaUrl.substringAfterLast("/").substringBeforeLast(".")
            }
            val extLyrics = lyricRepository.fetchLyrics(mediaUrl, null, decodedName)
            if (extLyrics.isNotEmpty()) {
                val filtered = if (lyricsFilterEnabled) LyricParser.filterLyrics(extLyrics) else extLyrics
                _state.value = _state.value.copy(lyrics = filtered)
            }
        }
    }

    private fun isVideoFile(u: String): Boolean = VIDEO_EXTENSIONS.contains(u.substringAfterLast(".").lowercase())

    private fun parseLyricsWithFallback(l: String): List<LyricEntry> { 
        val p = LyricParser.parse(l)
        if (p.isNotEmpty()) return p
        val lines = l.lines().filter { it.isNotBlank() && !it.startsWith("[") }
        if (lines.isNotEmpty()) {
            return lines.mapIndexed { index, text -> LyricEntry(index * 3000L, text.trim()) }
        }
        return emptyList() 
    }
    private fun readSynchsafeInt(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0x7F) shl 21) or ((b[o+1].toInt() and 0x7F) shl 14) or ((b[o+2].toInt() and 0x7F) shl 7) or (b[o+3].toInt() and 0x7F)
    
    private suspend fun readExactId3Bytes(u: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val stream: InputStream = if (u.startsWith("http")) {
                val url = java.net.URL(u)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.inputStream
            } else {
                java.io.FileInputStream(u)
            }
            stream.use { s ->
                val h = ByteArray(10); if (readExactly(s, h, 10) && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                    val ts = readSynchsafeInt(h, 6); if (ts > 0 && ts < 10*1024*1024) {
                        val b = ByteArray(10 + ts); System.arraycopy(h, 0, b, 0, 10)
                        if (readExactly(s, b, 10, ts)) return@use b
                    }
                }
                null
            }
        } catch (e: Exception) { null }
    }

    private fun readExactly(s: InputStream, b: ByteArray, off: Int, len: Int): Boolean {
        var r = len; var o = off; while (r > 0) { val c = s.read(b, o, r); if (c <= 0) break; o += c; r -= c }
        return r == 0
    }
    private fun readExactly(s: InputStream, b: ByteArray, len: Int): Boolean = readExactly(s, b, 0, len)

    private fun parseId3Frames(bytes: ByteArray, start: Int, end: Int, unsync: Boolean, onFound: (artBytes: ByteArray?, lyricsText: String?, title: String?, artist: String?, frameId: String) -> Unit) {
        var pos = start
        val timeTagRegex = Regex("""\[(\d{1,2}:\d{2}|ver:|ti:|ar:|al:)""")

        while (pos + 10 <= end) {
            val frameId = String(bytes, pos, 4, StandardCharsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break
            if (frameId.any { it !in ' '..'Z' }) break
            pos += 4

            // 获取帧大小。注意：帧头内部也可能是 synchsafe 的 (v2.4)，或者普通 32位整型 (v2.3)
            val b1 = bytes[pos].toInt() and 0xFF
            val b2 = bytes[pos+1].toInt() and 0xFF
            val b3 = bytes[pos+2].toInt() and 0xFF
            val b4 = bytes[pos+3].toInt() and 0xFF
            
            // 启发式判断：v2.3 常用普通 int，v2.4 强制 synchsafe
            val sizeNormal = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            val sizeSynch = (b1 shl 21) or (b2 shl 14) or (b3 shl 7) or b4
            
            val frameSize = if (pos + 4 + 2 + sizeNormal <= end && sizeNormal > 0) sizeNormal else sizeSynch
            
            pos += 4
            pos += 2 // skip flags
            
            if (frameSize <= 0 || pos + frameSize > end) break
            val dataStart = pos
            val dataEnd = pos + frameSize
            val normalizedId = frameId.uppercase()

            when (normalizedId) {
                "TIT2", "TIT1", "TIT" -> {
                    val text = extractTextFrameData(bytes, dataStart, dataEnd, normalizedId)
                    if (!text.isNullOrBlank()) onFound(null, null, text, null, normalizedId)
                }
                "TPE1", "TPE2", "TPE", "TP1" -> {
                    val text = extractTextFrameData(bytes, dataStart, dataEnd, normalizedId)
                    if (!text.isNullOrBlank()) onFound(null, null, null, text, normalizedId)
                }
                "APIC" -> {
                    val pic = extractApicData(bytes, dataStart, dataEnd)
                    if (pic != null) onFound(pic, null, null, null, "APIC")
                }
                "USLT", "COMM", "TXXX", "TEXT" -> {
                    val frameData = extractAllTextFields(bytes, dataStart, dataEnd, normalizedId)
                    val candidates = frameData.filter { 
                        it.isNotBlank() && !it.startsWith("http") && !it.contains("163 key")
                    }
                    if (candidates.isNotEmpty()) {
                        val best = candidates.sortedByDescending { 
                            (if (timeTagRegex.containsMatchIn(it)) 10000 else 0) + it.length 
                        }.first()
                        if (best.length > 20) {
                            onFound(null, best, null, null, normalizedId)
                        }
                    }
                }
            }
            pos = dataEnd
        }
    }

    private fun extractApicData(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        if (start >= end) return null
        try {
            val encoding = bytes[start].toInt() and 0xFF
            var current = start + 1
            while (current < end && bytes[current] != 0.toByte()) current++
            current++ // skip null
            if (current >= end) return null
            current++ // skip picture type
            if (encoding == 1 || encoding == 2) {
                while (current + 1 < end && (bytes[current] != 0.toByte() || bytes[current + 1] != 0.toByte())) current += 2
                current += 2
            } else {
                while (current < end && bytes[current] != 0.toByte()) current++
                current++
            }
            if (current < end) return bytes.copyOfRange(current, end)
        } catch (_: Exception) {}
        return null
    }

    private fun extractTextFrameData(b: ByteArray, s: Int, e: Int, id: String): String? = extractAllTextFields(b, s, e, id).firstOrNull()

    private fun extractAllTextFields(bytes: ByteArray, start: Int, end: Int, frameId: String): List<String> {
        if (start >= end) return emptyList()
        val encoding = bytes[start].toInt() and 0xFF
        val results = mutableListOf<String>()
        var currentPos = start + 1

        // 🌟 针对 USLT (歌词) 和 COMM (评论) 帧，跳过 3 字节的语言代码
        if (frameId == "USLT" || frameId == "COMM") {
            if (currentPos + 3 < end) {
                currentPos += 3
            }
        }

        while (currentPos < end) {
            val length = findNullInBytes(bytes, currentPos, end, encoding)
            val text = decodeBytesToString(bytes, currentPos, length, charsetFromEncoding(encoding))
            if (text != null) results.add(text)
            
            // 跳过终结符
            val terminatorSize = if (encoding == 1 || encoding == 2) 2 else 1
            currentPos += length + terminatorSize
            
            // 对于 USLT，描述字段之后紧跟的就是正文，且正文可能不带终结符直到帧末尾
            if (results.size >= 1 && (frameId == "USLT" || frameId == "COMM") && currentPos < end) {
                val remainingLen = end - currentPos
                val body = decodeBytesToString(bytes, currentPos, remainingLen, charsetFromEncoding(encoding))
                if (body != null) results.add(body)
                break
            }
        }
        return results
    }

    private fun findNullInBytes(bytes: ByteArray, start: Int, end: Int, encoding: Int): Int {
        var pos = start
        if (encoding == 1 || encoding == 2) { 
            while (pos + 1 < end) {
                if (bytes[pos] == 0.toByte() && bytes[pos + 1] == 0.toByte()) return pos - start
                pos += 2
            }
        } else {
            while (pos < end) {
                if (bytes[pos] == 0.toByte()) return pos - start
                pos++
            }
        }
        return end - start
    }

    private fun decodeBytesToString(bytes: ByteArray, start: Int, length: Int, charset: String): String? {
        if (length <= 0) return null
        
        // 🌟 核心乱码修正逻辑
        if (charset == "ISO-8859-1") {
            // 对于被标记为 Latin-1 的中文 MP3，按以下优先级猜测编码：
            // 1. GBK (中国大陆最常用)
            // 2. Big5 (港台地区常用)
            // 3. UTF-8 (部分非标软件)
            val guesses = listOf("GBK", "Big5", "UTF-8")
            for (guess in guesses) {
                try {
                    val decoder = java.nio.charset.Charset.forName(guess).newDecoder()
                    val byteBuffer = java.nio.ByteBuffer.wrap(bytes, start, length)
                    val decoded = decoder.decode(byteBuffer).toString()
                    // 验证解码结果：不含替换字符 \uFFFD，且确实含有非 ASCII 字符（说明猜测有效）
                    if (!decoded.contains("\uFFFD") && decoded.any { it.code > 127 }) {
                        return decoded.trim().replace("\u0000", "").replace("\uFEFF", "")
                    }
                } catch (_: Exception) {}
            }
        }

        // 默认处理，同时移除 BOM 和 Null
        return try {
            val s = String(bytes, start, length, java.nio.charset.Charset.forName(charset))
            s.trim().replace("\u0000", "").replace("\uFEFF", "")
        } catch (_: Exception) {
            null
        }
    }

    private fun charsetFromEncoding(encoding: Int): String = when (encoding) {
        1 -> "UTF-16"
        2 -> "UTF-16BE"
        3 -> "UTF-8"
        else -> "ISO-8859-1"
    }

    override fun play() { exoPlayer.play() }
    override fun pause() { exoPlayer.pause() }
    override fun togglePlayPause() { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }
    override fun seek(positionMs: Long) { exoPlayer.seekTo(positionMs) }
    override fun seekByOffset(offsetMs: Long) { exoPlayer.seekTo(exoPlayer.currentPosition + offsetMs) }
    override fun setSpeed(speed: Float) { exoPlayer.setPlaybackSpeed(speed); _state.value = _state.value.copy(playbackSpeed = speed) }
    override fun setVolume(volume: Int) { exoPlayer.volume = volume / 100f; _state.value = _state.value.copy(volume = volume) }
    override fun setRepeatMode(mode: RepeatMode) {
        // 始终使用 REPEAT_MODE_OFF，由 ViewModel 手动管理循环逻辑
        // 原因：ExoPlayer 每次只加载单个 MediaItem，REPEAT_MODE_ALL/ONE 只会循环当前这一个条目
        // 导致 STATE_ENDED 不会触发，onFileEnd 回调失效，playlist 无法前进
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(repeatMode = mode)
    }
    override fun setShuffle(shuffle: Boolean) { exoPlayer.shuffleModeEnabled = shuffle; _state.value = _state.value.copy(isShuffle = shuffle) }
    override fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = _state.value.copy(
            status = PlayerState.Status.IDLE,
            isPlaying = false,
            title = "",
            artist = "",
            mediaPath = "",
            currentPositionMs = 0,
            durationMs = 0,
            lyrics = emptyList(),
            currentLyricIndex = -1,
            cues = emptyList(),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            videoTracks = emptyList()
        )
    }
    override fun release() { uiHandler.removeCallbacksAndMessages(null); exoPlayer.release(); playerScope.cancel() }
    override fun setSurface(surface: Surface?) { currentSurface = surface; exoPlayer.setVideoSurface(surface) }

    override fun updateExternalProgress(positionMs: Long) {
        _state.value = _state.value.copy(currentPositionMs = positionMs)
        updateLyricIndex(positionMs)
    }
    
    override fun setAudioFilter(audioFilter: String) {
        val parameters = exoPlayer.trackSelectionParameters.buildUpon()
        // Here you would typically apply ffmpeg filters if using a custom renderer,
        // for standard ExoPlayer we just update state or use common effects.
        _state.value = _state.value.copy(audioInfo = "Filter: $audioFilter")
    }

    override fun selectAudioTrack(id: Int) {
        val current = _state.value.audioTracks.find { it.isSelected }?.id ?: -2
        if (current == id) {
            selectTrackGroup(C.TRACK_TYPE_AUDIO, -1) // 切换为关闭/禁用
        } else {
            selectTrackGroup(C.TRACK_TYPE_AUDIO, id)
        }
    }

    override fun selectSubtitleTrack(id: Int) {
        val current = _state.value.subtitleTracks.find { it.isSelected }?.id ?: -2
        if (current == id) {
            selectTrackGroup(C.TRACK_TYPE_TEXT, -1) // 再次点击已选中的字幕则关闭
        } else {
            selectTrackGroup(C.TRACK_TYPE_TEXT, id)
        }
    }

    override fun selectVideoTrack(id: Int) {
        val current = _state.value.videoTracks.find { it.isSelected }?.id ?: -2
        if (current == id) {
            // 视频轨道通常不能完全禁用，ExoPlayer 至少需要一个视频轨道（如果存在）
            // 但我们可以尝试切换或重置选择
            selectTrackGroup(C.TRACK_TYPE_VIDEO, id)
        } else {
            selectTrackGroup(C.TRACK_TYPE_VIDEO, id)
        }
    }
    fun selectSubtitleTrackByGroup(groupIndex: Int, trackIndex: Int) {
        selectTrackGroupByExact(C.TRACK_TYPE_TEXT, groupIndex, trackIndex)
    }

    private fun selectTrackGroup(trackType: Int, compositeId: Int) {
        val tracks = exoPlayer.currentTracks
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (compositeId < 0) {
            builder.setTrackTypeDisabled(trackType, true)
            builder.clearOverridesOfType(trackType)
        } else {
            val groupIndex = compositeId / 100
            val trackIndex = compositeId % 100
            if (groupIndex >= 0 && groupIndex < tracks.groups.size) {
                val group = tracks.groups[groupIndex]
                if (trackIndex >= 0 && trackIndex < group.length) {
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                }
            }
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    private fun selectTrackGroupByExact(trackType: Int, groupIndex: Int, trackIndex: Int) {
        val tracks = exoPlayer.currentTracks
        if (groupIndex >= 0 && groupIndex < tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (trackIndex >= 0 && trackIndex < group.length) {
                val builder = exoPlayer.trackSelectionParameters.buildUpon()
                builder.setTrackTypeDisabled(trackType, false)
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                exoPlayer.trackSelectionParameters = builder.build()
            }
        }
    }

    override fun addSubtitleFile(uri: Uri) {
        val mime = detectSubtitleMime(uri)
        val label = uri.path?.substringAfterLast('/')?.removeSuffix(uri.path?.substringAfterLast('.') ?: "") ?: "subtitle"
        val uniqueId = "ext_${uri.toString().hashCode()}"
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("zh")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setId(uniqueId)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()
        subtitleConfigs = subtitleConfigs + config
        Log.d("SubTitle", "addSubtitleFile mime=$mime uri=$uri id=$uniqueId")
        val cur = currentMediaUrl
        if (cur.isNotEmpty()) load(cur, _state.value.title, _state.value.artist, _state.value.albumArtUrl, !_state.value.isPlaying, _state.value.durationMs, _state.value.isVideo, _state.value.mediaPath, _state.value.albumArtBytes)
    }

    private fun detectSubtitleMime(uri: Uri): String {
        val path = uri.toString().lowercase()
        return when {
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".srt") -> "application/x-subrip"
            path.endsWith(".txt") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.TEXT_SSA
        }
    }

    override fun addAudioFile(uri: Uri) { /* Similar to subtitle */ }

    override fun setDecoderMode(mode: String) {
        currentDecoder = mode
        _state.value = _state.value.copy(currentDecoder = mode)
        // 🌟 应用解码器模式到 ExoPlayer（通过 TrackSelectionParameters）
        applyDecoderModeToExoPlayer(mode)
    }

    /** 🌟 将解码器模式应用到 ExoPlayer */
    private fun applyDecoderModeToExoPlayer(mode: String) {
        val player = internalExoPlayer() ?: return
        val currentParams = player.trackSelectionParameters
        val newParams = when (mode) {
            "硬件解码" -> currentParams.buildUpon()
                .setForceHighestSupportedBitrate(true)
                .build()
            "软件解码" -> currentParams.buildUpon()
                .setForceHighestSupportedBitrate(false)
                .setMaxVideoSize(0, 0)
                .setMaxVideoFrameRate(0)
                .build()
            else -> currentParams.buildUpon() // "自动解码" - 默认 ExoPlayer 行为
                .setForceHighestSupportedBitrate(false)
                .build()
        }
        player.trackSelectionParameters = newParams
    }

    private fun internalExoPlayer(): ExoPlayer? {
        return try { exoPlayer } catch (e: Exception) { null }
    }

    override fun setLibassEnabled(enabled: Boolean) {
        Log.d("Libass", "setLibassEnabled=$enabled useLibass=${libassRawInterceptorFactory?.useLibass}")
        _state.value = _state.value.copy(libassEnabled = enabled)
        libassRawInterceptorFactory?.useLibass = enabled
    }

    override fun setSubtitleDisplaySize(width: Int, height: Int) {
        assHandler?.setVideoSize(width, height)
    }

    override fun cycleAspectRatio() {
        val modes = VideoScaleMode.entries
        val next = modes[(modes.indexOf(currentVideoScaleMode) + 1) % modes.size]
        currentVideoScaleMode = next
        _state.value = _state.value.copy(videoScaleMode = next)
        // Apply to ExoPlayer if possible, or handle in UI
    }

    override fun refreshTrackList() { refreshTracks() }

    override fun getEqualizerPresets(): List<String> {
        val list = mutableListOf<String>()
        equalizer?.let { for (i in 0 until it.numberOfPresets) list.add(localizePreset(it.getPresetName(i.toShort()))) }
        return list
    }

    override fun getEqualizerBandCount(): Int = equalizer?.numberOfBands?.toInt() ?: 0
    override fun getEqualizerBandLevel(bandIndex: Int): Int = equalizer?.getBandLevel(bandIndex.toShort())?.toInt() ?: 0
    override fun setEqualizerBandLevel(bandIndex: Int, level: Int) {
        equalizer?.setBandLevel(bandIndex.toShort(), level.toShort())
        syncEqualizerState()
    }

    override fun setEqualizerPreset(presetIndex: Int) {
        equalizer?.usePreset(presetIndex.toShort())
        _state.value = _state.value.copy(currentEqPreset = presetIndex)
        syncEqualizerState()
    }

    override fun resetEqualizer() {
        val range = getEqualizerBandLevelRange()
        for (i in 0 until getEqualizerBandCount()) setEqualizerBandLevel(i, 0)
        syncEqualizerState()
    }

    override fun getEqualizerBandLevelRange(): Pair<Int, Int> {
        val r = equalizer?.bandLevelRange ?: shortArrayOf(-1500, 1500)
        return r[0].toInt() to r[1].toInt()
    }

    private fun setupEqualizer() {
        if (currentAudioSessionId == -1) return
        try {
            if (equalizer == null) {
                equalizer = android.media.audiofx.Equalizer(0, currentAudioSessionId)
                equalizer?.enabled = true
                _state.value = _state.value.copy(
                    eqPresets = getEqualizerPresets(),
                    eqBandCount = getEqualizerBandCount(),
                    eqLevelMin = getEqualizerBandLevelRange().first,
                    eqLevelMax = getEqualizerBandLevelRange().second
                )
                syncEqualizerState()
            }
        } catch (e: Exception) { Log.e(TAG, "Equalizer setup failed", e) }
    }

    private fun syncEqualizerState() {
        val count = getEqualizerBandCount()
        val levels = (0 until count).map { getEqualizerBandLevel(it) }
        _state.value = _state.value.copy(eqBandLevels = levels)
    }

    private fun refreshTracks() {
        val audio = mutableListOf<TrackInfo>()
        val text = mutableListOf<TrackInfo>()
        val video = mutableListOf<TrackInfo>()
        val tracks = exoPlayer.currentTracks
        for (g in 0 until tracks.groups.size) {
            val group = tracks.groups[g]
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT && type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val prefix = when(type) {
                    C.TRACK_TYPE_AUDIO -> "音频"
                    C.TRACK_TYPE_TEXT -> "字幕"
                    C.TRACK_TYPE_VIDEO -> "视频"
                    else -> "轨道"
                }
                val title = buildTrackTitleFromFormat(format, i, prefix)
                val compositeId = g * 100 + i
                val info = TrackInfo(compositeId, title, group.isTrackSelected(i))
                when (type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(info)
                    C.TRACK_TYPE_TEXT -> text.add(info)
                    C.TRACK_TYPE_VIDEO -> video.add(info)
                }
            }
        }
        _state.value = _state.value.copy(audioTracks = audio, subtitleTracks = text, videoTracks = video)
    }

    private fun buildTrackTitleFromFormat(format: Format, index: Int, prefix: String): String {
        val lang = format.language?.let { LANG_MAP[it] ?: it } ?: "未知"
        val label = format.label ?: ""
        return "$prefix ${index + 1} [$lang] $label".trim()
    }

    private fun updateAudioInfo() {
        val format = exoPlayer.audioFormat ?: return
        _state.value = _state.value.copy(audioInfo = "${format.sampleRate}Hz ${format.channelCount}ch ${format.codecs ?: ""}")
    }

    private fun pullMediaMetadata() {
        val mm = exoPlayer.mediaMetadata
        val cur = _state.value
        _state.value = cur.copy(
            title = mm.title?.toString()?.takeIf { it.isNotBlank() } ?: cur.title,
            artist = mm.artist?.toString()?.takeIf { it.isNotBlank() } ?: cur.artist
        )
    }

    private fun handlePlaybackEnded() { onFileEnd?.invoke() }
    private fun updateLyricIndex(p: Long) {
        val lyrics = _state.value.lyrics
        if (lyrics.isEmpty()) return
        var index = lyrics.indexOfLast { it.timeMillis <= p }
        if (index != _state.value.currentLyricIndex) {
            _state.value = _state.value.copy(currentLyricIndex = index)
            val line = if (index >= 0) lyrics[index].text else ""
            if (index >= 0) com.mediaplayer.plus.ui.FloatingLyricsService.updateLyricLine(line)
            // 蓝牙歌词：当功能开启且当前行有内容时，向 AVRCP 设备/锁屏推送
            if (index >= 0 && line.isNotBlank()) {
                com.mediaplayer.plus.MusicServiceManager.updateLyric(
                    appContext, line, _state.value.title, _state.value.artist
                )
            }
        }
    }
}
