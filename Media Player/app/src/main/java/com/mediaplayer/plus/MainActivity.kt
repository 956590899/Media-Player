package com.mediaplayer.plus

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.PictureInPictureParams
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.util.Rational
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaplayer.plus.data.SmbHttpProxy
import com.mediaplayer.plus.ui.FullScreenVideoOverlay
import com.mediaplayer.plus.ui.PlayerScreen
import com.mediaplayer.plus.ui.PlayerViewModel
import com.mediaplayer.plus.ui.SettingsScreen
import com.mediaplayer.plus.ui.TvHomeScreen
import com.mediaplayer.plus.ui.TvTab
import com.mediaplayer.plus.ui.TvPlayerScreen
import com.mediaplayer.plus.ui.theme.PowerampTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    private var isInPipMode by mutableStateOf(false)

    // =====================================================================
    // 外部音频设备断开时自动暂停（防止扬声器泄密）
    // 监听 ACTION_AUDIO_BECOMING_NOISY：蓝牙断开、有线耳机拔出、DLNA 断开等
    // 当系统检测到音频从非扬声器设备切回扬声器时触发
    // =====================================================================
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    // 有线耳机拔出 / 蓝牙断开 / 音频路由回到扬声器
                    if (viewModel.state.value.isPlaying) {
                        Log.i("AudioRoute", "External audio device disconnected, pausing to prevent speaker leak")
                        viewModel.pause()
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.scanMedia()
        }
    }

    private val subtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addSubtitleFile(it) }
    }

    private val audioTrackPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addAudioFile(it) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    private fun enterPip(ratio: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val clampedRatio = ratio.coerceIn(0.418410f, 2.39f) // Android PiP 限制比例
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((clampedRatio * 1000).toInt(), 1000))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🌟 核心优化：针对 Android 9+ 处理刘海屏沉浸式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            val list = mutableListOf<String>(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            if (Build.VERSION.SDK_INT >= 36) {
                list.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
            list.toTypedArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)

        handleIntent(intent)

        setContent {
            PowerampTheme {
                val songs by viewModel.songs.collectAsState()
                val videos by viewModel.videos.collectAsState()
                val playerState by viewModel.state.collectAsState()
                val playlistIndex by viewModel.playlistIndex.collectAsState()
                val playlistTotal by viewModel.playlistTotal.collectAsState()
                val currentPlaylist by viewModel.currentPlaylistFlow.collectAsState()
                val floatingEnabled by viewModel.floatingLyricsEnabled.collectAsState()
                val bluetoothLyricsEnabled by viewModel.bluetoothLyricsEnabled.collectAsState()
                val uiBackgroundReview by viewModel.uiBackgroundReview.collectAsState()
    val playlistVersion by viewModel.playlistVersion.collectAsState()
                val lyricsFilterEnabled by viewModel.lyricsFilterEnabled.collectAsState()
                val libassEnabled by viewModel.libassEnabled.collectAsState()
                val showPermissionDialog by viewModel.showOverlayPermissionDialog.collectAsState()
                val scanAllAudio by viewModel.scanAllAudio.collectAsState()
                val scanFoldersAudio by viewModel.scanFoldersAudio.collectAsState()
                val scanAllVideo by viewModel.scanAllVideo.collectAsState()
                val scanFoldersVideo by viewModel.scanFoldersVideo.collectAsState()
                val filterContext by viewModel.lastFilterContext.collectAsState()
                val tvMode by viewModel.tvMode.collectAsState()
                val dlnaCastStatus by viewModel.dlnaCastStatus.collectAsState()
                val dlnaDevices by viewModel.dlnaDevices.collectAsState()
                val dlnaSmoothProgress by viewModel.dlnaSmoothProgress.collectAsState()
                val isTvMode by viewModel.isTvMode.collectAsState()
                val isRealTv by viewModel.isRealTv.collectAsState()
                val currentScreen by viewModel.currentScreen.collectAsState()
                val tvSelectedTab by viewModel.tvSelectedTab.collectAsState()
                val localFiles by viewModel.localFiles.collectAsState()
                val currentDirPath by viewModel.currentDirPath.collectAsState()
                val recentlyPlayed by viewModel.lastPlayedItem.collectAsState()
                val tvLayoutMode by viewModel.tvLayoutMode.collectAsState()
                val smbServers by viewModel.smbServers.collectAsState()
                val isScanningSmb by viewModel.isScanningSmb.collectAsState()
                val smbScanProgress by viewModel.smbScanProgress.collectAsState()
                val smbBookmarks by viewModel.smbBookmarks.collectAsState()
                val smbEntries by viewModel.smbEntries.collectAsState()
                val currentSmbServer by viewModel.currentSmbServer.collectAsState()
                val currentSmbPath by viewModel.currentSmbPath.collectAsState()
                val isSmbLoading by viewModel.isSmbLoading.collectAsState()
                val inSmbMode by viewModel.inSmbMode.collectAsState()
                var showTvPlaylist by remember { mutableStateOf(false) }

                // 🌟 终极优化：将 movableVideoSurface 提升至 MainActivity 级别
                // 这样在 手机模式 (PlayerScreen) 与 TV 模式 (FullScreenVideoOverlay/TvPlayerScreen)
                // 之间切换时，底层 SurfaceView 物理实例得以跨模式保留，实现真正的无缝切屏，彻底解决黑屏问题。
                val movableVideoSurface = androidx.compose.runtime.remember {
                    androidx.compose.runtime.movableContentOf { isFullScreen: Boolean ->
                        com.mediaplayer.plus.ui.VideoSurfaceView(
                            isFullScreen = isFullScreen,
                            onSurfaceCreated = { viewModel.setSurface(it) },
                            onSurfaceDestroyed = { viewModel.setSurface(null) }
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.browseLocalDirectory(currentDirPath)
                }

                LaunchedEffect(isTvMode) {
                    viewModel.browseLocalDirectory(currentDirPath)
                    
                    val window = (this@MainActivity as android.app.Activity).window
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)

                    if (isTvMode) {
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        // 🌟 核心补正：如果判定为手机，强制拉回竖屏。
                        if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        // 🌟 沉浸式优化：手机模式也保持 DecorFitsSystemWindows(false)
                        // 确保状态栏透明且应用内容延伸到状态栏下方，配合 PlayerScreen 内部的 statusBarsPadding 使用
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                        // 强制使用亮色图标（匹配深色背景）
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    }
                }

                val smbHttpProxy = remember { viewModel.smbManager.let { SmbHttpProxy(it) } }
                LaunchedEffect(Unit) { smbHttpProxy.start() }
                DisposableEffect(Unit) { onDispose { smbHttpProxy.stop(); viewModel.smbManager.clearContextCache() } }
                // 将 proxy 注入 ViewModel，供切歌时创建新的代理 URL
                // 同时恢复上次播放的 SMB 文件状态
                LaunchedEffect(smbHttpProxy) {
                    viewModel.smbHttpProxy = smbHttpProxy
                    viewModel.restoreSmbLastState(smbHttpProxy)
                }

                if (showPermissionDialog) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    viewModel.resetOverlayPermissionDialog()
                }

                if (isTvMode) {
                    if (playerState.title.isNotBlank() && currentScreen == PlayerViewModel.Screen.Player) {
                        if (playerState.isVideo) {
                            FullScreenVideoOverlay(
                                state = playerState,
                                onPlayPause = { viewModel.togglePlayPause() },
                                onSeek = { viewModel.seek(it) },
                                onSeekByOffsetMs = { viewModel.player.seekByOffset(it) },
                                onSpeedChange = { viewModel.player.setSpeed(it) },
                                onNext = { viewModel.playNext() },
                                onPrevious = { viewModel.playPrevious() },
                                onToggleShuffle = { viewModel.toggleShuffle() },
                                onToggleRepeat = { viewModel.toggleRepeat() },
                                onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                                onSelectSubtitleTrack = { viewModel.selectSubtitleTrack(it) },
                                onDecoderChange = { viewModel.setDecoderMode(it) },
                                onAspectRatioChange = { viewModel.cycleAspectRatio() },
                                onPipClick = { enterPip(playerState.videoAspectRatio) },
                                onRefreshTrackList = { viewModel.refreshTrackList() },
                                onImportSubtitle = { subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "text/x-ssa", "text/x-ass", "application/x-matroska", "video/*")) },
                                onImportAudioTrack = { audioTrackPicker.launch(arrayOf("audio/*")) },
                                onSurfaceCreated = { viewModel.setSurface(it) },
                                onSurfaceDestroyed = { viewModel.setSurface(null) },
                                videoSurface = movableVideoSurface,
                                onExitFullscreen = { 
                                    viewModel.exitPlayback(true)
                                },
                                showBackArrow = false,
                                playlist = currentPlaylist,
                                playlistIndex = playlistIndex - 1,
                                onPlayFromPlaylist = { viewModel.playFromPlaylist(it) },
                                showPlaylist = showTvPlaylist,
                                onPlaylistClick = { showTvPlaylist = !showTvPlaylist }
                            )
                        } else {
                            TvPlayerScreen(
                                state = playerState,
                                onPlayPause = { viewModel.togglePlayPause() },
                                onNext = { viewModel.playNext() },
                                onPrevious = { viewModel.playPrevious() },
                                onShuffleToggle = { viewModel.toggleShuffle() },
                                onRepeatModeChange = { viewModel.setRepeatMode(it) },
                                onSettingsClick = { /* Can navigate to settings if needed, but for now just back */ },
                                onBack = { 
                                    viewModel.exitPlayback(true)
                                },
                                onSurfaceCreated = { viewModel.setSurface(it) },
                                onSurfaceDestroyed = { viewModel.setSurface(null) },
                                videoSurface = movableVideoSurface
                            )
                        }
                    } else {
                        TvHomeScreen(
                            songs = songs,
                            videos = videos,
                            localFiles = localFiles,
                            recentlyPlayed = recentlyPlayed,
                            currentDirPath = currentDirPath,
                            onSongClick = { list, index -> viewModel.playSong(list, index) },
                            onVideoClick = { list, index -> viewModel.playVideo(list, index) },
                            onRecentlyPlayedClick = { item -> viewModel.resumeRecentItem(item) },
                            onFileClick = { file ->
                                if (file.path == "::smb::") {
                                    // Handled inside TvHomeScreen state
                                } else {
                                    viewModel.playLocalFile(file)
                                }
                            },
                            onFileBack = { viewModel.goBackLocal() },
                            isLocalRoot = viewModel.isLocalRoot(),
                            smbServers = smbServers,
                            onSmbServerClick = { server, path ->
                                viewModel.browseSmbServer(server, path ?: "")
                            },
                            onSmbScanClick = { viewModel.scanLan() },
                            isScanningSmb = isScanningSmb,
                            onSmbAddClick = { viewModel.addSmbServer(it) },
                            onSmbBack = { viewModel.exitSmb() },
                            smbEntries = smbEntries,
                            currentSmbServer = currentSmbServer,
                            currentSmbPath = currentSmbPath,
                            smbBookmarks = smbBookmarks,
                            isSmbLoading = isSmbLoading,
                            onSmbEntryClick = { entry ->
                                if (entry.isDirectory) {
                                    viewModel.browseSmbServer(currentSmbServer!!, entry.path)
                                } else if (entry.isMediaFile) {
                                    viewModel.playSmbEntry(entry)
                                }
                            },
                            onSmbBookmarkToggle = { server, path, label ->
                                viewModel.toggleSmbBookmark(server, path, label)
                            },
                            tvMode = tvMode,
                            isRealTv = isRealTv,
                            onTvModeChange = { mode -> viewModel.setTvMode(mode) },
                            selectedTab = TvTab.entries.find { it.name == tvSelectedTab } ?: TvTab.Files,
                            onTabChange = { viewModel.setTvSelectedTab(it.name) },
                            layoutMode = tvLayoutMode,
                            onLayoutModeChange = { viewModel.setTvLayoutMode(it) },
                            inSmbMode = inSmbMode,
                            onInSmbModeChange = { viewModel.setInSmbMode(it) }
                        )
                    }
                } else {
                    // 彻底把界面控制权移交，让 PlayerScreen 内部自由切换主/副控制面
                    PlayerScreen(
                        state = playerState,
                        songs = songs,
                        videos = videos,
                        playlistIndex = playlistIndex,
                        playlistTotal = playlistTotal,
                        playlist = currentPlaylist,
                        onPlayFromPlaylist = { viewModel.playFromPlaylist(it) },
                        onPlayPause = { viewModel.togglePlayPause() },
                        onNext = { viewModel.playNext() },
                        onPrevious = { viewModel.playPrevious() },
                        onSeek = { viewModel.seek(it) },
                        onSeekByOffsetMs = { viewModel.player.seekByOffset(it) },
                        onSpeedChange = { viewModel.player.setSpeed(it) },
                        onSurfaceCreated = { viewModel.setSurface(it) },
                        onSurfaceDestroyed = { viewModel.setSurface(null) },
                        videoSurface = movableVideoSurface,
                        onSongClick = { list, index -> viewModel.playSong(list, index) },
                        onVideoClick = { list, index -> viewModel.playVideo(list, index) },
                        floatingLyricsEnabled = floatingEnabled,
                        onFloatingLyricsToggle = { viewModel.setFloatingLyricsEnabled(it) },
                        bluetoothLyricsEnabled = bluetoothLyricsEnabled,
                        onBluetoothLyricsToggle = { viewModel.setBluetoothLyricsEnabled(it) },
                        onRepeatModeChange = { viewModel.setRepeatMode(it) },
                        onShuffleToggle = { viewModel.toggleShuffle() },
                        onSetSleepTimer = { viewModel.setSleepTimer(it) },
                        onSetAudioFilter = { viewModel.setAudioFilter(it) },
                        onPresetSelect = { viewModel.setEqualizerPreset(it) },
                        onEqReset = { viewModel.resetEqualizer() },
                        onBandLevelChange = { band, level -> viewModel.setEqualizerBandLevel(band, level) },
                        uiBackgroundReview = uiBackgroundReview,
                        playlistVersion = playlistVersion,
                        onUiBackgroundReviewToggle = { viewModel.setUiBackgroundReview(it) },
                        lyricsFilterEnabled = lyricsFilterEnabled,
                        onLyricsFilterToggle = { viewModel.setLyricsFilterEnabled(it) },
                        libassEnabled = libassEnabled,
                        onLibassToggle = { viewModel.setLibassEnabled(it) },
                        // Scan folder settings
                        scanAllAudio = scanAllAudio,
                        scanFoldersAudio = scanFoldersAudio,
                        onSetScanAllAudio = { viewModel.setScanAllAudio(it) },
                        onAddScanFolderAudio = { viewModel.addScanFolderAudio(it) },
                        onRemoveScanFolderAudio = { viewModel.removeScanFolderAudio(it) },
                        scanAllVideo = scanAllVideo,
                        scanFoldersVideo = scanFoldersVideo,
                        onSetScanAllVideo = { viewModel.setScanAllVideo(it) },
                        onAddScanFolderVideo = { viewModel.addScanFolderVideo(it) },
                        onRemoveScanFolderVideo = { viewModel.removeScanFolderVideo(it) },
                        // SMB 局域网
                        smbManager = viewModel.smbManager,
                        smbServers = smbServers,
                        onPlaySmbFile = { isVideo, playlist, idx ->
                            viewModel.playSmbFile(
                                isVideo,
                                playlist,
                                idx
                            )
                        },
                        // 媒体库分页过滤
                        lastFilterType = filterContext.filterType,
                        lastFilterValue = filterContext.filterValue,
                        onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                        onSelectSubtitleTrack = { viewModel.selectSubtitleTrack(it) },
                        onDecoderChange = { viewModel.setDecoderMode(it) },
                        onAspectRatioChange = { viewModel.cycleAspectRatio() },
                        onPipClick = { enterPip(playerState.videoAspectRatio) },
                        onRefreshTrackList = { viewModel.refreshTrackList() },
                        onImportSubtitle = { subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "text/x-ssa", "text/x-ass", "application/x-matroska", "video/*")) },
                        onImportAudioTrack = { audioTrackPicker.launch(arrayOf("audio/*")) },
                        onFilterChange = { type, value -> viewModel.onFilterChange(type, value) },
                        onRefreshSmbServers = { viewModel.loadSmbServers() },
                        onSmbScanClick = { viewModel.scanLan() },
                        onCanvasSizeChanged = { w, h -> viewModel.player.setSubtitleDisplaySize(w, h) },
                        tvMode = tvMode,
                        onTvModeChange = { mode -> viewModel.setTvMode(mode) },
                        isScanningSmb = isScanningSmb,
                        smbScanProgress = smbScanProgress,
                        isInPipMode = isInPipMode,
                        // DLNA
                        dlnaCastStatus = dlnaCastStatus,
                        dlnaDevices = dlnaDevices,
                        dlnaSmoothProgress = dlnaSmoothProgress,
                        onDlnaCastClick = {
                            if (viewModel.dlnaCastStatus.value == DlnaManager.CastStatus.PLAYING || viewModel.dlnaCastStatus.value == DlnaManager.CastStatus.PAUSED) {
                                viewModel.disconnectDlna()
                            } else {
                                viewModel.dlnaSearch()
                            }
                        },
                        onDlnaDeviceSelect = { device ->
                            val url = viewModel.dlnaGetCurrentUrl()
                            if (url != null) viewModel.dlnaCast(device, url, viewModel.dlnaGetCurrentTitle())
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 在主界面内隐藏悬浮歌词，避免遮挡播放器
        com.mediaplayer.plus.ui.FloatingLyricsService.visibleInApp = false
        // 从悬浮窗权限设置页返回后，若权限已授予则自动启动服务
        if (viewModel.floatingLyricsEnabled.value &&
            Settings.canDrawOverlays(this) &&
            !com.mediaplayer.plus.ui.FloatingLyricsService.isRunning.value) {
            com.mediaplayer.plus.ui.FloatingLyricsService.start(this)
        }
        // 注册音频路由变化监听：外部设备断开时自动暂停（防泄密）
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(noisyAudioReceiver, filter)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                viewModel.handleExternalUri(uri)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开主界面后恢复悬浮歌词显示
        com.mediaplayer.plus.ui.FloatingLyricsService.visibleInApp = true
        try {
            unregisterReceiver(noisyAudioReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }
}