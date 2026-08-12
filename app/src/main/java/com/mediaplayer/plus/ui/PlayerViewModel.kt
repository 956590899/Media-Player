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
import com.mediaplayer.plus.data.SmbEntry
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
        set(value) {
            field = value
            if (value != null) {
                val item = _lastPlayedItem.value
                if (item is SmbMediaItem && (player.state.value.status == PlayerState.Status.PREPARING || player.state.value.status == PlayerState.Status.IDLE)) {
                     val (a, t) = if (item.isVideo()) parseVideoTitle(item.title) else (null to item.title)
                     player.load(
                        item.getPlaybackUrl(value), t, a ?: item.artistName,
                        if(item.isVideo()) item.uri.toString() else null,
                        startPaused = true, duration = item.duration, isVideo = item.isVideo(),
                        mediaId = item.getIdentificationPath(),
                        albumArtBytes = item.albumArtBytes
                    )
                }
            }
        }
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

    // TV section order for waterfall home page
    private val _tvSectionOrder = MutableStateFlow<List<TvSection>>(
        listOf(TvSection.Recent, TvSection.Video, TvSection.Music, TvSection.Files, TvSection.Settings)
    )
    val tvSectionOrder: StateFlow<List<TvSection>> = _tvSectionOrder.asStateFlow()

    // Whether TV settings page is showing (standalone overlay)
    private val _tvSettingsVisible = MutableStateFlow(false)
    val tvSettingsVisible: StateFlow<Boolean> = _tvSettingsVisible.asStateFlow()

    // 🌟 视频解码器模式：持久化设置（设置页修改）vs 会话级（全屏模式修改，仅当次生效）
    private val _decoderMode = MutableStateFlow("自动解码")
    val decoderMode: StateFlow<String> = _decoderMode.asStateFlow()
    private val _sessionDecoderMode = MutableStateFlow<String?>(null) // null = 使用持久化设置
    val sessionDecoderMode: StateFlow<String?> = _sessionDecoderMode.asStateFlow()
    /** 获取当前生效的解码器模式（会话级优先，其次持久化） */
    val effectiveDecoderMode: String get() = _sessionDecoderMode.value ?: _decoderMode.value

    // TV 模式活跃浏览器页面状态 (如 AllVideos, LocalFiles, Smb 等，null 表示瀑布流首页)
    private val _tvActiveBrowserType = MutableStateFlow<TvBrowserType?>(null)
    val tvActiveBrowserType: StateFlow<TvBrowserType?> = _tvActiveBrowserType.asStateFlow()

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
        loadDecoderMode()
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
        val durationMs = player.state.value.durationMs
        val state = player.state.value
        // 优先从当前播放列表项获取专辑图，其次从播放器状态
        val item = currentPlaylist.getOrNull(currentPlaylistIndex)
        val itemArt = (item as? Song)?.albumArtBytes ?: (item as? SmbMediaItem)?.albumArtBytes
        val effectiveArtBytes = itemArt ?: state.albumArtBytes ?: albumArtCache[state.mediaPath]
        val artUrl = if (effectiveArtBytes != null) {
            val id = item?.getIdentificationPath() ?: state.mediaPath
            if (id.isNotEmpty()) com.mediaplayer.plus.AlbumArtRegistry.register(id, effectiveArtBytes, DlnaManager.currentHostIp) else null
        } else null
        val isVideo = item?.isVideo() ?: (item as? SmbMediaItem)?.isVideoFile ?: state.isVideo
        DlnaManager.castToDeviceWithCover(
            device = device,
            url = url,
            title = title,
            albumArtUrl = artUrl,
            artist = (item as? Song)?.artist ?: (item as? SmbMediaItem)?.artistName,
            album = (item as? Song)?.album,
            duration = durationMs,
            startPositionMs = progress,
            isVideo = isVideo,
            onCastSuccess = {
                _dlnaSmoothProgress.value = progress to durationMs
                player.pause()
            }
        )
    }

    private fun autoCastToSelected(artBytes: ByteArray? = null, artist: String? = null, album: String? = null, mediaId: String? = null) {
        val device = _selectedDlnaDevice ?: return
        val url = dlnaGetCurrentUrl() ?: return
        val progress = player.state.value.currentPositionMs
        val title = dlnaGetCurrentTitle() ?: ""
        _lastDlnaUrl = url
        _lastDlnaTitle = title
        Log.d("PlayerVM", "autoCastToSelected: device=${device.name} url=$url progress=$progress")
        // 立即重置进度，避免切歌时显示旧进度
        _dlnaSmoothProgress.value = 0L to _dlnaSmoothProgress.value.second
        DlnaManager.stopProgressPolling()
        // 🌟 修复：使用 DlnaManager 的 applicationScope 代替 viewModelScope，
        // 确保后台锁屏、Activity 销毁后，切歌的延时投屏任务仍能执行。
        DlnaManager.applicationScope.launch {
            delay(1500)
            val state = player.state.value
            val durationMs = state.durationMs
            // 优先使用传入的专辑图（来自播放列表项），其次从播放器状态获取
            val effectiveArtBytes = artBytes ?: state.albumArtBytes ?: albumArtCache[state.mediaPath]
            val artUrl = if (effectiveArtBytes != null) {
                val id = mediaId ?: state.mediaPath
                if (id.isNotEmpty()) com.mediaplayer.plus.AlbumArtRegistry.register(id, effectiveArtBytes, DlnaManager.currentHostIp) else null
            } else null
            DlnaManager.castToDeviceWithCover(
                device = device,
                url = url,
                title = title,
                albumArtUrl = artUrl,
                artist = artist,
                album = album,
                duration = durationMs,
                startPositionMs = progress,
                isVideo = state.isVideo,
                onCastSuccess = {
                    _dlnaSmoothProgress.value = progress to durationMs
                    Log.d("PlayerVM", "autoCastToSelected: cast success, pausing local player")
                    player.pause()
                }
            )
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
        return state.value.title ?: ""
    }

    private val _uiBackgroundReview = MutableStateFlow(false)
    val uiBackgroundReview: StateFlow<Boolean> = _uiBackgroundReview.asStateFlow()

    enum class TvMode { AUTO, ON, OFF }
    private val _tvMode = MutableStateFlow(TvMode.AUTO)
    val tvMode: StateFlow<TvMode> = _tvMode.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _temporaryTvMode = MutableStateFlow(false)
    val temporaryTvMode: StateFlow<Boolean> = _temporaryTvMode.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _isRealTv = MutableStateFlow(false)
    val isRealTv: StateFlow<Boolean> = _isRealTv.asStateFlow()

    private val _lastPlayedItem = MutableStateFlow<MediaItem?>(null)
    val lastPlayedItem: StateFlow<MediaItem?> = _lastPlayedItem.asStateFlow()

    // 🌟 目录导航栈条目：记录进入子目录前的状态，返回时恢复
    data class DirectoryStackEntry(
        val path: String,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val focusedIndex: Int = 0
    )

    data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long = 0)
    private val _localFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val localFiles: StateFlow<List<FileEntry>> = _localFiles.asStateFlow()

    private val _currentDirPath = MutableStateFlow(android.os.Environment.getExternalStorageDirectory().absolutePath)
    val currentDirPath: StateFlow<String> = _currentDirPath.asStateFlow()

    private val localRootPath = android.os.Environment.getExternalStorageDirectory().absolutePath

    // 🌟 目录导航栈（本地文件）
    private val _dirStack = mutableListOf<DirectoryStackEntry>()
    val dirStack: List<DirectoryStackEntry> get() = _dirStack

    // 🌟 本地文件恢复状态（返回上一层时使用）
    private val _localRestoreState = MutableStateFlow<DirectoryStackEntry?>(null)
    val localRestoreState: StateFlow<DirectoryStackEntry?> = _localRestoreState.asStateFlow()

    // 🌟 SMB 目录导航栈
    private val _smbDirStack = mutableListOf<DirectoryStackEntry>()
    val smbDirStack: List<DirectoryStackEntry> get() = _smbDirStack

    // 🌟 SMB 恢复状态（返回上一层时使用）
    private val _smbRestoreState = MutableStateFlow<DirectoryStackEntry?>(null)
    val smbRestoreState: StateFlow<DirectoryStackEntry?> = _smbRestoreState.asStateFlow()

    // 🌟 手机模式搜索页 SMB 浏览专用状态（独立于 TV 模式，跨 tab 切换保持）
    private val _phoneSmbServer = MutableStateFlow<SmbServer?>(null)
    val phoneSmbServer: StateFlow<SmbServer?> = _phoneSmbServer.asStateFlow()
    private val _phoneSmbPath = MutableStateFlow("")
    val phoneSmbPath: StateFlow<String> = _phoneSmbPath.asStateFlow()
    private val _phoneSmbEntries = MutableStateFlow<List<SmbEntry>>(emptyList())
    val phoneSmbEntries: StateFlow<List<SmbEntry>> = _phoneSmbEntries.asStateFlow()
    private val _phoneSmbBrowseStack = mutableListOf<Pair<String, String>>() // (serverId, path)
    val phoneSmbBrowseStack: List<Pair<String, String>> get() = _phoneSmbBrowseStack
    private val _phoneSmbLoading = MutableStateFlow(false)
    val phoneSmbLoading: StateFlow<Boolean> = _phoneSmbLoading.asStateFlow()
    private val _phoneSmbError = MutableStateFlow<String?>(null)
    val phoneSmbError: StateFlow<String?> = _phoneSmbError.asStateFlow()
    // 🌟 手机模式 SMB 列表滚动位置（像素级恢复）
    private val _phoneSmbScrollIndex = MutableStateFlow(0)
    val phoneSmbScrollIndex: StateFlow<Int> = _phoneSmbScrollIndex.asStateFlow()
    private val _phoneSmbScrollOffset = MutableStateFlow(0)
    val phoneSmbScrollOffset: StateFlow<Int> = _phoneSmbScrollOffset.asStateFlow()

    fun savePhoneSmbScrollPosition(index: Int, offset: Int) {
        _phoneSmbScrollIndex.value = index
        _phoneSmbScrollOffset.value = offset
    }

    fun phoneBrowseSmbServer(server: SmbServer, path: String = "") {
        _phoneSmbServer.value = server
        _phoneSmbPath.value = path
        _phoneSmbScrollIndex.value = 0
        _phoneSmbScrollOffset.value = 0
        _phoneSmbLoading.value = true
        _phoneSmbError.value = null
        viewModelScope.launch {
            smbManager.listEntries(server, path).fold(
                onSuccess = { _phoneSmbEntries.value = it; _phoneSmbLoading.value = false },
                onFailure = { _phoneSmbError.value = it.message; _phoneSmbLoading.value = false }
            )
        }
    }

    fun phoneEnterSmbFolder(entry: SmbEntry) {
        val server = _phoneSmbServer.value ?: return
        _phoneSmbBrowseStack.add(server.id to _phoneSmbPath.value)
        phoneBrowseSmbServer(server, entry.path)
    }

    fun phoneGoBackSmb() {
        if (_phoneSmbBrowseStack.isNotEmpty()) {
            val (serverId, path) = _phoneSmbBrowseStack.removeAt(_phoneSmbBrowseStack.size - 1)
            val server = smbServers.value.find { it.id == serverId } ?: return
            phoneBrowseSmbServer(server, path)
        } else {
            _phoneSmbServer.value = null
            _phoneSmbEntries.value = emptyList()
            _phoneSmbPath.value = ""
            _phoneSmbBrowseStack.clear()
        }
    }

    fun phoneCloseSmb() {
        _phoneSmbServer.value = null
        _phoneSmbEntries.value = emptyList()
        _phoneSmbPath.value = ""
        _phoneSmbBrowseStack.clear()
    }

    fun phonePlaySmbEntry(entry: SmbEntry) {
        val s = _phoneSmbServer.value ?: return
        val items = _phoneSmbEntries.value.filter { it.isMediaFile }.map { f ->
            SmbMediaItem(s.id, f.path, f.name, f.size, isVideoFile(f.name), s.host, s.share, s.isGuest, s.username, s.password)
        }
        val idx = items.indexOfFirst { it.smbPath == entry.path }.coerceAtLeast(0)
        playSmbFile(isVideoFile(entry.name), items, idx)
    }

    fun isLocalRoot(): Boolean = _currentDirPath.value.trimEnd('/') == localRootPath.trimEnd('/')

    /** 进入子目录：保存当前状态到栈中 */
    fun enterLocalDirectory(newPath: String, scrollIndex: Int = 0, scrollOffset: Int = 0, focusedIndex: Int = 0) {
        _dirStack.add(DirectoryStackEntry(_currentDirPath.value, scrollIndex, scrollOffset, focusedIndex))
        browseLocalDirectory(newPath)
    }

    /** 返回上一层目录：弹出栈顶并恢复状态 */
    fun navigateLocalUp(): DirectoryStackEntry? {
        if (_dirStack.isNotEmpty()) {
            val last = _dirStack.removeAt(_dirStack.size - 1)
            browseLocalDirectory(last.path)
            _localRestoreState.value = last
            return last
        }
        _localRestoreState.value = null
        return null
    }

    /** 保存当前滚动位置（播放文件前调用，返回时恢复） */
    fun saveLocalScrollPosition(scrollIndex: Int, scrollOffset: Int = 0, focusedIndex: Int = 0) {
        _localRestoreState.value = DirectoryStackEntry(_currentDirPath.value, scrollIndex, scrollOffset, focusedIndex)
    }

    /** 保存 SMB 滚动位置（播放文件前调用） */
    fun saveSmbScrollPosition(scrollIndex: Int, scrollOffset: Int = 0, focusedIndex: Int = 0) {
        _smbRestoreState.value = DirectoryStackEntry(_currentSmbPath.value, scrollIndex, scrollOffset, focusedIndex)
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

        // TV section order
        val savedOrder = prefs.getString("tv_section_order", null)
        if (savedOrder != null) {
            val parts = savedOrder.split(",").mapNotNull { TvSection.entries.find { e -> e.name == it.trim() } }
            if (parts.size == TvSection.entries.size) _tvSectionOrder.value = parts
        }
        
        updateIsTvMode()
        val isTv = _isTvMode.value

        if (!isTv) _currentScreen.value = Screen.Player else {
            _currentScreen.value = Screen.MusicLibrary
            // TV 模式始终进入主页（文件管理器）
            _tvSelectedTab.value = "Files"
        }
        player.init(application)
        player.setDecoderMode(effectiveDecoderMode) // 🌟 应用持久化解码器设置
        player.onFileEnd = { playNext(isAutoAdvance = true) }
        player.onMetadataParsed = { mediaId, title, artist, albumArt ->
            if (albumArt != null) albumArtCache[mediaId] = albumArt
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
        MusicServiceManager.onNext = { playNext(isAutoAdvance = true) }
        MusicServiceManager.onDismiss = { stopServiceAndRelease() }
        MusicServiceManager.onSeekTo = { pos -> seekTo(pos) }
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
            // 🌟 核心修复：如果 DLNA 正在投屏且处于活跃状态，则由 DlnaManager 负责通知栏更新，
            // 避免 PlayerViewModel 因为本地播放器暂停而将通知栏强制刷回“暂停”状态。
            val dlnaStatus = DlnaManager.castStatus.value
            if (dlnaStatus == DlnaManager.CastStatus.PLAYING || dlnaStatus == DlnaManager.CastStatus.PAUSED) {
                return
            }

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
                    // 手机模式自动加载上次播放文件，TV 模式则不加载
                    if (!_isTvMode.value) {
                        restoreLastState(s, v)
                    }
                    _songs.value = s
                    _videos.value = v
                    _floatingLyricsEnabled.value = prefs.getBoolean("floating_lyrics_enabled", false)
                    if (_floatingLyricsEnabled.value && android.provider.Settings.canDrawOverlays(getApplication()) && !com.mediaplayer.plus.ui.FloatingLyricsService.isRunning.value) com.mediaplayer.plus.ui.FloatingLyricsService.start(getApplication())
                    _bluetoothLyricsEnabled.value = prefs.getBoolean("bluetooth_lyrics_enabled", true)
                    _uiBackgroundReview.value = prefs.getBoolean("ui_background_review", false)
                    _lyricsFilterEnabled.value = prefs.getBoolean("lyrics_filter_enabled", true)
                    (player as? MediaPlayerImpl)?.lyricsFilterEnabled = _lyricsFilterEnabled.value
                    _libassEnabled.value = prefs.getBoolean("libass_enabled", false)
                    player.setLibassEnabled(_libassEnabled.value)
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
            val smbPlaylist = originalPlaylist.filterIsInstance<SmbMediaItem>()
            if (smbPlaylist.isNotEmpty()) {
                try {
                    val root = org.json.JSONObject()
                    val server = org.json.JSONObject()
                    server.put("sid", smbItem.serverId).put("h", smbItem.host).put("sh", smbItem.share).put("g", smbItem.isGuest).put("u", smbItem.username).put("p", smbItem.password)
                    root.put("server", server)
                    val itemsArr = org.json.JSONArray()
                    for (p in smbPlaylist) {
                        val o = org.json.JSONObject()
                        o.put("p", p.smbPath).put("n", p.fileName).put("s", p.fileSize).put("v", p.isVideoFile)
                        itemsArr.put(o)
                    }
                    root.put("items", itemsArr)
                    editor.putString("last_smb_playlist", root.toString())
                } catch (e: Exception) {
                    Log.e("PlayerVM", "Failed to save SMB playlist JSON", e)
                }
            } else {
                editor.remove("last_smb_playlist")
            }
        } else {
            editor.putBoolean("last_smb", false)
            editor.remove("last_smb_playlist")
        }
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
        val isSmb = prefs.getBoolean("last_smb", false)
        val type = prefs.getString("last_type", null)
        val path = prefs.getString("last_path", null)
        val ft = prefs.getString("last_filter_type", "all") ?: "all"
        val fv = prefs.getString("last_filter_value", "") ?: ""
        _lastFilterContext.value = FilterContext(ft, fv)

        // 首次启动（无历史状态）时，自动加载第一首音频（准备但不播放）
        if (type == null) {
            val ss = songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            if (ss.isNotEmpty()) {
                originalPlaylist = ss; currentPlaylist = ss; currentPlaylistIndex = 0
                val song = ss[0]
                _lastPlayedItem.value = song
                player.load(song.path, song.title, song.artist, song.albumArtUrl, startPaused = true, duration = song.duration, isVideo = false)
                _playlistIndex.value = 1; _playlistTotal.value = ss.size; _currentPlaylist.value = ss; prefetchAdjacentSmbMetadata(0)
                _playlistVersion.value++
                saveLastState(song.getIdentificationPath(), "song")
            }
            return
        }

        if (isSmb) {
            val pjson = prefs.getString("last_smb_playlist", null)
            if (pjson != null) {
                try {
                    val pl = mutableListOf<SmbMediaItem>()
                    if (pjson.startsWith("{")) {
                        // 新的紧凑格式
                        val root = org.json.JSONObject(pjson)
                        val s = root.getJSONObject("server")
                        val itemsArr = root.getJSONArray("items")
                        val sid = s.getString("sid"); val h = s.getString("h"); val sh = s.getString("sh"); val g = s.getBoolean("g")
                        val u = s.optString("u", ""); val p = s.optString("p", "")
                        for (i in 0 until itemsArr.length()) {
                            val o = itemsArr.getJSONObject(i)
                            pl.add(SmbMediaItem(sid, o.getString("p"), o.getString("n"), o.getLong("s"), o.getBoolean("v"), h, sh, g, u, p))
                        }
                    } else {
                        // 旧格式兼容
                        val arr = org.json.JSONArray(pjson)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            pl.add(SmbMediaItem(
                                o.getString("serverId"), o.getString("smbPath"), o.getString("fileName"),
                                o.getLong("fileSize"), o.getBoolean("isVideo"), o.getString("host"),
                                o.getString("share"), o.getBoolean("isGuest"), o.optString("username", ""),
                                o.optString("password", "")
                            ))
                        }
                    }
                    if (pl.isNotEmpty()) {
                        // 🌟 恢复时按文件名排序（顺序播放的基础）
                        val sortedPl = pl.sortedBy { it.fileName.lowercase() }
                        val idx = sortedPl.indexOfFirst { it.getIdentificationPath() == path }.coerceAtLeast(0)
                        playMediaList(sortedPl, idx, isVideo = type == "video", startPaused = true)
                        return
                    }
                } catch (e: Exception) { Log.e("PlayerViewModel", "Failed to restore SMB playlist", e) }
            }
            
            // Fallback for single SMB item
            val sid = prefs.getString("last_smb_serverId", null)
            val spath = prefs.getString("last_smb_path", null)
            if (sid != null && spath != null && path != null) {
                val item = SmbMediaItem(
                    sid, spath, prefs.getString("last_smb_fileName", "") ?: "",
                    prefs.getLong("last_smb_fileSize", 0), prefs.getBoolean("last_smb_isVideo", false),
                    prefs.getString("last_smb_host", "") ?: "", prefs.getString("last_smb_share", "") ?: "",
                    prefs.getBoolean("last_smb_isGuest", false), prefs.getString("last_smb_username", "") ?: "",
                    prefs.getString("last_smb_password", "") ?: ""
                )
                playMediaList(listOf(item), 0, isVideo = type == "video", startPaused = true)
            }
            return
        }

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
        if (type == "song") {
            val fs = when (ft) { "folder" -> songs.filter { getParentFolderName(it.path) == fv }; "artist" -> songs.filter { it.artist == fv }; "album" -> songs.filter { it.album == fv }; else -> songs }
            val es = if (fs.isNotEmpty()) fs else songs; val idx = es.indexOfFirst { it.getIdentificationPath() == path }
            if (idx != -1) {
                playMediaList(es, idx, isVideo = false, startPaused = true)
            }
        } else if (type == "video") {
            val fvds = if (ft == "folder") videos.filter { getParentFolderName(it.path) == fv } else videos
            val ev = if (fvds.isNotEmpty()) fvds else videos; val idx = ev.indexOfFirst { it.getIdentificationPath() == path }
            if (idx != -1) {
                playMediaList(ev, idx, isVideo = true, startPaused = true)
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
        // 🌟 核心优化：TV 模式下点击“最近播放”时，优先尝试恢复完整的播放列表上下文
        // 而不是只创建一个包含单文件的列表，解决重启后播放列表丢失的问题。
        
        // 1. 如果当前已经加载了匹配的列表且正在播放该项，直接切回播放器
        val currentIdx = currentPlaylist.indexOfFirst { it.getIdentificationPath() == item.getIdentificationPath() }
        if (currentIdx != -1) {
            if (currentIdx == currentPlaylistIndex && player.state.value.status != PlayerState.Status.IDLE) {
                navigateTo(Screen.Player)
                return
            }
            playFromCurrentPlaylist(currentIdx, startPaused = false)
            navigateTo(Screen.Player)
            return
        }

        // 2. 如果不匹配，尝试从持久化历史中完整恢复
        val prefs = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val lastPath = prefs.getString("last_path", null)
        if (lastPath == item.getIdentificationPath()) {
            restoreLastState(_songs.value, _videos.value)
            // 恢复后默认是暂停的，既然是用户点击 Resume，则强制开始播放
            player.play()
            navigateTo(Screen.Player)
            return
        }

        // 3. 保底逻辑：单文件加载（通常不应走到这里）
        viewModelScope.launch {
            val playbackUrl = item.getPlaybackUrl(smbHttpProxy); val id = item.getIdentificationPath(); val isVid = item.isVideo()
            val (artist, title) = if (isVid) parseVideoTitle(item.title) else (null to item.title)
            player.load(playbackUrl, title, artist ?: (item as? Song)?.artist, (item as? Song)?.albumArtUrl ?: if(isVid) item.uri.toString() else null, false, item.duration, isVid, id, (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes)
            originalPlaylist = listOf(item); currentPlaylist = listOf(item); currentPlaylistIndex = 0; _playlistIndex.value = 1; _playlistTotal.value = 1; _currentPlaylist.value = currentPlaylist
            saveLastState(id, if(isVid) "video" else "song", item as? SmbMediaItem); navigateTo(Screen.Player)
        }
    }

    fun exitPlayback(isTv: Boolean) { 
        val wasTemporary = _temporaryTvMode.value
        if (isTv && state.value.isVideo) { 
            player.stop()
            MusicServiceManager.stop(getApplication()) 
        } 
        _temporaryTvMode.value = false
        updateIsTvMode()
        if (wasTemporary) {
            navigateTo(Screen.Player)
        } else {
            navigateTo(Screen.MusicLibrary)
        }
    }
    fun setTvSelectedTab(t: String) { _tvSelectedTab.value = t; if (t != "Files") { _inSmbMode.value = false; _currentSmbServer.value = null } }
    fun setTvLayoutMode(m: LayoutMode) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putInt("tv_layout_mode", m.ordinal).apply(); _tvLayoutMode.value = m }
    fun setTvSectionOrder(order: List<TvSection>) {
        getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .edit().putString("tv_section_order", order.joinToString(",") { it.name }).apply()
        _tvSectionOrder.value = order
    }
    fun setTvSettingsVisible(v: Boolean) { _tvSettingsVisible.value = v }
    fun setTvActiveBrowserType(type: TvBrowserType?) { _tvActiveBrowserType.value = type }
    fun browseLocalDirectory(p: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val root = android.os.Environment.getExternalStorageDirectory().absolutePath; val f = java.io.File(p)
                if (f.exists() && f.isDirectory) {
                    _currentDirPath.value = p; val entries = mutableListOf<FileEntry>()
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
    fun setBluetoothLyricsEnabled(e: Boolean) { android.util.Log.d("BT-Lyrics", "setBluetoothLyricsEnabled: $e"); com.mediaplayer.plus.BluetoothLyricsManager.setEnabled(getApplication(), e); _bluetoothLyricsEnabled.value = e }
    fun resetOverlayPermissionDialog() { _showOverlayPermissionDialog.value = false }
    fun setUiBackgroundReview(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("ui_background_review", e).apply(); _uiBackgroundReview.value = e }
    fun setLyricsFilterEnabled(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("lyrics_filter_enabled", e).apply(); _lyricsFilterEnabled.value = e; (player as? MediaPlayerImpl)?.lyricsFilterEnabled = e }
    fun setLibassEnabled(e: Boolean) { getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putBoolean("libass_enabled", e).apply(); _libassEnabled.value = e; player.setLibassEnabled(e) }

    fun playSong(songs: List<Song>, index: Int) {
        playMediaList(songs, index, isVideo = false)
    }

    fun playVideo(videos: List<Video>, index: Int) {
        playMediaList(videos, index, isVideo = true)
    }

    fun playSmbFile(isVid: Boolean, pl: List<SmbMediaItem>, idx: Int) {
        playMediaList(pl, idx, isVideo = isVid)
    }

    private fun playMediaList(items: List<MediaItem>, index: Int, isVideo: Boolean, startPaused: Boolean = false) {
        if (index < 0 || index >= items.size) return
        originalPlaylist = items
        currentPlaylist = items
        currentPlaylistIndex = index
        currentType = if (isVideo) "video" else "song"
        restoreShuffleRepeat(currentType)
        
        if (_shuffleMode.value == 1) {
            applyShuffleLogic(true)
        } else {
            _playlistIndex.value = index + 1
            _playlistTotal.value = items.size
            _currentPlaylist.value = items
        }
        _playlistVersion.value++
        playFromCurrentPlaylist(currentPlaylistIndex, startPaused = startPaused)
        _currentScreen.value = Screen.Player
    }

    fun playFromPlaylist(index: Int) { if (index in 0 until currentPlaylist.size) playFromCurrentPlaylist(index) }
    fun playFromPlaylistSwipe(index: Int) { if (index in 0 until currentPlaylist.size) playFromCurrentPlaylist(index, isAutoAdvance = true) }

    private fun playFromCurrentPlaylist(index: Int, isAutoAdvance: Boolean = false, startPaused: Boolean? = null) {
        if (index !in currentPlaylist.indices) return
        val item = currentPlaylist[index]; currentPlaylistIndex = index; _playlistIndex.value = index + 1; _playlistTotal.value = currentPlaylist.size
        val playbackUrl = item.getPlaybackUrl(smbHttpProxy); val id = item.getIdentificationPath(); val isVid = item.isVideo()
        val isDlnaConnected = _selectedDlnaDevice != null
        
        // 🌟 记录最后播放的项，并保存状态
        _lastPlayedItem.value = item
        saveLastState(id, if(isVid) "video" else "song", item as? SmbMediaItem, _lastFilterContext.value)
        
        // 🌟 统一修复：startPaused 如果没传，则根据是否自动切换和当前播放状态决定
        val effectiveStartPaused = startPaused ?: (isAutoAdvance && !state.value.isPlaying)

        if (isDlnaConnected) {
            val (artist, title) = if (isVid) parseVideoTitle(item.title) else (null to item.title)
            val itemArtist = artist ?: (item as? Song)?.artist ?: (item as? SmbMediaItem)?.artistName
            val itemAlbumArt = (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes
            player.load(playbackUrl, title, itemArtist, (item as? Song)?.albumArtUrl ?: if(isVid) item.uri.toString() else null, true, item.duration, isVid, id, itemAlbumArt)
            autoCastToSelected(artBytes = itemAlbumArt, artist = itemArtist, album = (item as? Song)?.album, mediaId = id)
        } else {
            val (artist, title) = if (isVid) parseVideoTitle(item.title) else (null to item.title)
            player.load(playbackUrl, title, artist ?: (item as? Song)?.artist ?: (item as? SmbMediaItem)?.artistName, (item as? Song)?.albumArtUrl ?: if(isVid) item.uri.toString() else null, effectiveStartPaused, item.duration, isVid, id, (item as? SmbMediaItem)?.albumArtBytes ?: (item as? Song)?.albumArtBytes)
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
            val id = newItem.getIdentificationPath()
            
            // 1. 更新当前活跃列表（打乱或顺序）
            if (index in currentPlaylist.indices && currentPlaylist[index].getIdentificationPath() == id) {
                val newList = currentPlaylist.toMutableList()
                newList[index] = newItem
                currentPlaylist = newList
                _currentPlaylist.value = newList
                if (index == currentPlaylistIndex) _lastPlayedItem.value = newItem
            }
            
            // 2. 🌟 必须同步更新原始备份列表，否则切换随机模式时会回退到旧数据
            val orgIdx = originalPlaylist.indexOfFirst { it.getIdentificationPath() == id }
            if (orgIdx != -1) {
                val newOrgList = originalPlaylist.toMutableList()
                newOrgList[orgIdx] = newItem
                originalPlaylist = newOrgList
            }
        }
    }

    fun playNext(isAutoAdvance: Boolean = false) {
        if (currentPlaylist.isEmpty()) return
        if (state.value.repeatMode == RepeatMode.ONE) {
            playFromCurrentPlaylist(currentPlaylistIndex, isAutoAdvance)
        } else {
            val next = currentPlaylistIndex + 1
            if (next >= currentPlaylist.size) {
                if (state.value.repeatMode == RepeatMode.ALL) {
                    playFromCurrentPlaylist(0, isAutoAdvance)
                } else {
                    // 🌟 TV 模式下，若单曲/列表自然播放完毕（无下一首），自动退出播放并恢复之前的浏览界面
                    if (_isTvMode.value && isAutoAdvance) {
                        exitPlayback(true)
                    }
                }
            } else {
                playFromCurrentPlaylist(next, isAutoAdvance)
            }
        }
    }
    fun playPrevious(isAutoAdvance: Boolean = true) { if (currentPlaylist.isEmpty()) return; val prev = if (currentPlaylistIndex - 1 < 0) currentPlaylist.size - 1 else currentPlaylistIndex - 1; if (prev != currentPlaylistIndex) playFromCurrentPlaylist(prev, isAutoAdvance) }
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
        val durationMs = if (DlnaManager.castStatus.value == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || DlnaManager.castStatus.value == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED) {
            val (_, dur) = _dlnaSmoothProgress.value
            if (dur > 0) dur else state.value.durationMs
        } else state.value.durationMs
        seekTo((p * durationMs).toLong().coerceAtLeast(0L))
    }

    fun seekTo(pos: Long) {
        val s = DlnaManager.castStatus.value
        val isDlna = s == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || s == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED
        if (isDlna) {
            DlnaManager.seek(pos)
            val (_, dur) = _dlnaSmoothProgress.value
            _dlnaSmoothProgress.value = pos to dur
        } else {
            player.seek(pos)
        }
    }
    fun toggleShuffle() {
        val n = if (_shuffleMode.value == 0) 1 else 0
        _shuffleMode.value = n
        // 🌟 统一应用随机逻辑：基于 originalPlaylist 构建新的 currentPlaylist
        applyShuffleLogic(n == 1)
        player.setShuffle(n == 1)
        saveShuffleRepeat(currentType)
        
        // 保存状态，确保下次启动时能正确恢复当前的随机顺序或顺序
        val i = currentPlaylist.getOrNull(currentPlaylistIndex)
        if (i != null) {
            saveLastState(i.getIdentificationPath(), currentType, i as? SmbMediaItem, _lastFilterContext.value)
        }
    }

    private fun applyShuffleLogic(s: Boolean) {
        if (originalPlaylist.isEmpty()) return
        val c = currentPlaylist.getOrNull(currentPlaylistIndex)
        if (s) {
            val sh = originalPlaylist.shuffled().toMutableList()
            if (c != null) {
                sh.removeAll { it.getIdentificationPath() == c.getIdentificationPath() }
                sh.add(0, c)
            }
            currentPlaylist = sh; currentPlaylistIndex = 0
        } else {
            currentPlaylist = originalPlaylist
            if (c != null) {
                val ni = originalPlaylist.indexOfFirst { it.getIdentificationPath() == c.getIdentificationPath() }
                currentPlaylistIndex = if (ni != -1) ni else 0
            }
        }
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
    fun selectVideoTrack(i: Int) { player.selectVideoTrack(i) }
    fun setDecoderMode(m: String) { player.setDecoderMode(m) }
    /** 🌟 设置页修改解码器模式（持久化，重启后生效） */
    fun setPersistentDecoderMode(mode: String) {
        val p = getApplication<Application>().getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
        p.edit().putString("decoder_mode", mode).apply()
        _decoderMode.value = mode
        player.setDecoderMode(mode)
    }
    /** 🌟 全屏模式修改解码器（仅当次会话生效，不持久化） */
    fun setSessionDecoderMode(mode: String) {
        _sessionDecoderMode.value = mode
        player.setDecoderMode(mode)
    }
    /** 🌟 加载持久化解码器设置（init 调用） */
    private fun loadDecoderMode() {
        val p = getApplication<Application>().getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
        _decoderMode.value = p.getString("decoder_mode", "自动解码") ?: "自动解码"
    }
    fun cycleAspectRatio() { player.cycleAspectRatio() }
    fun refreshTrackList() { player.refreshTrackList() }
    
    private fun updateIsTvMode() {
        _isTvMode.value = _temporaryTvMode.value || when (_tvMode.value) {
            TvMode.AUTO -> _isRealTv.value
            TvMode.ON -> true
            TvMode.OFF -> false
        }
    }

    fun setTvMode(m: TvMode) { 
        val p = getApplication<Application>().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        p.edit().putInt("tv_mode", m.ordinal).apply()
        _tvMode.value = m
        updateIsTvMode()
    }

    fun setTemporaryTvMode(enabled: Boolean) {
        _temporaryTvMode.value = enabled
        updateIsTvMode()
    }

    fun toggleKeepScreenOn() {
        _keepScreenOn.value = !_keepScreenOn.value
    }
    fun setInSmbMode(i: Boolean) { _inSmbMode.value = i }
    fun onFilterChange(t: String, v: String) { _lastFilterContext.value = FilterContext(t, v) }
    fun playLocalFile(f: FileEntry) { if (f.isDirectory) browseLocalDirectory(f.path) else { val isVid = isVideoFile(f.path); if (isVid) playVideo(videos.value, videos.value.indexOfFirst { it.path == f.path }.takeIf { it != -1 } ?: 0) else playSong(songs.value, songs.value.indexOfFirst { it.path == f.path }.takeIf { it != -1 } ?: 0) } }
    private fun isVideoFile(p: String): Boolean = p.lowercase().let { s ->
        listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m3u8", "ts", "m2ts", "vob", "rmvb").any { s.endsWith(".$it") }
    }

    fun browseSmbServer(s: SmbServer, p: String) {
        _currentSmbServer.value = s
        _currentSmbPath.value = p
        _isSmbLoading.value = true
        _inSmbMode.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = smbManager.listEntries(s, p)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _smbEntries.value = res.getOrDefault(emptyList())
                _isSmbLoading.value = false
            }
        }
    }

    /** 进入 SMB 子目录：保存当前状态到栈中 */
    fun enterSmbDirectory(server: SmbServer, newPath: String, scrollIndex: Int = 0, scrollOffset: Int = 0, focusedIndex: Int = 0) {
        _smbDirStack.add(DirectoryStackEntry(_currentSmbPath.value, scrollIndex, scrollOffset, focusedIndex))
        browseSmbServer(server, newPath)
    }

    /** 返回上一层 SMB 目录：弹出栈顶并恢复状态 */
    fun navigateSmbUpStack(): DirectoryStackEntry? {
        val s = _currentSmbServer.value ?: return null
        if (_smbDirStack.isNotEmpty()) {
            val last = _smbDirStack.removeAt(_smbDirStack.size - 1)
            browseSmbServer(s, last.path)
            _smbRestoreState.value = last
            return last
        }
        // 🌟 栈为空时退出 SMB 浏览，回到设备列表页
        exitSmb()
        _smbRestoreState.value = null
        return null
    }

    fun navigateSmbUp() {
        val s = _currentSmbServer.value ?: return
        val currentPath = _currentSmbPath.value
        // 根目录或服务器根路径时退出 SMB 浏览
        if (currentPath.isEmpty() || currentPath == s.smbRoot || currentPath == s.smbRoot + "/") {
            exitSmb()
        } else {
            // 计算父目录路径
            val parentPath = currentPath.removeSuffix("/").substringBeforeLast("/")
            browseSmbServer(s, parentPath)
        }
    }
    fun scanLan() {
        if (_isScanningSmb.value) return
        _isScanningSmb.value = true
        _smbScanProgress.value = "准备扫描..."
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                smbManager.scanLan(
                    onProgress = { progress ->
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _smbScanProgress.value = progress
                        }
                    },
                    onFound = { server ->
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val cur = _smbServers.value.toMutableList()
                            if (cur.none { it.host == server.host }) {
                                cur.add(server)
                                _smbServers.value = cur
                                smbManager.addServer(server)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _smbScanProgress.value = "扫描失败: ${e.message}"
                }
            } finally {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _isScanningSmb.value = false
                    _smbServers.value = smbManager.getServers()
                }
            }
        }
    }
    fun addSmbServer(s: SmbServer) { smbManager.addServer(s); loadSmbServers() }
    fun exitSmb() { _inSmbMode.value = false; _currentSmbServer.value = null; _smbEntries.value = emptyList(); _smbDirStack.clear() }
    fun playSmbEntry(e: com.mediaplayer.plus.data.SmbEntry) {
        val s = currentSmbServer.value ?: return
        val items = smbEntries.value.filter { it.isMediaFile }.map { f -> SmbMediaItem(s.id, f.path, f.name, f.size, isVideoFile(f.name), s.host, s.share, s.isGuest, s.username, s.password) }
        val idx = items.indexOfFirst { it.smbPath == e.path }.coerceAtLeast(0)
        playSmbFile(isVideoFile(e.name), items, idx)
    }
    fun toggleSmbBookmark(s: SmbServer, p: String, l: String) {
        if (smbManager.isBookmarked(s.id, p)) {
            smbManager.removeBookmarkByPath(s.id, p)
        } else {
            smbManager.addBookmark(s.id, p, l)
        }
        loadSmbServers()
    }
    fun handleExternalUri(u: Uri) { isHandlingExternalUri = true; val path = u.path ?: return; val rawTitle = (android.net.Uri.decode(path.substringAfterLast("/"))).substringBeforeLast(".") ?: path.substringAfterLast("/"); val (artist, title) = parseVideoTitle(rawTitle); player.load(u.toString(), title, artist ?: "", startPaused = false); _currentScreen.value = Screen.Player }
    private fun isDeviceTv(): Boolean { val pm = getApplication<Application>().packageManager; return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.hardware.tv") || (getApplication<Application>().getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION }
}
