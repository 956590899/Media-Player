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
import com.mediaplayer.plus.ui.KeepScreenOn
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
import com.mediaplayer.plus.ui.TvPlayerScreen
import com.mediaplayer.plus.ui.theme.PowerampTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    private var isInPipMode by mutableStateOf(false)

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
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
            val clampedRatio = ratio.coerceIn(0.418410f, 2.39f)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((clampedRatio * 1000).toInt(), 1000))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // 🌟 在 setContent 之前立即锁定横屏方向，避免竖屏→横屏的闪屏切换
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        val tvModeOrdinal = prefs.getInt("tv_mode", 0) // 0 = AUTO
        val isRealTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                || packageManager.hasSystemFeature("android.hardware.tv")
                || (getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val shouldBeTv = when (tvModeOrdinal) {
            1 -> true  // ON
            2 -> false // OFF
            else -> isRealTv // AUTO
        }
        if (shouldBeTv) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

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
                val decoderMode by viewModel.decoderMode.collectAsState()
                val currentScreen by viewModel.currentScreen.collectAsState()
                val localFiles by viewModel.localFiles.collectAsState()
                val currentDirPath by viewModel.currentDirPath.collectAsState()
                val recentlyPlayed by viewModel.lastPlayedItem.collectAsState()
                val smbServers by viewModel.smbServers.collectAsState()
                val isScanningSmb by viewModel.isScanningSmb.collectAsState()
                val smbScanProgress by viewModel.smbScanProgress.collectAsState()
                val smbBookmarks by viewModel.smbBookmarks.collectAsState()
                val tvSettingsVisible by viewModel.tvSettingsVisible.collectAsState()
                // 🌟 手机模式 SMB 浏览状态（跨 tab 保持）
                val phoneSmbServer by viewModel.phoneSmbServer.collectAsState()
                val phoneSmbPath by viewModel.phoneSmbPath.collectAsState()
                val phoneSmbEntries by viewModel.phoneSmbEntries.collectAsState()
                val phoneSmbLoading by viewModel.phoneSmbLoading.collectAsState()
                val phoneSmbError by viewModel.phoneSmbError.collectAsState()
                val smbEntries by viewModel.smbEntries.collectAsState()
                val currentSmbServer by viewModel.currentSmbServer.collectAsState()
                val isSmbLoading by viewModel.isSmbLoading.collectAsState()
                val smbCurrentPath by viewModel.currentSmbPath.collectAsState()
                val keepScreenOn by viewModel.keepScreenOn.collectAsState()
                val tvActiveBrowserType by viewModel.tvActiveBrowserType.collectAsState()
                val tvLayoutMode by viewModel.tvLayoutMode.collectAsState()
                val localRestoreState by viewModel.localRestoreState.collectAsState()
                val smbRestoreState by viewModel.smbRestoreState.collectAsState()
                var showTvPlaylist by remember { mutableStateOf(false) }

                KeepScreenOn(keepOn = keepScreenOn)

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
                        if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    }
                }

                val smbHttpProxy = remember { viewModel.smbManager.let { SmbHttpProxy(it) } }
                LaunchedEffect(Unit) { smbHttpProxy.start() }
                DisposableEffect(Unit) { onDispose { smbHttpProxy.stop(); viewModel.smbManager.clearContextCache() } }
                LaunchedEffect(smbHttpProxy) {
                    viewModel.smbHttpProxy = smbHttpProxy
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
                                onNext = { viewModel.playNext(isAutoAdvance = true) },
                                onPrevious = { viewModel.playPrevious() },
                                onToggleShuffle = { viewModel.toggleShuffle() },
                                onToggleRepeat = { viewModel.toggleRepeat() },
                                onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                                onSelectSubtitleTrack = { viewModel.selectSubtitleTrack(it) },
                                onSelectVideoTrack = { viewModel.selectVideoTrack(it) },
                                onDecoderChange = { viewModel.setSessionDecoderMode(it) },
                                onAspectRatioChange = { viewModel.cycleAspectRatio() },
                                onPipClick = { enterPip(playerState.videoAspectRatio) },
                                onRefreshTrackList = { viewModel.refreshTrackList() },
                                onImportSubtitle = { subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "text/x-ssa", "text/x-ass", "application/x-matroska", "video/*")) },
                                onImportAudioTrack = { audioTrackPicker.launch(arrayOf("audio/*")) },
                                onSurfaceCreated = { viewModel.setSurface(it) },
                                onSurfaceDestroyed = { viewModel.setSurface(null) },
                                videoSurface = movableVideoSurface,
                                onExitFullscreen = { viewModel.exitPlayback(true) },
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
                                onNext = { viewModel.playNext(isAutoAdvance = true) },
                                onPrevious = { viewModel.playPrevious() },
                                onShuffleToggle = { viewModel.toggleShuffle() },
                                onRepeatModeChange = { viewModel.setRepeatMode(it) },
                                onSettingsClick = { },
                                onBack = { viewModel.exitPlayback(true) },
                                onSurfaceCreated = { viewModel.setSurface(it) },
                                onSurfaceDestroyed = { viewModel.setSurface(null) },
                                playlistIndex = playlistIndex,
                                playlistTotal = playlistTotal,
                                onSeek = { viewModel.seek(it) },
                                onSeekMs = { viewModel.seekTo(it) },
                                isKeepScreenOn = keepScreenOn,
                                onToggleKeepScreenOn = { viewModel.toggleKeepScreenOn() },
                                showPlaylist = showTvPlaylist,
                                playlist = currentPlaylist,
                                onPlayFromPlaylist = { viewModel.playFromPlaylist(it) },
                                onPlaylistClick = { showTvPlaylist = true },
                                onClosePlaylist = { showTvPlaylist = false },
                                videoSurface = movableVideoSurface
                            )
                        }
                    } else {
                        // 🌟 完全匹配无错参数调用的 TvHomeScreen
                        TvHomeScreen(
                            songs = songs,
                            videos = videos,
                            localFiles = localFiles,
                            recentlyPlayed = recentlyPlayed,
                            smbServers = smbServers,
                            smbBookmarks = smbBookmarks,
                            isScanningSmb = isScanningSmb,
                            smbScanProgress = smbScanProgress,
                            currentDirPath = currentDirPath,
                            sectionOrder = viewModel.tvSectionOrder.value,
                            activeBrowserType = tvActiveBrowserType,
                            onActiveBrowserTypeChange = { viewModel.setTvActiveBrowserType(it) },
                            onSectionOrderChange = { viewModel.setTvSectionOrder(it) },
                            onSongClick = { list, index -> viewModel.playSong(list, index) },
                            onVideoClick = { list, index -> viewModel.playVideo(list, index) },
                            onRecentlyPlayedClick = { item -> viewModel.resumeRecentItem(item) },
                            onFileClick = { file ->
                                if (file.isDirectory) {
                                    viewModel.browseLocalDirectory(file.path)
                                } else {
                                    viewModel.playLocalFile(file)
                                }
                            },
                            onScanSmb = { viewModel.scanLan() },
                            onBrowseLocalDir = { path -> viewModel.browseLocalDirectory(path) },
                            onNavigateLocalUp = { viewModel.navigateLocalUp() },
                            onSettingsClick = { viewModel.setTvSettingsVisible(true) },
                            isLocalRoot = viewModel.isLocalRoot(),
                            onBrowseSmbServer = { server -> viewModel.browseSmbServer(server, "") },
                            smbEntries = smbEntries,
                            currentSmbServer = currentSmbServer,
                            currentSmbPath = smbCurrentPath,
                            isSmbLoading = isSmbLoading,
                            onSmbNavigateUp = { viewModel.navigateSmbUp() },
                            onSmbEntryClick = { entry ->
                                if (entry.isDirectory) {
                                    currentSmbServer?.let { viewModel.browseSmbServer(it, entry.path) }
                                } else {
                                    viewModel.playSmbEntry(entry)
                                }
                            },
                            onEnterSmbDirectory = { entry, scrollIdx, scrollOffset, focusedIdx ->
                                currentSmbServer?.let { viewModel.enterSmbDirectory(it, entry.path, scrollIdx, scrollOffset, focusedIdx) }
                            },
                            onNavigateSmbUpStack = { viewModel.navigateSmbUpStack() },
                            smbRestoreState = smbRestoreState,
                            isSmbBookmarked = currentSmbServer != null && smbBookmarks.any { it.serverId == currentSmbServer?.id && it.path == smbCurrentPath },
                            onToggleSmbBookmark = {
                                currentSmbServer?.let { server ->
                                    val label = if (smbCurrentPath.isEmpty()) server.displayName else smbCurrentPath.removeSuffix("/").substringAfterLast("/")
                                    viewModel.toggleSmbBookmark(server, smbCurrentPath, label)
                                }
                            },
                            onOpenPlaylist = { showTvPlaylist = true },
                            onBookmarkClick = { bookmark ->
                                val server = smbServers.find { it.id == bookmark.serverId }
                                if (server != null) {
                                    viewModel.browseSmbServer(server, bookmark.path)
                                }
                            },
                            playlistCount = currentPlaylist.size,
                            isPlaying = playerState.isPlaying,
                            showPlaylist = showTvPlaylist,
                            onTogglePlaylist = { showTvPlaylist = !showTvPlaylist },
                            currentPlaylist = currentPlaylist,
                            playlistIndex = playlistIndex - 1,
                            onPlayFromPlaylist = { viewModel.playFromPlaylist(it) },
                            tvLayoutMode = tvLayoutMode,
                            onTvLayoutModeChange = { viewModel.setTvLayoutMode(it) },
                            onEnterLocalDirectory = { entry, scrollIdx, scrollOffset, focusedIdx ->
                                viewModel.enterLocalDirectory(entry.path, scrollIdx, scrollOffset, focusedIdx)
                            },
                            onSaveLocalScrollPosition = { scrollIdx, scrollOffset, focusedIdx ->
                                viewModel.saveLocalScrollPosition(scrollIdx, scrollOffset, focusedIdx)
                            },
                            onSaveSmbScrollPosition = { scrollIdx, scrollOffset, focusedIdx ->
                                viewModel.saveSmbScrollPosition(scrollIdx, scrollOffset, focusedIdx)
                            },
                            localRestoreState = localRestoreState
                        )

                        if (tvSettingsVisible) {
                            SettingsScreen(
                                floatingLyricsEnabled = viewModel.floatingLyricsEnabled.value,
                                onFloatingLyricsToggle = { viewModel.setFloatingLyricsEnabled(it) },
                                bluetoothLyricsEnabled = viewModel.bluetoothLyricsEnabled.value,
                                onBluetoothLyricsToggle = { viewModel.setBluetoothLyricsEnabled(it) },
                                uiBackgroundReview = viewModel.uiBackgroundReview.value,
                                onUiBackgroundReviewToggle = { viewModel.setUiBackgroundReview(it) },
                                lyricsFilterEnabled = viewModel.lyricsFilterEnabled.value,
                                onLyricsFilterToggle = { viewModel.setLyricsFilterEnabled(it) },
                                libassEnabled = viewModel.libassEnabled.value,
                                onLibassToggle = { viewModel.setLibassEnabled(it) },
                                tvMode = viewModel.tvMode.value,
                                onTvModeChange = { viewModel.setTvMode(it) },
                                isTvMode = isTvMode,
                                decoderMode = decoderMode,
                                onDecoderModeChange = { viewModel.setPersistentDecoderMode(it) },
                                onBack = { viewModel.setTvSettingsVisible(false) }
                            )
                        }
                    }
                } else {
                    PlayerScreen(
                        state = playerState,
                        songs = songs,
                        videos = videos,
                        playlistIndex = playlistIndex,
                        playlistTotal = playlistTotal,
                        playlist = currentPlaylist,
                        onPlayFromPlaylist = { viewModel.playFromPlaylist(it) },
                        onPlayFromPlaylistSwipe = { viewModel.playFromPlaylistSwipe(it) },
                        onPlayPause = { viewModel.togglePlayPause() },
                        onNext = { viewModel.playNext(isAutoAdvance = true) },
                        onPrevious = { viewModel.playPrevious() },
                        onSeek = { viewModel.seek(it) },
                        onSeekMs = { viewModel.seekTo(it) },
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
                        smbManager = viewModel.smbManager,
                        smbServers = smbServers,
                        onPlaySmbFile = { isVideo, playlist, idx ->
                            viewModel.playSmbFile(
                                isVideo,
                                playlist,
                                idx
                            )
                        },
                        lastFilterType = filterContext.filterType,
                        lastFilterValue = filterContext.filterValue,
                        onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                        onSelectSubtitleTrack = { viewModel.selectSubtitleTrack(it) },
                        onSelectVideoTrack = { viewModel.selectVideoTrack(it) },
                        onDecoderChange = { viewModel.setSessionDecoderMode(it) },
                        onAspectRatioChange = { viewModel.cycleAspectRatio() },
                        onPipClick = { enterPip(playerState.videoAspectRatio) },
                        onRefreshTrackList = { viewModel.refreshTrackList() },
                        onImportSubtitle = { subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "text/x-ssa", "text/x-ass", "application/x-matroska", "video/*")) },
                        onImportAudioTrack = { audioTrackPicker.launch(arrayOf("audio/*")) },
                        onFilterChange = { type, value -> viewModel.onFilterChange(type, value) },
                        onRefreshSmbServers = { viewModel.loadSmbServers() },
                        onSmbScanClick = { viewModel.scanLan() },
                        // 🌟 手机模式 SMB 浏览状态（跨 tab 保持）
                        phoneSmbServer = phoneSmbServer,
                        phoneSmbPath = phoneSmbPath,
                        phoneSmbEntries = phoneSmbEntries,
                        phoneSmbLoading = phoneSmbLoading,
                        phoneSmbError = phoneSmbError,
                        phoneSmbBrowseStack = viewModel.phoneSmbBrowseStack.any(),
                        phoneSmbScrollIndex = { viewModel.phoneSmbScrollIndex.value },
                        phoneSmbScrollOffset = { viewModel.phoneSmbScrollOffset.value },
                        onPhoneBrowseSmbServer = { server, path -> viewModel.phoneBrowseSmbServer(server, path) },
                        onPhoneEnterSmbFolder = { entry -> viewModel.phoneEnterSmbFolder(entry) },
                        onPhoneGoBackSmb = { viewModel.phoneGoBackSmb() },
                        onPhoneCloseSmb = { viewModel.phoneCloseSmb() },
                        onPhoneSaveSmbScrollPosition = { index, offset -> viewModel.savePhoneSmbScrollPosition(index, offset) },
                        onCanvasSizeChanged = { w, h -> viewModel.player.setSubtitleDisplaySize(w, h) },
                        tvMode = tvMode,
                        onTvModeChange = { mode -> viewModel.setTvMode(mode) },
                        onEnterTvMode = { viewModel.setTemporaryTvMode(true) },
                        isScanningSmb = isScanningSmb,
                        smbScanProgress = smbScanProgress,
                        isInPipMode = isInPipMode,
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
        com.mediaplayer.plus.ui.FloatingLyricsService.visibleInApp = false
        if (viewModel.floatingLyricsEnabled.value &&
            Settings.canDrawOverlays(this) &&
            !com.mediaplayer.plus.ui.FloatingLyricsService.isRunning.value) {
            com.mediaplayer.plus.ui.FloatingLyricsService.start(this)
        }
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
        com.mediaplayer.plus.ui.FloatingLyricsService.visibleInApp = true
        try {
            unregisterReceiver(noisyAudioReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.isTvMode.value && viewModel.state.value.isVideo) {
            viewModel.player.stop()
            MusicServiceManager.stop(this)
        }
    }
}