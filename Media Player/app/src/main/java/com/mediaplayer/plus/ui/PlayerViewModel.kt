package com.mediaplayer.plus.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediaplayer.plus.AlbumArtRegistry
import com.mediaplayer.plus.DlnaManager
import com.mediaplayer.plus.MusicServiceManager
import com.mediaplayer.plus.data.MediaItem
import com.mediaplayer.plus.data.MediaRepository
import com.mediaplayer.plus.data.SmbManager
import com.mediaplayer.plus.data.SmbHttpProxy
import com.mediaplayer.plus.data.SmbMediaItem
import com.mediaplayer.plus.data.SmbServer
import com.mediaplayer.plus.data.Song
import com.mediaplayer.plus.data.Video
import com.mediaplayer.plus.player.MediaPlayer
import com.mediaplayer.plus.player.MediaPlayerImpl
import com.mediaplayer.plus.player.PlayerState
import com.mediaplayer.plus.player.RepeatMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)
    val smbManager = SmbManager(application)
    var smbHttpProxy: SmbHttpProxy? = null
    val player: MediaPlayer = MediaPlayerImpl()
    val state = player.state

    // 全局专辑图字节缓存 (ID -> Bytes)
    private val albumArtCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    // 🌟 列表版本号，仅当列表结构/顺序变化时增加，不随元数据更新变化
    // 用于 PagerState 的 key，防止切歌解析元数据时 Pager 重置导致 3D 动画消失
    private val _playlistVersion = MutableStateFlow(0)
    val playlistVersion: StateFlow<Int> = _playlistVersion.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private var originalPlaylist: List<MediaItem> = emptyList()
    private var currentPlaylist: List<MediaItem> = emptyList()
    private var currentPlaylistIndex: Int = -1
    private var currentType: String = "song"

    private var isHandlingExternalUri = false

    private val _playlistIndex = MutableStateFlow(0)
    val playlistIndex: StateFlow<Int> = _playlistIndex.asStateFlow()

    private val _playlistTotal = MutableStateFlow(0)
    val playlistTotal: StateFlow<Int> = _playlistTotal.asStateFlow()

    private val _currentPlaylist = MutableStateFlow<List<MediaItem>>(emptyList())
    val currentPlaylistFlow: StateFlow<List<MediaItem>> = _currentPlaylist.asStateFlow()

    data class FilterContext(val filterType: String = "all", val filterValue: String = "")
    private val _lastFilterContext = MutableStateFlow(FilterContext())
    val lastFilterContext: StateFlow<FilterContext> = _lastFilterContext.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.MusicLibrary)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _tvSelectedTab = MutableStateFlow("Recent") 
    val tvSelectedTab: StateFlow<String> = _tvSelectedTab.asStateFlow()

    enum class LayoutMode { GRID, LIST }
    private val _tvLayoutMode = MutableStateFlow(LayoutMode.GRID)
    val tvLayoutMode: StateFlow<LayoutMode> = _tvLayoutMode.asStateFlow()

    private val _shuffleMode = MutableStateFlow(0)
    val shuffleMode: StateFlow<Int> = _shuffleMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(0)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _miniPlayerVisible = MutableStateFlow(false)
    val miniPlayerVisible: StateFlow<Boolean> = _miniPlayerVisible.asStateFlow()

    private val _floatingLyricsEnabled = MutableStateFlow(false)
    val floatingLyricsEnabled: StateFlow<Boolean> = _floatingLyricsEnabled.asStateFlow()

    private val _bluetoothLyricsEnabled = MutableStateFlow(false)
    val bluetoothLyricsEnabled: StateFlow<Boolean> = _bluetoothLyricsEnabled.asStateFlow()

    // DLNA 投屏
    private val _dlnaCastStatus = MutableStateFlow<com.mediaplayer.plus.DlnaManager.CastStatus>(com.mediaplayer.plus.DlnaManager.CastStatus.IDLE)
    val dlnaCastStatus: StateFlow<com.mediaplayer.plus.DlnaManager.CastStatus> = _dlnaCastStatus.asStateFlow()

    private val _dlnaDevices = MutableStateFlow<List<com.mediaplayer.plus.DlnaManager.DlnaDevice>>(emptyList())
    val dlnaDevices: StateFlow<List<com.mediaplayer.plus.DlnaManager.DlnaDevice>> = _dlnaDevices.asStateFlow()

    private val _dlnaProgress = MutableStateFlow(0L to 0L)
    val dlnaProgress: StateFlow<Pair<Long, Long>> = _dlnaProgress.asStateFlow()

    private val _dlnaVolume = MutableStateFlow(-1 to false)
    val dlnaVolume: StateFlow<Pair<Int, Boolean>> = _dlnaVolume.asStateFlow()

    private val _dlnaSmoothProgress = MutableStateFlow(0L to 0L)
    val dlnaSmoothProgress: StateFlow<Pair<Long, Long>> = _dlnaSmoothProgress.asStateFlow()

    private var _selectedDlnaDevice: DlnaManager.DlnaDevice? = null

    init {
        viewModelScope.launch {
            DlnaManager.castStatus.collect { _dlnaCastStatus.value = it }
        }
        viewModelScope.launch {
            DlnaManager.progress.collect { _dlnaProgress.value = it }
        }
        viewModelScope.launch {
            DlnaManager.volume.collect { _dlnaVolume.value = it }
        }
        viewModelScope.launch {
            DlnaManager.devicesFlow.collect { _dlnaDevices.value = it }
        }
        viewModelScope.launch {
            var dragging = false
            while (true) {
                delay(500)
                val castStatus = DlnaManager.castStatus.value
                if (castStatus == DlnaManager.CastStatus.PLAYING || castStatus == DlnaManager.CastStatus.PAUSED) {
                    val (pos, dur) = _dlnaSmoothProgress.value
                    player.updateExternalProgress(pos)
                }
            }
        }
        viewModelScope.launch {
            DlnaManager.progress.collect {
                _dlnaSmoothProgress.value = it
            }
        }
        DlnaManager.onTrackEnded = { playNext(isAutoAdvance = true) }
        DlnaManager.init(application)
    }

    fun dlnaSearch(timeout: Long = 6000) {
        DlnaManager.searchDevices(timeout)
    }

    private var _lastDlnaUrl: String = ""
    private var _lastDlnaTitle: String = ""

    fun dlnaCast(device: com.mediaplayer.plus.DlnaManager.DlnaDevice, url: String, title: String) {
        _selectedDlnaDevice = device
        _lastDlnaUrl = url
        _lastDlnaTitle = title
        val progress = player.state.value.currentPositionMs
        DlnaManager.castToDevice(device, url, title, startPositionMs = progress, onCastSuccess = { player.pause() })
    }

    private fun pushAlbumArtToDlna(device: com.mediaplayer.plus.DlnaManager.DlnaDevice, url: String, title: String, mediaId: String, artBytes: ByteArray?, durationMs: Long) {
        val castUrl = if (url.startsWith("http", ignoreCase = true)) url else DlnaManager.convertToCastUrl(url)
        artBytes?.let { bytes ->
            if (bytes.isEmpty()) return@let
            val artUrl = AlbumArtRegistry.register(mediaId, bytes, DlnaManager.currentHostIp)
            if (artUrl != null) {
                DlnaManager.setAlbumArtURI(device, castUrl, title, artUrl, durationMs)
            }
        }
    }

    private fun autoCastToSelected() {
        val device = _selectedDlnaDevice ?: return
        val url = dlnaGetCurrentUrl() ?: return
        val progress = player.state.value.currentPositionMs
        val title = dlnaGetCurrentTitle() ?: ""
        _lastDlnaUrl = url
        _lastDlnaTitle = title
        Log.d("PlayerVM", "autoCastToSelected: device=${device.name} url=$url progress=$progress")
        DlnaManager.stopProgressPolling()
        viewModelScope.launch {
            delay(1500)
            DlnaManager.castToDevice(device, url, title, startPositionMs = progress, onCastSuccess = {
                Log.d("PlayerVM", "autoCastToSelected: cast success, pausing local player")
                player.pause()
            })
        }
    }

    fun disconnectDlna() {
        _selectedDlnaDevice = null
        _lastDlnaUrl = ""
        _lastDlnaTitle = ""
        DlnaManager.stop()
        _dlnaCastStatus.value = com.mediaplayer.plus.DlnaManager.CastStatus.IDLE
        player.pause() // 防止断连后自动用扬声器续播（防泄密）
    }

    fun dlnaCastAuto(url: String, title: String) {
        val progress = player.state.value.currentPositionMs
        DlnaManager.castToAuto(url, title, startPositionMs = progress)
    }

    fun dlnaPlay() { DlnaManager.play() }
    fun dlnaPause() { DlnaManager.pause() }
    fun dlnaStop() { DlnaManager.stop(); _dlnaCastStatus.value = com.mediaplayer.plus.DlnaManager.CastStatus.IDLE }
    fun dlnaSeek(pos: Long) { DlnaManager.seek(pos) }
    fun dlnaSetVolume(vol: Int) { DlnaManager.setVolume(vol) }

    fun dlnaGetPlaybackUrl(item: MediaItem): String? {
        return item.getPlaybackUrl(smbHttpProxy)
    }

    fun dlnaGetCurrentUrl(): String? {
        val current = currentPlaylist.getOrNull(currentPlaylistIndex) ?: return null
        return dlnaGetPlaybackUrl(current)
    }

    fun dlnaGetCurrentTitle(): String {
        val title = state.value.title
        val artist = state.value.artist
        return if (!title.isNullOrEmpty()) {
            if (!artist.isNullOrEmpty()) "$artist - $title" else title
        } else if (!artist.isNullOrEmpty()) artist else ""
    }

    private val _uiBackgroundReview = MutableStateFlow(false)
    val uiBackgroundReview: StateFlow<Boolean> = _uiBackgroundReview.asStateFlow()

    enum class TvMode { AUTO, ON, OFF }
    private val _tvMode = MutableStateFlow(TvMode.AUTO)
    val tvMode: StateFlow<TvMode> = _tvMode.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _isRealTv = MutableStateFlow(false)
    val isRealTv: StateFlow<Boolean> = _isRealTv.asStateFlow()

    private val _lastPlayedItem = MutableStateFlow<MediaItem?>(null)
    val lastPlayedItem: StateFlow<MediaItem?> = _lastPlayedItem.asStateFlow()

    data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long = 0)
    private val _localFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val localFiles: StateFlow<List<FileEntry>> = _localFiles.asStateFlow()

    private val _currentDirPath = MutableStateFlow(android.os.Environment.getExternalStorageDirectory().absolutePath)
    val currentDirPath: StateFlow<String> = _currentDirPath.asStateFlow()

    private val localRootPath = android.os.Environment.getExternalStorageDirectory().absolutePath

    fun isLocalRoot(): Boolean = _currentDirPath.value.trimEnd('/') == localRootPath.trimEnd('/')
    fun goBackLocal() {
        if (!isLocalRoot()) {
            val parent = java.io.File(_currentDirPath.value).parent
            if (parent != null) browseLocalDirectory(parent)
        }
    }

    private val _smbEntries = MutableStateFlow<List<com.mediaplayer.plus.data.SmbEntry>>(emptyList())
    val smbEntries: StateFlow<List<com.mediaplayer.plus.data.SmbEntry>> = _smbEntries.asStateFlow()
    private val _currentSmbServer = MutableStateFlow<com.mediaplayer.plus.data.SmbServer?>(null)
    val currentSmbServer: StateFlow<com.mediaplayer.plus.data.SmbServer?> = _currentSmbServer.asStateFlow()
    private val _currentSmbPath = MutableStateFlow("")
    val currentSmbPath: StateFlow<String> = _currentSmbPath.asStateFlow()
    private val _smbBookmarks = MutableStateFlow<List<com.mediaplayer.plus.data.SmbBookmark>>(emptyList())
    val smbBookmarks: StateFlow<List<com.mediaplayer.plus.data.SmbBookmark>> = _smbBookmarks.asStateFlow()
    private val _isSmbLoading = MutableStateFlow(false)
    val isSmbLoading: StateFlow<Boolean> = _isSmbLoading.asStateFlow()
    private val _inSmbMode = MutableStateFlow(false)
    val inSmbMode: StateFlow<Boolean> = _inSmbMode.asStateFlow()
    private val _smbServers = MutableStateFlow<List<SmbServer>>(emptyList())
    val smbServers: StateFlow<List<SmbServer>> = _smbServers.asStateFlow()
    private val _isScanningSmb = MutableStateFlow(false)
    val isScanningSmb: StateFlow<Boolean> = _isScanningSmb.asStateFlow()
    private val _smbScanProgress = MutableStateFlow("")
    val smbScanProgress: StateFlow<String> = _smbScanProgress.asStateFlow()

    private val _lyricsFilterEnabled = MutableStateFlow(true)
    val lyricsFilterEnabled: StateFlow<Boolean> = _lyricsFilterEnabled.asStateFlow()
    private val _libassEnabled = MutableStateFlow(false)
    val libassEnabled: StateFlow<Boolean> = _libassEnabled.asStateFlow()
    private val _dlnaAlbumArtEnabled = MutableStateFlow(false)
    val dlnaAlbumArtEnabled: StateFlow<Boolean> = _dlnaAlbumArtEnabled.asStateFlow()
    private val _showOverlayPermissionDialog = MutableStateFlow(false)
    val showOverlayPermissionDialog: StateFlow<Boolean> = _showOverlayPermissionDialog.asStateFlow()
    private val _scanAllAudio = MutableStateFlow(true)
    val scanAllAudio: StateFlow<Boolean> = _scanAllAudio.asStateFlow()
    private val _scanFoldersAudio = MutableStateFlow<List<String>>(emptyList())
    val scanFoldersAudio: StateFlow<List<String>> = _scanFoldersAudio.asStateFlow()
    private val _scanAllVideo = MutableStateFlow(true)
    val scanAllVideo: StateFlow<Boolean> = _scanAllVideo.asStateFlow()
    private val _scanFoldersVideo = MutableStateFlow<List<String>>(emptyList())
    val scanFoldersVideo: StateFlow<List<String>> = _scanFoldersVideo.asStateFlow()

    private var sleepTimerJob: Job? = null

    sealed class Screen {
        object MusicLibrary : Screen()
        object VideoLibrary : Screen()
        object Player : Screen()
    }

    init {
        val prefs = application.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val isReal = isDeviceTv()
        _isRealTv.value = isReal
        val tvModeOrdinal = prefs.getInt("tv_mode", TvMode.AUTO.ordinal)
        val savedTvMode = TvMode.entries.getOrElse(tvModeOrdinal) { TvMode.AUTO }
        _tvMode.value = savedTvMode
        val layoutModeOrdinal = prefs.getInt("tv_layout_mode", LayoutMode.GRID.ordinal)
        _tvLayoutMode.value = LayoutMode.entries.getOrElse(layoutModeOrdinal) { LayoutMode.GRID }
        val isTv = when (savedTvMode) {
            TvMode.AUTO -> isReal
            TvMode.ON -> true
            TvMode.OFF -> false
        }
        _isTvMode.value = isTv
        if (!isTv) _currentScreen.value = Screen.Player else {
            _currentScreen.value = Screen.MusicLibrary
            val hasHistory = prefs.getString("last_path", null) != null || prefs.getBoolean("last_smb", false)
            _tvSelectedTab.value = if (hasHistory) "Recent" else "Video"
        }
        player.init(application)
        player.onFileEnd = { playNext(isAutoAdvance = true) }
        player.onMetadataParsed = { mediaId, title, artist, albumArt ->
            if (albumArt != null) albumArtCache[mediaId] = albumArt
            // DLNA 专辑图推送：元数据解析完成后再推送，确保 albumArtBytes 可用
            val dlnaDevice = _selectedDlnaDevice
            val castUrl = _lastDlnaUrl
            val castTitle = _lastDlnaTitle
            if (dlnaDevice != null && castUrl.isNotEmpty()) {
                val bytes = albumArt ?: player.state.value.albumArtBytes
                pushAlbumArtToDlna(dlnaDevice, castUrl, castTitle, mediaId, bytes, player.state.value.durationMs)
            }
            val idx = currentPlaylist.indexOfFirst { it.getIdentificationPath() == mediaId }
            if (idx != -1) {
                val old = currentPlaylist[idx]
                val updated = when (old) {
                    is SmbMediaItem -> old.copy(albumArtBytes = albumArt ?: old.albumArtBytes, artistName = artist ?: old.artistName, realTitle = title ?: old.realTitle)
                    is Song -> old.copy(albumArtBytes = albumArt ?: old.albumArtBytes, artist = artist ?: old.artist, title = title ?: old.title)
                    else -> old
                }
                if (updated !== old) updateItemInPlaylist(idx, updated)
            }
        }
        MusicServiceManager.onPlay = { play() }
        MusicServiceManager.onPause = { pause() }
        MusicServiceManager.onPlayPause = { togglePlayPause() }
        MusicServiceManager.onPrevious = { playPrevious() }
        MusicServiceManager.onNext = { playNext() }
        MusicServiceManager.onDismiss = { stopServiceAndRelease() }
        MusicServiceManager.onSeekTo = { pos -> player.seek(pos) }
        observePlayerState()
        loadSmbServers()
    }

    fun loadSmbServers() {
        _smbServers.value = smbManager.getServers()
        _smbBookmarks.value = smbManager.getBookmarks()
    }

    private fun observePlayerState() {
        viewModelScope.launch {
            state.collect { state -> updateNotification(state) }
        }
    }

    private fun updateNotification(state: PlayerState) {
        if (state.title.isNotBlank()) {
            val albumArt = if (state.isVideo) {
                val currentItem = currentPlaylist.getOrNull(currentPlaylistIndex)
                (currentItem as? Video)?.uri?.toString() ?: state.albumArtUrl
            } else state.albumArtUrl
            MusicServiceManager.sharedAlbumArtBytes = state.albumArtBytes
            MusicServiceManager.sharedAlbumArtBytesHash = state.albumArtBytes?.hashCode() ?: 0
            MusicServiceManager.update(getApplication(), state.title, state.artist, state.isPlaying, albumArt, state.currentPositionMs, state.durationMs, state.playbackSpeed)
        }
    }

    fun scanMedia() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                _scanAllAudio.value = prefs.getBoolean("scan_all_audio", true)
                _scanAllVideo.value = prefs.getBoolean("scan_all_video", true)
                _scanFoldersAudio.value = loadFolderList(prefs, "scan_folders_audio")
                _scanFoldersVideo.value = loadFolderList(prefs, "scan_folders_video")
                val s = if (_scanAllAudio.value) repository.getAllSongs() else repository.getSongsInFolders(_scanFoldersAudio.value)
                val v = if (_scanAllVideo.value) repository.getAllVideos() else repository.getVideosInFolders(_scanFoldersVideo.value)

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    restoreLastState(s, v)
                    _songs.value = s
                    _videos.value = v
                    _floatingLyricsEnabled.value = prefs.getBoolean("floating_lyrics_enabled", false)
                    if (_floatingLyricsEnabled.value && android.provider.Settings.canDrawOverlays(getApplication()) && !com.mediaplayer.plus.ui.FloatingLyricsService.isRunning.value) com.mediaplayer.plus.ui.FloatingLyricsService.start(getApplication())
                    _bluetoothLyricsEnabled.value = prefs.getBoolean("bluetooth_lyrics_enabled", false)
                    _uiBackgroundReview.value = prefs.getBoolean("ui_background_review", false)
                    _lyricsFilterEnabled.value = prefs.getBoolean("lyrics_filter_enabled", true)
                    (player as? MediaPlayerImpl)?.lyricsFilterEnabled = _lyricsFilterEnabled.value
                    _libassEnabled.value = prefs.getBoolean("libass_enabled", false)
                    player.setLibassEnabled(_libassEnabled.value)
                    _dlnaAlbumArtEnabled.value = prefs.getBoolean("dlna_album_art_enabled", false)
                    if (_isTvMode.value) _tvSelectedTab.value = if (_lastPlayedItem.value == null) "Video" else "Recent"
                }
            } catch (e: SecurityException) {
                Log.e("PlayerViewModel", "Permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Scan error: ${e.message}")
            }
        }
    }

    private fun saveLastState(path: String, type: String, smbItem: SmbMediaItem? = null, filterCtx: FilterContext = FilterContext()) {
        val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("last_path", path).putString("last_type", type).putString("last_filter_type", filterCtx.filterType).putString("last_filter_value", filterCtx.filterValue).putInt("last_playlist_index", currentPlaylistIndex)
        val paths = currentPlaylist.map { it.getIdentificationPath() }
        if (paths.isNotEmpty()) editor.putString("last_playlist_paths", paths.joinToString("\n")) else editor.remove("last_playlist_paths")
        if (smbItem != null) {
            editor.putBoolean("last_smb", true).putString("last_smb_serverId", smbItem.serverId).putString("last_smb_path", smbItem.smbPath).putString("last_smb_fileName", smbItem.fileName).putLong("last_smb_fileSize", smbItem.fileSize).putBoolean("last_smb_isVideo", smbItem.isVideoFile).putString("last_smb_host", smbItem.host).putString("last_smb_share", smbItem.share).putBoolean("last_smb_isGuest", smbItem.isGuest).putString("last_smb_username", smbItem.username).putString("last_smb_password", smbItem.password)
            val smbPlaylist = currentPlaylist.filterIsInstance<SmbMediaItem>()
            if (smbPlaylist.size > 1) {
                val arr = org.json.JSONArray()
                for (p in smbPlaylist) { val o = org.json.JSONObject(); o.put("serverId", p.serverId).put("smbPath", p.smbPath).put("fileName", p.fileName).put("fileSize", p.fileSize).put("isVideo", p.isVideoFile).put("host", p.host).put("share", p.share).put("isGuest", p.isGuest).put("username", p.username).put("password", p.password); arr.put(o) }
                editor.putString("last_smb_playlist", arr.toString()).putInt("last_smb_playlistIndex", currentPlaylistIndex)
            }
        } else editor.putBoolean("last_smb", false)
        editor.apply()
    }

    private fun saveShuffleRepeat(type: String) {
        val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("${type}_shuffle", _shuffleMode.value).putInt("${type}_repeat", _repeatMode.value).apply()
    }

    private fun restoreShuffleRepeat(type: String) {
        val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val s = prefs.getInt("${type}_shuffle", 0); val r = prefs.getInt("${type}_repeat", 0)
        _shuffleMode.value = s; _repeatMode.value = r; player.setShuffle(s == 1); player.setRepeatMode(RepeatMode.entries[r.coerceIn(0, RepeatMode.entries.size - 1)])
    }

    private fun restoreLastState(songs: List<Song>, videos: List<Video>) {
        if (isHandlingExternalUri) return
        val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("last_smb", false)) return
        val path = prefs.getString("last_path", null)
        if (path == null) {
            val ss = songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            val sv = videos.sortedBy { it.title.lowercase() }
            if (ss.isNotEmpty()) {
                originalPlaylist = ss; currentPlaylist = ss; currentPlaylistIndex = 0
                val song = ss[0]; _lastPlayedItem.value = song; player.load(song.path, song.title, song.artist, song.albumArtUrl, startPaused = true, duration = song.duration, isVideo = false)
                _playlistIndex.value = 1; _playlistTotal.value = ss.size; _currentPlaylist.value = ss; prefetchAdjacentSmbMetadata(0)
                _playlistVersion.value++ // 🌟 结构变化
            } else if (sv.isNotEmpty()) {
                originalPlaylist = sv; currentPlaylist = sv; currentPlaylistIndex = 0
                val video = sv[0]; _lastPlayedItem.value = video
                val (a, t) = parseVideoTitle(video.title)
                player.load(video.path, t, a, albumArtUrl = video.uri.toString(), startPaused = true, duration = video.duration, isVideo = true)
                _playlistIndex.value = 1; _playlistTotal.value = sv.size; _currentPlaylist.value = sv
            }
            return
        }
        val type = prefs.getString("last_type", null) ?: return
        val ft = prefs.getString("last_filter_type", "all") ?: "all"; val fv = prefs.getString("last_filter_value", "") ?: ""
        _lastFilterContext.value = FilterContext(ft, fv)
        val savedPaths = prefs.getString("last_playlist_paths", null)?.split("\n"); val savedIdx = prefs.getInt("last_playlist_index", -1)
        if (type == "song") {
            val fs = when (ft) { "folder" -> songs.filter { getParentFolderName(it.path) == fv }; "artist" -> songs.filter { it.artist == fv }; "album" -> songs.filter { it.album == fv }; else -> songs }
            val es = if (fs.isNotEmpty()) fs else songs; val idx = es.indexOfFirst { it.getIdentificationPath() == path }
            if (idx != -1) {
                currentType = "song"; originalPlaylist = es; restoreShuffleRepeat("song")
                if (_shuffleMode.value == 1 && savedPaths != null && savedIdx != -1) {
                    val rec = savedPaths.mapNotNull { p -> es.find { it.getIdentificationPath() == p } }
                    if (rec.isNotEmpty()) { currentPlaylist = rec; currentPlaylistIndex = if (savedIdx in 0 until rec.size) savedIdx else 0
                    } else { currentPlaylist = es; currentPlaylistIndex = idx; applyShuffleLogic(true) }
                } else { currentPlaylist = es; currentPlaylistIndex = idx; if (_shuffleMode.value == 1) applyShuffleLogic(true) }
                val item = currentPlaylist.getOrNull(currentPlaylistIndex)
                if (item != null) { _lastPlayedItem.value = item; player.load(item.getPlaybackUrl(smbHttpProxy), item.title, (item as? Song)?.artist, (item as? Song)?.albumArtUrl, startPaused = true, duration = item.duration, isVideo = false, mediaId = item.getIdentificationPath(), albumArtBytes = (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes) }
                _playlistIndex.value = currentPlaylistIndex + 1; _playlistTotal.value = currentPlaylist.size; _currentPlaylist.value = currentPlaylist; if (!_isTvMode.value) _currentScreen.value = Screen.Player; prefetchAdjacentSmbMetadata(currentPlaylistIndex)
                _playlistVersion.value++ // 🌟 结构变化
            }
        } else if (type == "video") {
            val fvds = if (ft == "folder") videos.filter { getParentFolderName(it.path) == fv } else videos
            val ev = if (fvds.isNotEmpty()) fvds else videos; val idx = ev.indexOfFirst { it.getIdentificationPath() == path }
            if (idx != -1) {
                currentType = "video"; originalPlaylist = ev; restoreShuffleRepeat("video")
                if (_shuffleMode.value == 1 && savedPaths != null && savedIdx != -1) {
                    val rec = savedPaths.mapNotNull { p -> ev.find { it.getIdentificationPath() == p } }
                    if (rec.isNotEmpty()) { currentPlaylist = rec; currentPlaylistIndex = if (savedIdx in 0 until rec.size) savedIdx else 0
                    } else { currentPlaylist = ev; currentPlaylistIndex = idx; applyShuffleLogic(true) }
                } else { currentPlaylist = ev; currentPlaylistIndex = idx; if (_shuffleMode.value == 1) applyShuffleLogic(true) }
                val item = currentPlaylist.getOrNull(currentPlaylistIndex)
                if (item != null) { _lastPlayedItem.value = item
                    val (a, t) = parseVideoTitle(item.title)
                    player.load(item.getPlaybackUrl(smbHttpProxy), t, a, item.uri.toString(), startPaused = true, duration = item.duration, isVideo = true, mediaId = item.getIdentificationPath()) }
                _playlistIndex.value = currentPlaylistIndex + 1; _playlistTotal.value = currentPlaylist.size; _currentPlaylist.value = currentPlaylist; if (!_isTvMode.value) _currentScreen.value = Screen.Player
                _playlistVersion.value++ // 🌟 结构变化
            }
        }
    }

    private fun getParentFolderName(f: String): String = java.io.File(f).parentFile?.name ?: ""

    // 视频文件名解析：将 "Artist - Title" 拆分为歌手+歌名
    // 仅当存在 " - "（空格-空格）分隔符时解析；纯文件名如 "(G)I-DLE" 不解析
    // 示例："(G)I-DLE - Crow (ASS)" → artist="(G)I-DLE", title="Crow (ASS)"
    private fun parseVideoTitle(title: String): Pair<String?, String> {
        val idx = title.indexOf(" - ")
        return if (idx >= 0) {
            val artist = title.substring(0, idx).trim()
            val songTitle = title.substring(idx + 3).trim()
            if (artist.isNotEmpty() && songTitle.isNotEmpty()) artist to songTitle
            else null to title
        } else {
            null to title
        }
    }

    private fun videoTitleAndArtist(title: String): Pair<String?, String> = parseVideoTitle(title)

    private fun playSongOrVideoFromList(list: List<Video>, index: Int) {
        val video = list[index]
        _lastPlayedItem.value = video
        val (artist, title) = videoTitleAndArtist(video.title)
        player.load(video.path, title, artist, albumArtUrl = video.uri.toString(), startPaused = true, duration = video.duration, isVideo = true)
    }
    fun navigateTo(s: Screen) { _currentScreen.value = s }
    fun resumeRecentItem(item: MediaItem) {
        viewModelScope.launch {
            val playbackUrl = item.getPlaybackUrl(smbHttpProxy); val id = item.getIdentificationPath(); val isVid = item.isVideo()
            val (artist, title) = if (isVid) parseVideoTitle(item.title) else (null to item.title)
            player.load(playbackUrl, title, artist ?: (item as? Song)?.artist, (item as? Song)?.albumArtUrl ?: if(isVid) item.uri.toString() else null, false, item.duration, isVid, id, (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes)
            originalPlaylist = listOf(item); currentPlaylist = listOf(item); currentPlaylistIndex = 0; _playlistIndex.value = 1; _playlistTotal.value = 1; _currentPlaylist.value = currentPlaylist
            saveLastState(id, if(isVid) "video" else "song", item as? SmbMediaItem); navigateTo(Screen.Player)
        }
    }

    fun exitPlayback(isTv: Boolean) { if (isTv && state.value.isVideo) { player.stop(); MusicServiceManager.stop(getApplication()) }; navigateTo(Screen.MusicLibrary) }
    fun setTvSelectedTab(t: String) { _tvSelectedTab.value = t; if (t != "Files") { _inSmbMode.value = false; _currentSmbServer.value = null } }
    fun setTvLayoutMode(m: LayoutMode) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putInt("tv_layout_mode", m.ordinal).apply(); _tvLayoutMode.value = m }
    fun browseLocalDirectory(p: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val root = android.os.Environment.getExternalStorageDirectory().absolutePath; val f = java.io.File(p)
                if (f.exists() && f.isDirectory) {
                    _currentDirPath.value = p; val entries = mutableListOf<FileEntry>()
                    if (p.trimEnd('/') == root.trimEnd('/') && _isTvMode.value) entries.add(FileEntry("网络共享 (SMB)", "::smb::", true))
                    val local = f.listFiles()?.map { FileEntry(it.name, it.absolutePath, it.isDirectory, it.length()) }?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
                    entries.addAll(local); _localFiles.value = entries
                }
            } catch (e: Exception) { Log.e("PlayerViewModel", "Browse error: ${e.message}") }
        }
    }

    fun setScanAllAudio(s: Boolean) { _scanAllAudio.value = s; getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("scan_all_audio", s).apply(); scanMedia() }
    fun setScanAllVideo(s: Boolean) { _scanAllVideo.value = s; getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("scan_all_video", s).apply(); scanMedia() }
    fun addScanFolderAudio(f: String) { val c = _scanFoldersAudio.value.toMutableList(); if (!c.contains(f)) { c.add(f); _scanFoldersAudio.value = c; saveScanFolders("scan_folders_audio", c); if (!_scanAllAudio.value) scanMedia() } }
    fun removeScanFolderAudio(f: String) { val c = _scanFoldersAudio.value.toMutableList(); c.remove(f); _scanFoldersAudio.value = c; saveScanFolders("scan_folders_audio", c); if (!_scanAllAudio.value) scanMedia() }
    fun addScanFolderVideo(f: String) { val c = _scanFoldersVideo.value.toMutableList(); if (!c.contains(f)) { c.add(f); _scanFoldersVideo.value = c; saveScanFolders("scan_folders_video", c); if (!_scanAllVideo.value) scanMedia() } }
    fun removeScanFolderVideo(f: String) { val c = _scanFoldersVideo.value.toMutableList(); c.remove(f); _scanFoldersVideo.value = c; saveScanFolders("scan_folders_video", c); if (!_scanAllVideo.value) scanMedia() }
    private fun saveScanFolders(k: String, f: List<String>) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putString(k, f.joinToString("\n")).apply() }
    private fun loadFolderList(p: android.content.SharedPreferences, k: String): List<String> = (p.getString(k, null) ?: "").split("\n").filter { it.isNotBlank() }

    fun setFloatingLyricsEnabled(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("floating_lyrics_enabled", e).apply(); _floatingLyricsEnabled.value = e; if (e) { if (android.provider.Settings.canDrawOverlays(getApplication())) com.mediaplayer.plus.ui.FloatingLyricsService.start(getApplication()) else _showOverlayPermissionDialog.value = true } else com.mediaplayer.plus.ui.FloatingLyricsService.stop(getApplication()) }
    fun setBluetoothLyricsEnabled(e: Boolean) { android.util.Log.d("BT-Lyrics", "setBluetoothLyricsEnabled: $e"); getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("bluetooth_lyrics_enabled", e).apply(); _bluetoothLyricsEnabled.value = e; if (!e) com.mediaplayer.plus.BluetoothLyricsManager.clearLyrics() }
    fun resetOverlayPermissionDialog() { _showOverlayPermissionDialog.value = false }
    fun setUiBackgroundReview(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("ui_background_review", e).apply(); _uiBackgroundReview.value = e }
    fun setLyricsFilterEnabled(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("lyrics_filter_enabled", e).apply(); _lyricsFilterEnabled.value = e; (player as? MediaPlayerImpl)?.lyricsFilterEnabled = e }
    fun setLibassEnabled(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("libass_enabled", e).apply(); _libassEnabled.value = e; player.setLibassEnabled(e) }
    fun setDlnaAlbumArtEnabled(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("dlna_album_art_enabled", e).apply(); _dlnaAlbumArtEnabled.value = e }

    fun playSong(songs: List<Song>, index: Int) {
        if (index < 0 || index >= songs.size) return
        originalPlaylist = songs; currentPlaylist = songs; currentPlaylistIndex = index; currentType = "song"; restoreShuffleRepeat("song")
        if (_shuffleMode.value == 1) applyShuffleLogic(true) else { _playlistIndex.value = index + 1; _playlistTotal.value = songs.size; _currentPlaylist.value = songs }
        _playlistVersion.value++ // 🌟 结构变化
        playFromCurrentPlaylist(currentPlaylistIndex); _currentScreen.value = Screen.Player
    }

    fun playVideo(videos: List<Video>, index: Int) {
        if (index < 0 || index >= videos.size) return
        originalPlaylist = videos; currentPlaylist = videos; currentPlaylistIndex = index; currentType = "video"; restoreShuffleRepeat("video")
        if (_shuffleMode.value == 1) applyShuffleLogic(true) else { _playlistIndex.value = index + 1; _playlistTotal.value = videos.size; _currentPlaylist.value = videos }
        _playlistVersion.value++ // 🌟 结构变化
        playFromCurrentPlaylist(currentPlaylistIndex); _currentScreen.value = Screen.Player
    }

    fun playSmbFile(isVid: Boolean, pl: List<SmbMediaItem>, idx: Int) {
        currentType = if (isVid) "video" else "song"; restoreShuffleRepeat(currentType); originalPlaylist = pl; currentPlaylist = pl; currentPlaylistIndex = idx
        if (_shuffleMode.value == 1) applyShuffleLogic(true) else { _playlistIndex.value = idx + 1; _playlistTotal.value = pl.size; _currentPlaylist.value = pl }
        _playlistVersion.value++ // 🌟 结构变化
        playFromCurrentPlaylist(currentPlaylistIndex); _currentScreen.value = Screen.Player
    }

    fun playFromPlaylist(index: Int) { if (index in 0 until currentPlaylist.size) playFromCurrentPlaylist(index) }

    private fun playFromCurrentPlaylist(index: Int, isAutoAdvance: Boolean = false) {
        val item = currentPlaylist[index]; currentPlaylistIndex = index; _playlistIndex.value = index + 1; _playlistTotal.value = currentPlaylist.size
        val playbackUrl = item.getPlaybackUrl(smbHttpProxy); val id = item.getIdentificationPath(); val isVid = item.isVideo()
        val isDlnaConnected = _selectedDlnaDevice != null
        saveLastState(id, if(isVid) "video" else "song", item as? SmbMediaItem, _lastFilterContext.value)
        if (isDlnaConnected) {
            if (item is SmbMediaItem) player.stop()
            val (artist, title) = if (isVid) parseVideoTitle(item.title) else (null to item.title)
            player.load(playbackUrl, title, artist ?: (item as? Song)?.artist ?: (item as? SmbMediaItem)?.artistName, (item as? Song)?.albumArtUrl ?: if(isVid) item.uri.toString() else null, true, item.duration, isVid, id, (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes)
            autoCastToSelected()
        } else {
            val wasPaused = isAutoAdvance && !state.value.isPlaying
            if (item is SmbMediaItem) player.stop()
            val (artist, title) = if (isVid) parseVideoTitle(item.title) else (null to item.title)
            player.load(playbackUrl, title, artist ?: (item as? Song)?.artist ?: (item as? SmbMediaItem)?.artistName, (item as? Song)?.albumArtUrl ?: if(isVid) item.uri.toString() else null, wasPaused, item.duration, isVid, id, (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes)
        }
        prefetchAdjacentSmbMetadata(index)
    }

    private val prefetchJobMap = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun prefetchAdjacentSmbMetadata(currentIndex: Int) {
        val list = currentPlaylist; if (list.isEmpty()) return
        val range = (currentIndex - 5)..(currentIndex + 5)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            range.forEach { i ->
                val idx = when { i < 0 -> list.size + i; i >= list.size -> i % list.size; else -> i }
                val item = list.getOrNull(idx)
                if (item is SmbMediaItem) {
                    val path = item.smbPath
                    val cachedArt = albumArtCache[path]
                    if (cachedArt != null) { if (item.albumArtBytes == null) updateItemInPlaylist(idx, item.copy(albumArtBytes = cachedArt)); return@forEach }
                    if (prefetchJobMap.containsKey(path)) return@forEach
                    val job = launch {
                        try {
                            val server = SmbServer(item.serverId, item.host, item.share, item.username, item.password, "", item.isGuest)
                            val ctx = smbManager.createContextForStream(server)
                            val smbFile = jcifs.smb.SmbFile(item.smbPath, ctx)
                            smbFile.inputStream.use { stream ->
                                val res = com.mediaplayer.plus.data.Id3Metadata.parse(stream)
                                if (res != null) {
                                    val updated = item.copy(albumArtBytes = res.albumArt, artistName = res.artist, realTitle = res.title)
                                    if (res.albumArt != null) albumArtCache[path] = res.albumArt
                                    updateItemInPlaylist(idx, updated)
                                }
                            }
                        } catch (e: Exception) { Log.w("Prefetch", "Failed for ${item.fileName}: ${e.message}") } finally { prefetchJobMap.remove(path) }
                    }
                    prefetchJobMap[path] = job
                }
            }
        }
    }

    private fun updateItemInPlaylist(index: Int, newItem: MediaItem) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            if (index in currentPlaylist.indices && currentPlaylist[index].getIdentificationPath() == newItem.getIdentificationPath()) {
                val newList = currentPlaylist.toMutableList(); newList[index] = newItem; currentPlaylist = newList; _currentPlaylist.value = newList
                if (index == currentPlaylistIndex) _lastPlayedItem.value = newItem
            }
        }
    }

    fun playNext(isAutoAdvance: Boolean = false) { if (currentPlaylist.isEmpty()) return; val next = (currentPlaylistIndex + 1) % currentPlaylist.size; if (next != currentPlaylistIndex) playFromCurrentPlaylist(next, isAutoAdvance); if (isAutoAdvance || state.value.isPlaying) { viewModelScope.launch { delay(200); player.play() } } }
    fun playPrevious() { if (currentPlaylist.isEmpty()) return; val prev = if (currentPlaylistIndex - 1 < 0) currentPlaylist.size - 1 else currentPlaylistIndex - 1; if (prev != currentPlaylistIndex) playFromCurrentPlaylist(prev) }
    fun stopServiceAndRelease() { player.stop(); MusicServiceManager.stop(getApplication()) }
    fun play() {
        val s = DlnaManager.castStatus.value
        if (s == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || s == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED) DlnaManager.play() else player.play()
    }
    fun pause() {
        val s = DlnaManager.castStatus.value
        if (s == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || s == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED) DlnaManager.pause() else player.pause()
    }
    fun togglePlayPause() {
        val s = DlnaManager.castStatus.value
        if (s == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || s == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED) { if (s == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING) DlnaManager.pause() else DlnaManager.play() } else player.togglePlayPause()
    }
    fun seek(p: Float) {
        val s = DlnaManager.castStatus.value
        val isDlna = s == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || s == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED
        val durationMs = if (isDlna) {
            val (_, dur) = _dlnaSmoothProgress.value
            if (dur > 0) dur else state.value.durationMs
        } else state.value.durationMs
        val pos = (p * durationMs).toLong().coerceAtLeast(0L)
        if (isDlna) {
            DlnaManager.seek(pos)
            val (_, dur) = _dlnaSmoothProgress.value
            _dlnaSmoothProgress.value = pos to dur
        } else {
            player.seek(pos)
        }
    }
    fun toggleShuffle() {
        val n = if (_shuffleMode.value == 0) 1 else 0; _shuffleMode.value = n; applyShuffleLogic(n == 1); player.setShuffle(n == 1); saveShuffleRepeat(currentType)
        val i = currentPlaylist.getOrNull(currentPlaylistIndex); if (i != null) saveLastState(i.getIdentificationPath(), currentType, i as? SmbMediaItem, _lastFilterContext.value)
    }

    private fun applyShuffleLogic(s: Boolean) {
        if (originalPlaylist.isEmpty()) return
        val c = currentPlaylist.getOrNull(currentPlaylistIndex)
        if (s) { val sh = originalPlaylist.shuffled().toMutableList(); if (c != null) { sh.remove(c); sh.add(0, c) }; currentPlaylist = sh; currentPlaylistIndex = 0
        } else { currentPlaylist = originalPlaylist; if (c != null) { val ni = originalPlaylist.indexOf(c); currentPlaylistIndex = if (ni != -1) ni else 0 } }
        _playlistIndex.value = currentPlaylistIndex + 1; _playlistTotal.value = currentPlaylist.size; _currentPlaylist.value = currentPlaylist
        _playlistVersion.value++ // 🌟 结构变化，增加版本号
    }

    fun setRepeatMode(m: RepeatMode) { _repeatMode.value = m.ordinal; player.setRepeatMode(m); saveShuffleRepeat(currentType) }
    fun toggleRepeat() { val n = when (state.value.repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }; setRepeatMode(n) }
    fun setSleepTimer(m: Int) { sleepTimerJob?.cancel(); if (m <= 0) return; sleepTimerJob = viewModelScope.launch { var ms = m * 60 * 1000L; while (ms > 0) { delay(1000); ms -= 1000 }; player.pause() } }
    fun setAudioFilter(f: String) { player.setAudioFilter(f) }
    fun setEqualizerBandLevel(b: Int, l: Int) { player.setEqualizerBandLevel(b, l) }
    fun setEqualizerPreset(p: Int) { player.setEqualizerPreset(p) }
    fun resetEqualizer() { player.resetEqualizer() }

    fun addSubtitleFile(uri: Uri) { player.addSubtitleFile(uri) }
    fun addAudioFile(uri: Uri) { player.addAudioFile(uri) }
    fun setSurface(s: Surface?) { player.setSurface(s) }
    fun selectAudioTrack(i: Int) { player.selectAudioTrack(i) }
    fun selectSubtitleTrack(i: Int) { player.selectSubtitleTrack(i) }
    fun setDecoderMode(m: String) { player.setDecoderMode(m) }
    fun cycleAspectRatio() { player.cycleAspectRatio() }
    fun refreshTrackList() { player.refreshTrackList() }
    fun setTvMode(m: TvMode) { val p = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE); p.edit().putInt("tv_mode", m.ordinal).apply(); _tvMode.value = m; _isTvMode.value = when (m) { TvMode.AUTO -> isRealTv.value; TvMode.ON -> true; TvMode.OFF -> false } }
    fun setInSmbMode(i: Boolean) { _inSmbMode.value = i }
    fun onFilterChange(t: String, v: String) { _lastFilterContext.value = FilterContext(t, v) }
    fun playLocalFile(f: FileEntry) { if (f.isDirectory) browseLocalDirectory(f.path) else { val isVid = isVideoFile(f.path); if (isVid) playVideo(videos.value, videos.value.indexOfFirst { it.path == f.path }.takeIf { it != -1 } ?: 0) else playSong(songs.value, songs.value.indexOfFirst { it.path == f.path }.takeIf { it != -1 } ?: 0) } }
    private fun isVideoFile(p: String): Boolean = p.lowercase().let { s -> listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm").any { s.endsWith(".$it") } }

    fun browseSmbServer(s: SmbServer, p: String) { _currentSmbServer.value = s; _currentSmbPath.value = p; _isSmbLoading.value = true; _inSmbMode.value = true; viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { val res = smbManager.listEntries(s, p); withContext(kotlinx.coroutines.Dispatchers.Main) { _smbEntries.value = res.getOrDefault(emptyList()); _isSmbLoading.value = false } } }
    fun scanLan() { if (_isScanningSmb.value) return; _isScanningSmb.value = true; _smbScanProgress.value = "准备扫描..."; viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { smbManager.scanLan({ _smbScanProgress.value = it }, { f -> viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) { val cur = _smbServers.value.toMutableList(); if (cur.none { it.host == f.host }) { cur.add(f); _smbServers.value = cur; smbManager.addServer(f) } } }) } catch (e: Exception) { Log.e("SmbScan", "Failed: ${e.message}") } finally { withContext(kotlinx.coroutines.Dispatchers.Main) { _isScanningSmb.value = false; _smbScanProgress.value = "扫描结束"; _smbServers.value = smbManager.getServers() } } } }
    fun addSmbServer(s: SmbServer) { smbManager.addServer(s); loadSmbServers() }
    fun exitSmb() { _inSmbMode.value = false; _currentSmbServer.value = null; _smbEntries.value = emptyList() }
    fun playSmbEntry(e: com.mediaplayer.plus.data.SmbEntry) {
        val s = currentSmbServer.value ?: return
        val items = smbEntries.value.filter { it.isMediaFile }.map { f -> SmbMediaItem(s.id, f.path, f.name, f.size, isVideoFile(f.name), s.host, s.share, s.isGuest, s.username, s.password) }
        val idx = items.indexOfFirst { it.smbPath == e.path }.coerceAtLeast(0)
        playSmbFile(isVideoFile(e.name), items, idx)
    }
    fun toggleSmbBookmark(s: SmbServer, p: String, l: String) { if (smbManager.isBookmarked(s.id, p)) smbManager.removeBookmarkByPath(s.id, p) else smbManager.addBookmark(s.id, p, l); loadSmbServers() }
    fun handleExternalUri(u: Uri) { isHandlingExternalUri = true; val path = u.path ?: return; val rawTitle = (android.net.Uri.decode(path.substringAfterLast("/"))).substringBeforeLast(".") ?: path.substringAfterLast("/"); val (artist, title) = parseVideoTitle(rawTitle); player.load(u.toString(), title, artist ?: "", startPaused = false); _currentScreen.value = Screen.Player }
    private fun isDeviceTv(): Boolean { val pm = getApplication<Application>().packageManager; return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.hardware.tv") || (getApplication<Application>().getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION }

    fun restoreSmbLastState(proxy: SmbHttpProxy) {
        val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("last_smb", false)) return
        currentType = if (prefs.getBoolean("last_smb_isVideo", false)) "video" else "song"; restoreShuffleRepeat(currentType)
        val pjson = prefs.getString("last_smb_playlist", null); val savedPaths = prefs.getString("last_playlist_paths", null)?.split("\n"); val savedIndex = prefs.getInt("last_playlist_index", -1)
        if (pjson != null) {
            try {
                val arr = org.json.JSONArray(pjson); val pl = mutableListOf<SmbMediaItem>()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); pl.add(SmbMediaItem(o.getString("serverId"), o.getString("smbPath"), o.getString("fileName"), o.getLong("fileSize"), o.getBoolean("isVideo"), o.getString("host"), o.getString("share"), o.getBoolean("isGuest"), o.optString("username", ""), o.optString("password", ""))) }
                originalPlaylist = pl
                if (_shuffleMode.value == 1 && savedPaths != null && savedIndex != -1) {
                    val rec = savedPaths.mapNotNull { p -> pl.find { it.smbPath == p } }
                    if (rec.isNotEmpty()) { currentPlaylist = rec; currentPlaylistIndex = if (savedIndex in 0 until rec.size) savedIndex else 0 } else { currentPlaylist = pl; currentPlaylistIndex = 0; applyShuffleLogic(true) }
                } else { currentPlaylist = pl; currentPlaylistIndex = if (savedIndex in 0 until pl.size) savedIndex else 0 }
                _playlistIndex.value = currentPlaylistIndex + 1; _playlistTotal.value = currentPlaylist.size; _currentPlaylist.value = currentPlaylist; _lastPlayedItem.value = currentPlaylist.getOrNull(currentPlaylistIndex)
            } catch (e: Exception) { Log.e("PlayerViewModel", "Failed to restore SMB playlist", e) }
        }
        if (currentPlaylist.isNotEmpty()) { playFromCurrentPlaylist(currentPlaylistIndex, isAutoAdvance = false); prefetchAdjacentSmbMetadata(currentPlaylistIndex) }
        if (!_isTvMode.value) _currentScreen.value = Screen.Player
    }
}
