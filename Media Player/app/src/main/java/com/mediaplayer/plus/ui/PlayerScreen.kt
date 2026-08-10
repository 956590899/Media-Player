package com.mediaplayer.plus.ui

import android.util.Log
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Tv as OutlinedTv
import androidx.compose.material.icons.outlined.SmartDisplay as OutlinedSmartDisplay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediaplayer.plus.data.*
import com.mediaplayer.plus.player.VideoScaleMode
import com.mediaplayer.plus.player.LyricEntry
import com.mediaplayer.plus.player.PlayerState
import com.mediaplayer.plus.player.RepeatMode
import kotlin.math.absoluteValue

// 声明常驻底栏对应的 5 项状态
enum class PlayerTab {
    None, MusicLibrary, VideoLibrary, Search, AudioEffects, Settings
}

@Composable
fun PlayerScreen(
    state: PlayerState,
    songs: List<Song>,
    videos: List<Video>,
    playlistIndex: Int = 1,
    playlistTotal: Int = 1,
    playlist: List<MediaItem> = emptyList(),
    onPlayFromPlaylist: (Int) -> Unit = {},
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekByOffsetMs: (Long) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onVideoClick: (List<Video>, Int) -> Unit,
    floatingLyricsEnabled: Boolean,
    onFloatingLyricsToggle: (Boolean) -> Unit,
    bluetoothLyricsEnabled: Boolean = false,
    onBluetoothLyricsToggle: (Boolean) -> Unit = {},
    onRepeatModeChange: (RepeatMode) -> Unit = {},
    onShuffleToggle: () -> Unit = {},
    onSetSleepTimer: (Int) -> Unit = {},
    onSetAudioFilter: (String) -> Unit = {},
    onPresetSelect: (Int) -> Unit = {},
    onEqReset: () -> Unit = {},
    onBandLevelChange: (Int, Int) -> Unit = { _, _ -> },
    uiBackgroundReview: Boolean = false,
    playlistVersion: Int = 0,
    onUiBackgroundReviewToggle: (Boolean) -> Unit = {},
    lyricsFilterEnabled: Boolean = true,
    onLyricsFilterToggle: (Boolean) -> Unit = {},
    libassEnabled: Boolean = false,
    onLibassToggle: (Boolean) -> Unit = {},
    // Scan folder settings
    scanAllAudio: Boolean = true,
    scanFoldersAudio: List<String> = emptyList(),
    onSetScanAllAudio: (Boolean) -> Unit = {},
    onAddScanFolderAudio: (String) -> Unit = {},
    onRemoveScanFolderAudio: (String) -> Unit = {},
    scanAllVideo: Boolean = true,
    scanFoldersVideo: List<String> = emptyList(),
    onSetScanAllVideo: (Boolean) -> Unit = {},
    onAddScanFolderVideo: (String) -> Unit = {},
    onRemoveScanFolderVideo: (String) -> Unit = {},
    // SMB 局域网支持
    smbManager: SmbManager? = null,
    smbHttpProxy: SmbHttpProxy? = null,
    smbServers: List<SmbServer> = emptyList(),
    onPlaySmbFile: (isVideo: Boolean, playlist: List<SmbMediaItem>, idx: Int) -> Unit = { _, _, _ -> },
    // 媒体库分页过滤上下文
    lastFilterType: String = "all",
    lastFilterValue: String = "",
    onSelectAudioTrack: (Int) -> Unit = {},
    onSelectSubtitleTrack: (Int) -> Unit = {},
    onDecoderChange: (String) -> Unit = {},
    onAspectRatioChange: () -> Unit = {},
    onPipClick: () -> Unit = {},
    onRefreshTrackList: () -> Unit = {},
    onImportSubtitle: () -> Unit = {},
    onImportAudioTrack: () -> Unit = {},
    onFilterChange: (filterType: String, filterValue: String) -> Unit = { _, _ -> },
    onRefreshSmbServers: () -> Unit = {},
    onSmbScanClick: () -> Unit = {},
    onCanvasSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    tvMode: PlayerViewModel.TvMode = PlayerViewModel.TvMode.AUTO,
    onTvModeChange: (PlayerViewModel.TvMode) -> Unit = {},
    videoSurface: @Composable (Boolean) -> Unit,
    isInPipMode: Boolean = false,
    isScanningSmb: Boolean = false,
    smbScanProgress: String = "",
    // DLNA 投屏
    dlnaCastStatus: com.mediaplayer.plus.DlnaManager.CastStatus = com.mediaplayer.plus.DlnaManager.CastStatus.IDLE,
    dlnaDevices: List<com.mediaplayer.plus.DlnaManager.DlnaDevice> = emptyList(),
    dlnaSmoothProgress: Pair<Long, Long> = 0L to 0L,
    onDlnaCastClick: () -> Unit = {},
    onDlnaDeviceSelect: (com.mediaplayer.plus.DlnaManager.DlnaDevice) -> Unit = {}
) {
    val themeColor = Color(state.themeColor)

    // 背景色 800ms 柔和渐变过渡
    val animatedBackgroundColor by animateColorAsState(
        targetValue = Color(state.themeColor).copy(alpha = 0.85f),
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "BackgroundColorAnimation"
    )
    val context = LocalContext.current

    var activeTab by remember { mutableStateOf(PlayerTab.None) }
    var lyricsFullscreen by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var isVideoFullscreen by rememberSaveable { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(true) }
    var isInForeground by remember { mutableStateOf(true) }
    var lastResumeTime by remember { mutableStateOf(0L) }
    var showDlnaPicker by remember { mutableStateOf(false) }

    // 前台/后台监听
    DisposableEffect(Unit) {
        val activity = (context as? androidx.activity.ComponentActivity)
        if (activity != null) {
            activity.lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) isInForeground = false
                    else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                        lastResumeTime = System.currentTimeMillis()
                        isInForeground = true
                    }
                }
            })
        }
        onDispose { }
    }

    DisposableEffect(isVideoFullscreen) {
        toggleSystemFullScreen(context, isVideoFullscreen)
        onDispose {
            if (isVideoFullscreen) {
                toggleSystemFullScreen(context, false)
            }
        }
    }

    BackHandler(enabled = isVideoFullscreen) { isVideoFullscreen = false }
    BackHandler(enabled = showDlnaPicker) { showDlnaPicker = false }
    BackHandler(enabled = activeTab != PlayerTab.None && !isVideoFullscreen) { activeTab = PlayerTab.None }
    BackHandler(enabled = lyricsFullscreen) { lyricsFullscreen = false }
    BackHandler(enabled = showPlaylist) { showPlaylist = false }

    if ((isVideoFullscreen || isInPipMode) && state.isVideo) {
        Box(modifier = Modifier.fillMaxSize()) {
            FullScreenVideoOverlay(
                state = state,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onSeekByOffsetMs = onSeekByOffsetMs,
                onSpeedChange = onSpeedChange,
                onNext = onNext,
                onPrevious = onPrevious,
                onToggleShuffle = onShuffleToggle,
                onToggleRepeat = {
                    val nextMode = when (state.repeatMode) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    onRepeatModeChange(nextMode)
                },
                onSelectAudioTrack = onSelectAudioTrack,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                onDecoderChange = onDecoderChange,
                onAspectRatioChange = onAspectRatioChange,
                onPipClick = onPipClick,
                onRefreshTrackList = onRefreshTrackList,
                onImportSubtitle = onImportSubtitle,
                onImportAudioTrack = onImportAudioTrack,
                onSurfaceCreated = onSurfaceCreated,
                onSurfaceDestroyed = onSurfaceDestroyed,
                onExitFullscreen = { isVideoFullscreen = false },
                onPlaylistClick = { showPlaylist = true },
                videoSurface = videoSurface,
                isInPipMode = isInPipMode,
                playlist = playlist,
                playlistIndex = playlistIndex,
                onPlayFromPlaylist = onPlayFromPlaylist
            )

            if (showPlaylist) {
                AnimatedVisibility(
                    visible = showPlaylist,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd).width(360.dp).padding(bottom = 135.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).padding(top = 40.dp, bottom = 40.dp, start = 12.dp, end = 12.dp)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "播放列表", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showPlaylist = false }) { Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp)) }
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize(), state = rememberLazyListState(if (playlistIndex > 0) playlistIndex - 1 else 0)) {
                                itemsIndexed(playlist) { index, item ->
                                    val isCurrent = (index == playlistIndex - 1)
                                    Column(modifier = Modifier.fillMaxWidth().clickable { onPlayFromPlaylist(index); showPlaylist = false }.padding(horizontal = 8.dp, vertical = 8.dp).background(if (isCurrent) Color(0xFF00E5FF).copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))) {
                                        Text(text = item.title, color = if (isCurrent) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (isCurrent) { Spacer(modifier = Modifier.height(2.dp)); Text(text = "正在播放", color = Color(0xFF00E5FF).copy(alpha = 0.6f), fontSize = 10.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(if (isVideoFullscreen || isInPipMode) Color.Transparent else animatedBackgroundColor)) {
        KeepScreenOn(keepOn = (state.isVideo && state.isPlaying) || (lyricsFullscreen && state.isPlaying))

        if (!isVideoFullscreen && !isInPipMode) {
            BlurredBackground(artworkUri = state.albumArtUrl, artworkBytes = state.albumArtBytes, themeColor = themeColor)
        }

        Box(modifier = Modifier.fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))))

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = if (state.isVideo) Alignment.Center else Alignment.TopCenter) {
                    if (state.isVideo) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(state.nativeAspectRatio).background(Color.Transparent).pointerInput(Unit) { detectTapGestures(onDoubleTap = { isVideoFullscreen = true }) }.pointerInput(Unit) {
                                    var totalDragX = 0f; var totalDragY = 0f
                                    detectDragGestures(onDragStart = { totalDragX = 0f; totalDragY = 0f }, onDragEnd = {
                                            val absX = totalDragX.absoluteValue; val absY = totalDragY.absoluteValue
                                            if (absX > absY && absX > 60f) { if (totalDragX < -60f) onNext() else if (totalDragX > 60f) onPrevious() }
                                            else if (absY > absX && absY > 60f) { if (totalDragY < -60f) showPlaylist = true }
                                        }) { _, dragAmount -> totalDragX += dragAmount.x; totalDragY += dragAmount.y }
                                }) {
                                    videoSurface(false)
                                    // Libass / 文本字幕渲染层（主界面）
                                    SubtitleOverlay(cues = state.cues, videoW = state.videoWidth, videoH = state.videoHeight)
                                }
                    } else {
                        AnimatedAlbumArt(key = state.title, playlistIndex = playlistIndex, playlist = playlist, songs = songs, albumArtUrl = state.albumArtUrl, albumArtBytes = state.albumArtBytes, currentTitle = state.title, currentArtist = state.artist, currentPath = state.mediaPath, playlistVersion = playlistVersion, themeColor = themeColor, uiBackgroundReview = uiBackgroundReview, onPlayFromPlaylist = onPlayFromPlaylist, onPlaylistClick = { showPlaylist = true }, onLyricsFullscreen = { lyricsFullscreen = true }, lastResumeTime = lastResumeTime)
                    }
                }

                if (state.isVideo) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp)) { PlaybackInfo(title = state.title, artist = state.artist, themeColor = themeColor, isVideo = true, onFullscreen = { isVideoFullscreen = true }, uiBackgroundReview = uiBackgroundReview, audioTracks = state.audioTracks, subtitleTracks = state.subtitleTracks, onSelectAudioTrack = onSelectAudioTrack, onSelectSubtitleTrack = onSelectSubtitleTrack, onImportAudioTrack = onImportAudioTrack, onImportSubtitle = onImportSubtitle) }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 迷你歌词（仅音频模式）
                if (!state.isVideo && state.lyrics.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 4.dp)) {
                        val currentText = state.lyrics.getOrNull(state.currentLyricIndex)?.text ?: ""
                        val lines = currentText.split("\n").filter { it.isNotBlank() }
                        if (lines.isNotEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().background(if (uiBackgroundReview) Color.Black.copy(alpha = 0.20f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                                val displayText = lines.take(2).joinToString("\n") { it.trim() }
                                AnimatedContent(targetState = displayText, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "MiniLyricsCrossfade") { text ->
                                    Text(text = text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }

                val controlsBg = if (uiBackgroundReview) Color.Black.copy(alpha = 0.20f) else Color.Transparent
                val isCasting = dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED
                val effectivePlaying = if (isCasting) dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING else state.isPlaying
                val (castPos, castDur) = dlnaSmoothProgress
                val progress = if (isCasting) {
                    val d = if (castDur > 0) castDur else state.durationMs
                    if (d > 0) castPos.toFloat() / d else 0f
                } else {
                    if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs else 0f
                }
                val seekPos = if (isCasting) castPos else state.currentPositionMs
                val seekDur = if (isCasting) castDur else state.durationMs
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 70.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(16.dp)).background(controlsBg).padding(vertical = 12.dp, horizontal = 12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        WaveformSeekBar(progress = progress, onSeek = onSeek, currentPos = seekPos, duration = seekDur, currentIndex = playlistIndex, totalCount = playlistTotal)
                        Spacer(modifier = Modifier.height(12.dp))
                        PowerampControlPanel(isPlaying = effectivePlaying, onPlayPause = onPlayPause, onNext = onNext, onPrevious = onPrevious, themeColor = themeColor, repeatMode = state.repeatMode, isShuffle = state.isShuffle, onRepeatModeChange = onRepeatModeChange, onShuffleToggle = onShuffleToggle, onDlnaCastClick = { if (dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED) { onDlnaCastClick() } else { onDlnaCastClick(); showDlnaPicker = true } }, onPlaylistClick = { showPlaylist = true }, dlnaCastStatus = dlnaCastStatus)
                    }
            }
        }

        AnimatedVisibility(visible = activeTab != PlayerTab.None, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A22)))
                Box(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 135.dp)) {
                    when (activeTab) {
                    PlayerTab.MusicLibrary -> { MusicLibraryScreen(songs = songs, currentTitle = state.title, currentArtist = state.artist, scanAllAudio = scanAllAudio, scanFoldersAudio = scanFoldersAudio, initialFilterType = lastFilterType, initialFilterValue = lastFilterValue, onSongClick = { list, index -> onSongClick(list, index); activeTab = PlayerTab.None }, onSetScanAllAudio = onSetScanAllAudio, onAddScanFolderAudio = onAddScanFolderAudio, onRemoveScanFolderAudio = onRemoveScanFolderAudio, onFilterChange = onFilterChange) }
                    PlayerTab.VideoLibrary -> { VideoLibraryScreen(videos = videos, currentTitle = state.title, scanAllVideo = scanAllVideo, scanFoldersVideo = scanFoldersVideo, onVideoClick = { list, index -> onVideoClick(list, index); activeTab = PlayerTab.None }, onSetScanAllVideo = onSetScanAllVideo, onAddScanFolderVideo = onAddScanFolderVideo, onRemoveScanFolderVideo = onRemoveScanFolderVideo) }
                    PlayerTab.Search -> SimpleSearchView(songs = songs, videos = videos, smbManager = smbManager, smbServers = smbServers, onSongClick = { list, index -> onSongClick(list, index); activeTab = PlayerTab.None }, onVideoClick = { list, index -> onVideoClick(list, index); activeTab = PlayerTab.None }, onPlaySmbFile = onPlaySmbFile, onRefreshSmbServers = onRefreshSmbServers, onSmbScanClick = onSmbScanClick)
                    PlayerTab.AudioEffects -> AudioEffectsView(onApply = onSetAudioFilter, onPresetSelect = onPresetSelect, onBandLevelChange = onBandLevelChange, onReset = onEqReset, currentPreset = state.currentEqPreset, presets = state.eqPresets, bandLevels = state.eqBandLevels, bandCount = state.eqBandCount, levelMin = state.eqLevelMin, levelMax = state.eqLevelMax)
                    PlayerTab.Settings -> SettingsScreen(floatingLyricsEnabled = floatingLyricsEnabled, onFloatingLyricsToggle = onFloatingLyricsToggle, bluetoothLyricsEnabled = bluetoothLyricsEnabled, onBluetoothLyricsToggle = onBluetoothLyricsToggle, uiBackgroundReview = uiBackgroundReview, onUiBackgroundReviewToggle = onUiBackgroundReviewToggle, lyricsFilterEnabled = lyricsFilterEnabled, onLyricsFilterToggle = onLyricsFilterToggle, libassEnabled = libassEnabled, onLibassToggle = onLibassToggle, tvMode = tvMode, onTvModeChange = onTvModeChange)
                    else -> {}
                    }
                }
            }
        }



        if (lyricsFullscreen) { LyricsFullscreenView(lyrics = state.lyrics, currentIndex = state.currentLyricIndex, themeColor = themeColor, artworkUri = state.albumArtUrl, artworkBytes = state.albumArtBytes, onClose = { lyricsFullscreen = false }) }
        AnimatedVisibility(visible = showPlaylist, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(), modifier = Modifier.fillMaxSize()) {
            PlaylistOverlay(state = state, playlist = playlist, currentIndex = playlistIndex - 1, onPlayItem = { index -> onPlayFromPlaylist(index); showPlaylist = false }, onClose = { showPlaylist = false })
        }
        DlnaDevicePickerSheet(showDlnaPicker = showDlnaPicker, onDismiss = { showDlnaPicker = false }, devices = dlnaDevices, onDeviceSelect = { device -> onDlnaDeviceSelect(device); showDlnaPicker = false }, onRefresh = onDlnaCastClick)

        PowerampPersistentBottomBar(state = state, activeTab = activeTab, lyricsFullscreen = lyricsFullscreen, showPlaylist = showPlaylist, dlnaCastStatus = dlnaCastStatus, onTabClick = { tab -> activeTab = if (activeTab == tab) PlayerTab.None else tab; lyricsFullscreen = false }, onPlayPause = onPlayPause, onPrevious = onPrevious, onNext = onNext, themeColor = themeColor, onMiniPlayerClick = { activeTab = PlayerTab.None; lyricsFullscreen = false; showPlaylist = false })
    }
}

@Composable
fun WaveformSeekBar(progress: Float, onSeek: (Float) -> Unit, currentPos: Long, duration: Long, currentIndex: Int = 0, totalCount: Int = 0, modifier: Modifier = Modifier) {
    val barHeights = remember { listOf(0.15f, 0.25f, 0.40f, 0.30f, 0.55f, 0.85f, 0.65f, 0.45f, 0.75f, 0.95f, 0.60f, 0.35f, 0.50f, 0.70f, 0.90f, 0.75f, 0.55f, 0.35f, 0.65f, 0.85f, 0.95f, 0.70f, 0.50f, 0.40f, 0.60f, 0.80f, 0.55f, 0.30f, 0.45f, 0.65f, 0.85f, 0.75f, 0.55f, 0.35f, 0.55f, 0.85f, 0.65f, 0.45f, 0.75f, 0.55f, 0.35f, 0.20f, 0.45f, 0.65f, 0.80f, 0.60f, 0.40f, 0.50f, 0.30f, 0.15f) }
    var dragProgress by remember { mutableStateOf(progress) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(progress, isDragging) {
        if (!isDragging) dragProgress = progress
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barWidth = 4.dp.toPx(); val barGap = 2.dp.toPx(); val totalBars = barHeights.size
                val totalWidth = totalBars * (barWidth + barGap) - barGap; val startX = (size.width - totalWidth) / 2
                for (i in 0 until totalBars) {
                    val barHeight = size.height * barHeights[i]; val x = startX + i * (barWidth + barGap); val y = (size.height - barHeight) / 2
                    val isPlayed = (i.toFloat() / totalBars) <= dragProgress
                    drawRoundRect(color = if (isPlayed) Color.White else Color.White.copy(alpha = 0.2f), topLeft = Offset(x, y), size = Size(barWidth, barHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                }
            }
            Slider(value = dragProgress, onValueChange = { dragProgress = it }, onValueChangeFinished = { isDragging = false; onSeek(dragProgress) }, colors = SliderDefaults.colors(thumbColor = Color.Transparent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent), modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(formatTime(currentPos), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart))
            Text(text = "$currentIndex / $totalCount", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center))
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
fun PowerampControlPanel(isPlaying: Boolean, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, themeColor: Color, repeatMode: RepeatMode = RepeatMode.OFF, isShuffle: Boolean = false, onRepeatModeChange: (RepeatMode) -> Unit = {}, onShuffleToggle: () -> Unit = {}, onDlnaCastClick: () -> Unit = {}, onPlaylistClick: () -> Unit = {}, dlnaCastStatus: com.mediaplayer.plus.DlnaManager.CastStatus = com.mediaplayer.plus.DlnaManager.CastStatus.IDLE) {
    val isCasting = dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlaylistClick, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Description, "播放列表", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(24.dp)) }
            IconButton(onClick = onShuffleToggle, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Shuffle, "随机播放", tint = if (isShuffle) Color(0xFF6200EE) else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(24.dp)) }
            Spacer(modifier = Modifier.width(5.dp))
            Box(modifier = Modifier.shadow(12.dp, CircleShape, spotColor = themeColor.copy(alpha = 0.5f)).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)).size(52.dp).clickableWithoutRipple(onClick = onPrevious), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Filled.SkipPrevious, null, tint = Color.Black, modifier = Modifier.size(35.dp)) }
        }
        Spacer(modifier = Modifier.width(2.dp))
        Box(modifier = Modifier.shadow(12.dp, CircleShape, spotColor = themeColor.copy(alpha = 0.5f)).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)).size(75.dp).clickableWithoutRipple(onClick = onPlayPause), contentAlignment = Alignment.Center) { Icon(imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(50.dp)) }
        Spacer(modifier = Modifier.width(2.dp))
        Row(modifier = Modifier.weight(1f).padding(start = 8.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.shadow(12.dp, CircleShape, spotColor = themeColor.copy(alpha = 0.5f)).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)).size(52.dp).clickableWithoutRipple(onClick = onNext), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Filled.SkipNext, null, tint = Color.Black, modifier = Modifier.size(35.dp)) }
            Spacer(modifier = Modifier.width(5.dp))
            IconButton(onClick = { val next = when (repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }; onRepeatModeChange(next) }, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = when (repeatMode) { RepeatMode.ONE -> Icons.Filled.RepeatOne; else -> Icons.Filled.Repeat }, "循环模式", tint = if (repeatMode == RepeatMode.OFF) Color.White.copy(alpha = 0.4f) else Color(0xFF6200EE), modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onDlnaCastClick, modifier = Modifier.size(36.dp)) { Icon(imageVector = Icons.Filled.CastConnected, "DLNA投屏", tint = if (isCasting) Color(0xFF6200EE) else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
fun MiniPlayerContent(state: PlayerState, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, themeColor: Color, onClick: () -> Unit, isPlaying: Boolean = state.isPlaying) {
    Crossfade(targetState = state.mediaPath, animationSpec = tween(600), label = "MiniPlayerCrossfade") { _ ->
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AlbumArtThumb(state.albumArtUrl, state.albumArtBytes, size = 50.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(targetState = state.title to state.artist, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }, label = "MiniPlayerTextCrossfade") { (title, artist) ->
                        Column {
                            MarqueeText(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                            MarqueeText(text = artist, color = themeColor.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AlbumArtThumb(albumArtUrl: String?, bytes: ByteArray? = null, size: Dp = 36.dp) {
    Box(modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
        Crossfade(targetState = albumArtUrl ?: bytes, animationSpec = tween(400), label = "ThumbCrossfade") { source ->
            if (source is ByteArray) { AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(source).crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            else if (source is String) { AsyncImage(model = source, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            else { Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.2f)) } }
        }
    }
}

@Composable
fun PowerampPersistentBottomBar(state: PlayerState, activeTab: PlayerTab, lyricsFullscreen: Boolean = false, showPlaylist: Boolean = false, dlnaCastStatus: com.mediaplayer.plus.DlnaManager.CastStatus = com.mediaplayer.plus.DlnaManager.CastStatus.IDLE, onTabClick: (PlayerTab) -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, themeColor: Color, onMiniPlayerClick: () -> Unit) {
    val isCasting = dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING || dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PAUSED
    val effectivePlaying = if (isCasting) dlnaCastStatus == com.mediaplayer.plus.DlnaManager.CastStatus.PLAYING else state.isPlaying
    val showMini = (activeTab != PlayerTab.None || lyricsFullscreen || showPlaylist) && state.title.isNotEmpty()
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF16161E).copy(alpha = if (activeTab != PlayerTab.None || lyricsFullscreen || showPlaylist) 1f else 0.2f), border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)), shadowElevation = 16.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(visible = showMini, enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
                    MiniPlayerContent(state = state, onPlayPause = onPlayPause, onPrevious = onPrevious, onNext = onNext, themeColor = themeColor, onClick = onMiniPlayerClick, isPlaying = effectivePlaying)
                }
                Row(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    BottomBarItem(Icons.Filled.LibraryMusic, "音乐库", activeTab == PlayerTab.MusicLibrary) { onTabClick(PlayerTab.MusicLibrary) }
                    BottomBarItem(Icons.Filled.VideoLibrary, "视频库", activeTab == PlayerTab.VideoLibrary) { onTabClick(PlayerTab.VideoLibrary) }
                    BottomBarItem(Icons.Filled.Search, "搜索", activeTab == PlayerTab.Search) { onTabClick(PlayerTab.Search) }
                    BottomBarItem(Icons.Filled.Equalizer, "音效", activeTab == PlayerTab.AudioEffects) { onTabClick(PlayerTab.AudioEffects) }
                    BottomBarItem(Icons.Filled.Settings, "设置", activeTab == PlayerTab.Settings) { onTabClick(PlayerTab.Settings) }
                }
            }
        }
    }
}

@Composable
fun BottomBarItem(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f)
    val scale by animateFloatAsState(if (isSelected) 1.15f else 1.0f, label = "tab_scale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickableWithoutRipple(onClick = onClick).padding(8.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun DlnaDevicePickerSheet(showDlnaPicker: Boolean, onDismiss: () -> Unit, devices: List<com.mediaplayer.plus.DlnaManager.DlnaDevice>, onDeviceSelect: (com.mediaplayer.plus.DlnaManager.DlnaDevice) -> Unit, onRefresh: () -> Unit) {
    var searchInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(devices) {
        searchInProgress = false
    }

    // 面板打开时每 10 秒自动扫描一次，关闭后停止
    LaunchedEffect(showDlnaPicker) {
        if (showDlnaPicker) {
            searchInProgress = true
            onRefresh()
            while (showDlnaPicker) {
                delay(10000)
                if (showDlnaPicker) {
                    searchInProgress = true
                    onRefresh()
                }
            }
        }
    }

    AnimatedVisibility(visible = showDlnaPicker, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it })) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Card(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF16161E)), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(text = "投屏设备 (DLNA)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            if (searchInProgress) {
                                CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = { searchInProgress = true; onRefresh() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Refresh, "刷新", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, "关闭", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "点击设备名称即可投屏当前播放内容", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Column {
                            if (devices.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Filled.CastConnected, "未发现设备", tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(text = if (searchInProgress) "正在搜索 DLNA 设备..." else "未发现 DLNA 设备", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                                        if (!searchInProgress) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(text = "确保电视/功放与手机在同一 WiFi 网络下", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                    items(devices.size) { index ->
                                        val device = devices[index]
                                        Column(modifier = Modifier.clickable(onClick = { onDeviceSelect(device); onDismiss() }).padding(horizontal = 8.dp, vertical = 10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (device.isTV) Icons.Filled.Tv else Icons.Filled.SmartDisplay, null, tint = Color(0xFF6200EE), modifier = Modifier.size(28.dp))
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = device.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                    Text(text = "${if (device.isTV) "电视" else "播放设备"} · ${device.address}", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                                                }
                                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistOverlay(state: PlayerState, playlist: List<MediaItem>, currentIndex: Int, onPlayItem: (Int) -> Unit, onClose: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) { if (currentIndex >= 0 && currentIndex < playlist.size) listState.scrollToItem(currentIndex) }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A22)).statusBarsPadding().padding(bottom = 135.dp)) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "播放列表", color = Color.White.copy(alpha = 0.5f), fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(text = "${playlist.size}首", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "关闭", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp)) }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(playlist) { index, item ->
                        when (item) {
                            is Song -> MediaListRow(title = item.title, subtitle = item.artist, albumArtUrl = item.albumArtUrl, durationMs = item.duration, isCurrent = index == currentIndex, onClick = { onPlayItem(index) })
                            is Video -> MediaListRow(title = item.title, subtitle = formatTime(item.duration), albumArtUrl = item.uri.toString(), durationMs = item.duration, icon = Icons.Filled.Videocam, isCurrent = index == currentIndex, onClick = { onPlayItem(index) })
                            is SmbMediaItem -> MediaListRow(title = item.fileName, subtitle = if (item.fileSize > 0) { val mb = item.fileSize / (1024.0 * 1024.0); "SMB · ${"%.1f".format(mb)} MB" } else "SMB", albumArtUrl = null, durationMs = 0, icon = if (item.isVideoFile) Icons.Filled.Videocam else Icons.Filled.MusicNote, isCurrent = index == currentIndex, onClick = { onPlayItem(index) })
                        }
                    }
                }
            }
            FastScrollbar(listState = listState, modifier = Modifier.padding(top = 56.dp, bottom = 135.dp))
        }
    }
}

@Composable
fun SimpleSearchView(songs: List<Song> = emptyList(), videos: List<Video> = emptyList(), smbManager: SmbManager? = null, smbServers: List<SmbServer> = emptyList(), onSongClick: (List<Song>, Int) -> Unit = { _, _ -> }, onVideoClick: (List<Video>, Int) -> Unit = { _, _ -> }, onPlaySmbFile: (isVideo: Boolean, playlist: List<SmbMediaItem>, idx: Int) -> Unit = { _, _, _ -> }, onRefreshSmbServers: () -> Unit = {}, onSmbScanClick: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }; val listState = rememberLazyListState()
    val filteredSongs = remember(query, songs) { if (query.isBlank()) emptyList() else songs.filter { it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) || it.album.contains(query, ignoreCase = true) } }
    val filteredVideos = remember(query, videos) { if (query.isBlank()) emptyList() else videos.filter { it.title.contains(query, ignoreCase = true) } }
    var showSmbAddDialog by remember { mutableStateOf(false) }; var smbLoading by remember { mutableStateOf(false) }; var smbError by remember { mutableStateOf<String?>(null) }; var smbCurrentServer by remember { mutableStateOf<SmbServer?>(null) }; var smbCurrentPath by remember { mutableStateOf("") }; var smbEntries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }; var smbBrowseStack by remember { mutableStateOf<List<Pair<SmbServer, String>>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var smbEditingServer by remember { mutableStateOf<SmbServer?>(null) }; var smbBookmarks by remember { mutableStateOf(smbManager?.getBookmarks() ?: emptyList()) }; var showBookmarkNameDialog by remember { mutableStateOf(false) }; var bookmarkPendingServer by remember { mutableStateOf<SmbServer?>(null) }; var bookmarkPendingPath by remember { mutableStateOf("") }; var bookmarkPendingLabel by remember { mutableStateOf("") }
    fun browseServer(server: SmbServer, path: String = "") { smbLoading = true; smbError = null; smbCurrentServer = server; smbCurrentPath = path; smbManager?.let { mgr -> coroutineScope.launch { mgr.listEntries(server, path).fold(onSuccess = { smbEntries = it; smbLoading = false }, onFailure = { smbError = it.message; smbLoading = false }) } } }
    fun enterFolder(entry: SmbEntry) { val server = smbCurrentServer ?: return; smbBrowseStack = smbBrowseStack + (server to smbCurrentPath); browseServer(server, entry.path) }
    fun goBack() { if (smbBrowseStack.isNotEmpty()) { val (server, path) = smbBrowseStack.last(); smbBrowseStack = smbBrowseStack.dropLast(1); browseServer(server, path) } else { smbCurrentServer = null; smbEntries = emptyList(); smbCurrentPath = ""; smbBrowseStack = emptyList() } }
    BackHandler(enabled = smbCurrentServer != null) { goBack() }
    fun playSmbEntry(entry: SmbEntry) { val server = smbCurrentServer ?: return; val mediaFiles = smbEntries.filter { it.isMediaFile }; val playlist = mediaFiles.map { f -> SmbMediaItem(server.id, f.path, f.name, f.size, f.isVideoFile, server.host, server.share, server.isGuest, server.username, server.password) }; val index = mediaFiles.indexOf(entry).coerceAtLeast(0); onPlaySmbFile(entry.isVideoFile, playlist, index) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PageHeader("搜索"); Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("搜索音乐或视频", color = Color.Gray) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.White.copy(alpha = 0.3f), unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f), cursorColor = Color.White), leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "清除", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp)) } })
        Spacer(modifier = Modifier.height(12.dp))
        if (query.isBlank()) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                item { Spacer(modifier = Modifier.height(4.dp)); Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("🌐 局域网 (SMB)", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); TextButton(onClick = onSmbScanClick) { Text("🔍 自动扫描", color = Color(0xFF81C784), fontSize = 13.sp) }; TextButton(onClick = { smbEditingServer = null; showSmbAddDialog = true }) { Text("＋ 添加", color = Color(0xFF64B5F6), fontSize = 13.sp) } } }
                val bookmarks = smbBookmarks
                if (bookmarks.isNotEmpty() && smbCurrentServer == null) {
                    item { Text("⭐ 收藏夹", color = Color(0xFFFFD54F).copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp)) }
                    items(bookmarks.size) { index -> val bm = bookmarks[index]; val server = smbServers.find { it.id == bm.serverId }; MediaListRow(title = bm.label, subtitle = if (server != null) "smb://${server.host}${bm.path.removePrefix(server.smbRoot)}" else bm.path, albumArtUrl = null, durationMs = 0L, icon = Icons.Filled.Star, isCurrent = false, onClick = { server?.let { browseServer(it, bm.path) } }, trailing = { IconButton(onClick = { smbManager?.removeBookmark(bm.id) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "取消收藏", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(14.dp)) } }) }
                    item { HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp)) }
                }
                if (smbCurrentServer != null) {
                    item { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { if (smbBrowseStack.isNotEmpty()) IconButton(onClick = { goBack() }, modifier = Modifier.size(28.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp)) }; Text("${smbCurrentServer!!.displayName}${if (smbCurrentPath.isNotEmpty()) " / ${smbCurrentPath.trimEnd('/').substringAfterLast("/")}" else ""}", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); val isFav = smbManager?.isBookmarked(smbCurrentServer!!.id, smbCurrentPath.ifEmpty { smbCurrentServer!!.smbRoot }) == true; IconButton(onClick = { val srv = smbCurrentServer ?: return@IconButton; val p = smbCurrentPath.ifEmpty { srv.smbRoot }; val label = if (smbCurrentPath.isEmpty()) srv.displayName else smbCurrentPath.trimEnd('/').substringAfterLast("/").ifEmpty { srv.displayName }; if (isFav) { smbManager?.removeBookmarkByPath(srv.id, p); smbBookmarks = smbManager?.getBookmarks() ?: emptyList() } else { bookmarkPendingServer = srv; bookmarkPendingPath = p; bookmarkPendingLabel = label; showBookmarkNameDialog = true } }, modifier = Modifier.size(28.dp)) { Icon(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, "收藏", tint = if (isFav) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) }; IconButton(onClick = { smbCurrentServer = null; smbEntries = emptyList(); smbCurrentPath = ""; smbBrowseStack = emptyList() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "关闭", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp)) } } }
                    if (smbLoading) { item { Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(24.dp)) } } }
                    else if (smbError != null) { item { Text(smbError!!, color = Color(0xFFEF5350), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) } }
                    else { items(smbEntries.size) { index -> val entry = smbEntries[index]; if (entry.isDirectory) MediaListRow(title = entry.name, subtitle = if (entry.lastModified > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(entry.lastModified)) else "", albumArtUrl = null, durationMs = 0L, icon = Icons.Filled.Folder, isCurrent = false, onClick = { enterFolder(entry) }) else if (entry.isMediaFile) MediaListRow(title = entry.name, subtitle = if (entry.size > 0) formatSmbSize(entry.size) else "", albumArtUrl = null, durationMs = 0L, icon = if (entry.isVideoFile) Icons.Filled.Videocam else Icons.Filled.MusicNote, isCurrent = false, onClick = { playSmbEntry(entry) }) } }
                } else {
                    if (smbServers.isEmpty()) { item { Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Text("点击上方「＋ 添加服务器」添加局域网共享", color = Color.Gray, fontSize = 13.sp) } } }
                    else { items(smbServers.size) { index -> val server = smbServers[index]; MediaListRow(title = server.displayName, subtitle = "smb://${server.host}" + if (server.share.isNotEmpty()) "/${server.share}" else "", albumArtUrl = null, durationMs = 0L, icon = Icons.Filled.Storage, isCurrent = false, onClick = { browseServer(server) }, trailing = { Row { IconButton(onClick = { smbEditingServer = server; showSmbAddDialog = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Edit, "编辑", tint = Color(0xFF64B5F6).copy(alpha = 0.6f), modifier = Modifier.size(16.dp)) }; IconButton(onClick = { smbManager?.removeServer(server.id); onRefreshSmbServers() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFEF5350).copy(alpha = 0.6f), modifier = Modifier.size(16.dp)) } } }) } }
                }
                item { HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp)) }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (filteredSongs.isNotEmpty()) { item { Text("🎵 音乐", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 8.dp)) }; items(filteredSongs.size) { index -> val song = filteredSongs[index]; MediaListRow(title = song.title, subtitle = "${song.artist} · ${song.album}", albumArtUrl = song.albumArtUrl, durationMs = song.duration, isCurrent = false, onClick = { onSongClick(filteredSongs, index) }) } }
                if (filteredVideos.isNotEmpty()) { item { Text("🎬 视频", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 8.dp)) }; items(filteredVideos.size) { index -> val video = filteredVideos[index]; MediaListRow(title = video.title, subtitle = formatTime(video.duration), albumArtUrl = video.uri.toString(), durationMs = video.duration, icon = Icons.Filled.Videocam, isCurrent = false, onClick = { onVideoClick(filteredVideos, index) }) } }
            }
        }
    }
    if (showSmbAddDialog) { SmbServerDialog(existingServer = smbEditingServer, onDismiss = { showSmbAddDialog = false; smbEditingServer = null }, onSave = { server -> if (smbEditingServer != null) smbManager?.removeServer(smbEditingServer!!.id); smbManager?.addServer(server); onRefreshSmbServers(); showSmbAddDialog = false; smbEditingServer = null }) }
    if (showBookmarkNameDialog) { var nameInput by remember { mutableStateOf(bookmarkPendingLabel) }; AlertDialog(onDismissRequest = { showBookmarkNameDialog = false }, title = { Text("收藏文件夹", color = Color.White) }, text = { OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("名称", color = Color.White.copy(alpha = 0.5f)) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { val srv = bookmarkPendingServer ?: return@TextButton; smbManager?.addBookmark(srv.id, bookmarkPendingPath, nameInput.ifBlank { bookmarkPendingLabel }); smbBookmarks = smbManager?.getBookmarks() ?: emptyList(); onRefreshSmbServers(); showBookmarkNameDialog = false }) { Text("收藏", color = Color(0xFFFFD54F)) } }, dismissButton = { TextButton(onClick = { showBookmarkNameDialog = false }) { Text("取消", color = Color.White.copy(alpha = 0.5f)) } }, containerColor = Color(0xFF1E1E2E), shape = RoundedCornerShape(16.dp)) }
}

@Composable
fun BlurredBackground(artworkUri: String?, artworkBytes: ByteArray? = null, themeColor: Color) {
    val context = LocalContext.current
    val animatedAlpha by animateFloatAsState(targetValue = if (artworkUri != null || artworkBytes != null) 0.85f else 0.55f, animationSpec = tween(1200), label = "bg_alpha")
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A22))) {
        Crossfade(targetState = artworkUri ?: artworkBytes, animationSpec = tween(durationMillis = 800), label = "BackgroundCrossfade") { source ->
            if (source is String) { AsyncImage(model = ImageRequest.Builder(context).data(source).crossfade(800).build(), contentDescription = null, modifier = Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = animatedAlpha; scaleX = 1.15f; scaleY = 1.15f }, contentScale = ContentScale.Crop) }
            else if (source is ByteArray) { val bitmap = remember(source) { try { android.graphics.BitmapFactory.decodeByteArray(source, 0, source.size) } catch (_: Exception) { null } }; if (bitmap != null) { Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = animatedAlpha; scaleX = 1.15f; scaleY = 1.15f }, contentScale = ContentScale.Crop) } }
            else { Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(colors = listOf(themeColor.copy(alpha = 0.45f), Color(0xFF1A1A22)), radius = 1200f))) }
        }
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.6f)))))
    }
}

@Composable
fun PlaybackInfo(
    title: String,
    artist: String,
    themeColor: Color,
    isVideo: Boolean,
    onFullscreen: () -> Unit,
    uiBackgroundReview: Boolean = false,
    audioTracks: List<com.mediaplayer.plus.player.TrackInfo> = emptyList(),
    subtitleTracks: List<com.mediaplayer.plus.player.TrackInfo> = emptyList(),
    onSelectAudioTrack: (Int) -> Unit = {},
    onSelectSubtitleTrack: (Int) -> Unit = {},
    onImportAudioTrack: () -> Unit = {},
    onImportSubtitle: () -> Unit = {}
) {
    val titleBg = if (uiBackgroundReview) Color.Black.copy(alpha = 0.2f) else Color.Transparent
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().background(titleBg).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (isVideo) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (artist.isNotBlank()) {
                        Text(
                            text = artist,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { showAudioMenu = !showAudioMenu }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.MusicNote, "音轨", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = showAudioMenu, onDismissRequest = { showAudioMenu = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.UploadFile, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("导入音轨", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = { onImportAudioTrack(); showAudioMenu = false }
                                )
                                if (audioTracks.isEmpty()) {
                                    DropdownMenuItem(text = { Text("无音轨", color = Color.Gray) }, onClick = {})
                                } else {
                                    audioTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(text = track.title, color = if (track.isSelected) Color(0xFF00E5FF) else Color.White, fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                    if (track.isSelected) Icon(Icons.Filled.Check, null, tint = Color(0xFF00E5FF))
                                                }
                                            },
                                            onClick = { onSelectAudioTrack(track.id); showAudioMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showSubMenu = !showSubMenu }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Subtitles, "字幕", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = showSubMenu, onDismissRequest = { showSubMenu = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.UploadFile, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("导入字幕", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = { onImportSubtitle(); showSubMenu = false }
                                )
                                if (subtitleTracks.isEmpty()) {
                                    DropdownMenuItem(text = { Text("无字幕", color = Color.Gray) }, onClick = {})
                                } else {
                                    subtitleTracks.forEach { sub ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(text = sub.title, color = if (sub.isSelected) Color(0xFF00E5FF) else Color.White, fontWeight = if (sub.isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                    if (sub.isSelected) Icon(Icons.Filled.Check, null, tint = Color(0xFF00E5FF))
                                                }
                                            },
                                            onClick = { onSelectSubtitleTrack(sub.id); showSubMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Fullscreen, "全屏", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                        }
                    }
                }
                MarqueeText(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                MarqueeText(text = artist, color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun VideoSurfaceView(isFullScreen: Boolean = false, modifier: Modifier = Modifier, onSurfaceCreated: (Surface) -> Unit, onSurfaceDestroyed: () -> Unit) {
    var lastST by remember { mutableStateOf<SurfaceTexture?>(null) }
    var lastSurface by remember { mutableStateOf<Surface?>(null) }
    AndroidView(factory = { context -> TextureView(context).apply { surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { val surface = Surface(st); lastST = st; lastSurface = surface; onSurfaceCreated(surface) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { lastSurface?.release(); lastSurface = null; lastST = null; onSurfaceDestroyed(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    } } }, update = { textureView -> val st = textureView.surfaceTexture; if (st != null && st !== lastST) { lastSurface?.release(); val surface = Surface(st); lastST = st; lastSurface = surface; onSurfaceCreated(surface) } }, modifier = Modifier.fillMaxSize())
}

@Composable
fun LyricsFullscreenView(lyrics: List<LyricEntry>, currentIndex: Int, themeColor: Color, artworkUri: String?, artworkBytes: ByteArray? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val animatedAlpha by animateFloatAsState(targetValue = if (artworkUri != null || artworkBytes != null) 0.85f else 0.55f, animationSpec = tween(1200), label = "lyrics_bg_alpha")
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) { if (currentIndex >= 0 && currentIndex < lyrics.size) listState.animateScrollToItem(currentIndex) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A22))) {
            Crossfade(targetState = artworkUri ?: artworkBytes, animationSpec = tween(800), label = "LyricsBgCrossfade") { source ->
                if (source is String) { AsyncImage(model = ImageRequest.Builder(context).data(source).crossfade(800).build(), null, Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = animatedAlpha; scaleX = 1.15f; scaleY = 1.15f }, contentScale = ContentScale.Crop) }
                else if (source is ByteArray) { val bitmap = remember(source) { try { android.graphics.BitmapFactory.decodeByteArray(source, 0, source.size) } catch (_: Exception) { null } }; if (bitmap != null) { Image(bitmap = bitmap.asImageBitmap(), null, Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = animatedAlpha; scaleX = 1.15f; scaleY = 1.15f }, contentScale = ContentScale.Crop) } }
                else { Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(themeColor.copy(alpha = 0.45f), Color(0xFF1A1A22)), radius = 1200f))) }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.7f)))))
        }
        Crossfade(targetState = lyrics, animationSpec = tween(600), label = "LyricsContentCrossfade") { currentLyrics ->
            if (currentLyrics.isNotEmpty()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val topOffset = maxHeight / 3
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(bottom = 120.dp, start = 24.dp, end = 24.dp).statusBarsPadding(), contentPadding = PaddingValues(top = topOffset, bottom = maxHeight - topOffset), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(currentLyrics) { index, entry ->
                            val isCurrent = index == currentIndex
                            Text(text = entry.text, color = if (isCurrent) Color.White else Color.White.copy(alpha = if (isAdjacent(index, currentIndex)) 0.6f else 0.35f), fontSize = if (isCurrent) 24.sp else 16.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = if (isCurrent) 1.05f else 1.0f; scaleY = if (isCurrent) 1.05f else 1.0f }.padding(vertical = 4.dp))
                        }
                    }
                }
            } else { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text = "暂无歌词", color = Color.White.copy(alpha = 0.45f), fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).statusBarsPadding()) { Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.6f)) }
    }
}

@Composable
fun AnimatedAlbumArt(key: String, playlistIndex: Int, playlist: List<MediaItem>, songs: List<Song>, albumArtUrl: String?, albumArtBytes: ByteArray?, currentTitle: String, currentArtist: String, currentPath: String, playlistVersion: Int, themeColor: Color, uiBackgroundReview: Boolean, onPlayFromPlaylist: (Int) -> Unit, onPlaylistClick: () -> Unit, onLyricsFullscreen: () -> Unit, lastResumeTime: Long = 0L) {
    val pageCountVal = playlist.size.coerceAtLeast(1)
    val pagerState = key(playlistVersion) { val initialPage = playlist.indexOfFirst { it.getIdentificationPath() == currentPath }.coerceIn(0, pageCountVal - 1); rememberPagerState(initialPage = initialPage) { pageCountVal } }
    var lastTargetId by remember(currentPath) { mutableStateOf(currentPath) }
    // 标记：由 playlistIndex 变化触发的程序化翻页，动画结束前忽略 pager 的 onPlayFromPlaylist 回调，防止重复切歌
    var skipNextPagerPlay by remember { mutableStateOf(false) }
    LaunchedEffect(playlistIndex, pagerState.isScrollInProgress, currentPath, lastResumeTime) {
        if (!pagerState.isScrollInProgress && lastTargetId == currentPath) {
            val target = (playlistIndex - 1).coerceIn(0, pageCountVal - 1)
            if (pagerState.currentPage != target) {
                skipNextPagerPlay = true
                // 恢复前台 500ms 内且当前页与目标页不匹配（前台切歌）→ 无动画对齐
                val withinResumeWindow = lastResumeTime > 0L && (System.currentTimeMillis() - lastResumeTime) < 2000L
                if (withinResumeWindow) pagerState.scrollToPage(target)
                else if ((pagerState.currentPage - target).absoluteValue > 1) pagerState.scrollToPage(target)
                else pagerState.animateScrollToPage(target, animationSpec = tween(700))
                delay(850)
                skipNextPagerPlay = false
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (skipNextPagerPlay) return@collect
            val item = playlist.getOrNull(page)
            val itemId = item?.getIdentificationPath() ?: ""
            if (itemId.isNotEmpty() && itemId != lastTargetId && pagerState.isScrollInProgress && page != (playlistIndex - 1)) {
                lastTargetId = itemId
                onPlayFromPlaylist(page)
            }
        }
    }
    HorizontalPager(state = pagerState, key = { page -> playlist.getOrNull(page)?.getIdentificationPath() ?: "empty_$page" }, modifier = Modifier.fillMaxWidth().height(520.dp).pointerInput(Unit) { awaitEachGesture { var totalDragX = 0f; var totalDragY = 0f; var isVerticalDrag = false; var isHorizontalDrag = false; var lastY = 0f; val down = awaitFirstDown(requireUnconsumed = false); lastY = down.position.y; do { val event = awaitPointerEvent(); val dragChange = event.changes.firstOrNull() ?: break; if (dragChange.pressed) { val dragAmount = dragChange.position - dragChange.previousPosition; totalDragX += dragAmount.x.absoluteValue; totalDragY += dragAmount.y.absoluteValue; lastY = dragChange.position.y; if (!isVerticalDrag && !isHorizontalDrag) { if (totalDragX + totalDragY > 15f) if (totalDragY > totalDragX * 1.8f) isVerticalDrag = true else if (totalDragX > totalDragY * 1.8f) isHorizontalDrag = true } ; if (isVerticalDrag) dragChange.consume() } } while (event.changes.any { it.pressed }); if (isVerticalDrag) { val startY = down.position.y; val diffY = lastY - startY; if (diffY < -80f) onLyricsFullscreen() else if (diffY > 80f) onPlaylistClick() } } }, contentPadding = PaddingValues(horizontal = 15.dp), pageSpacing = 8.dp, beyondViewportPageCount = 1, verticalAlignment = Alignment.Top) { page ->
        val signedOffset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
        val pageOffset = signedOffset.absoluteValue
        val item = playlist.getOrNull(page); val isCurrentPlaying = item != null && item.getIdentificationPath() == currentPath
        val (pageTitle, pageArtist) = when { isCurrentPlaying -> currentTitle to currentArtist; item is Song -> item.title to item.artist; item is Video -> item.title to ""; item is SmbMediaItem -> (item.realTitle ?: item.fileName) to (item.artistName ?: ""); else -> currentTitle to currentArtist }
        Column(modifier = Modifier.fillMaxWidth().graphicsLayer { rotationY = -22f * signedOffset.coerceIn(-1f, 1f); val scale = 0.82f + (1f - 0.82f) * (1f - pageOffset.coerceAtMost(1f)); scaleX = scale; scaleY = scale; alpha = 0.5f + (1f - 0.5f) * (1f - pageOffset.coerceAtMost(1f)); cameraDistance = 12 * density }) {
            Box(Modifier.aspectRatio(1f).shadow(20.dp, RoundedCornerShape(24.dp), ambientColor = themeColor.copy(alpha = 0.35f), spotColor = themeColor).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E24)).pointerInput(Unit) { detectTapGestures(onTap = { onLyricsFullscreen() }, onLongPress = { onPlaylistClick() }) }, contentAlignment = Alignment.Center) {
                when (item) {
                    is Song -> AlbumArtContent(url = item.albumArtUrl, bytes = if (isCurrentPlaying) (albumArtBytes ?: item.albumArtBytes) else item.albumArtBytes)
                    is Video -> Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { Icon(Icons.Filled.Videocam, null, tint = Color.White.copy(alpha = 0.15f), modifier = Modifier.size(110.dp)) }
                    is SmbMediaItem -> { val resB = if (isCurrentPlaying) (albumArtBytes ?: item.albumArtBytes) else item.albumArtBytes; if (resB != null) AlbumArtContent(null, resB) else Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { Icon(if (item.isVideoFile) Icons.Filled.Videocam else Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.15f), modifier = Modifier.size(110.dp)) } }
                    else -> AlbumArtContent(albumArtUrl, albumArtBytes)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().background(if (uiBackgroundReview) Color.Black.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                AnimatedContent(targetState = pageTitle to pageArtist, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }, label = "AlbumArtTextCrossfade") { (title, artist) ->
                    Column {
                        if (title.isNotBlank()) MarqueeText(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth())
                        if (artist.isNotBlank()) MarqueeText(text = artist, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}



@Composable
private fun AlbumArtContent(url: String?, bytes: ByteArray?) {
    val ctx = LocalContext.current
    if (url != null) AsyncImage(model = ImageRequest.Builder(ctx).data(url).crossfade(false).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else if (bytes != null) { val b = remember(bytes) { try { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null } }; if (b != null) Image(bitmap = b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    else Box(Modifier.fillMaxSize().background(Color(0xFF1E1E24)), Alignment.Center) { Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.15f), modifier = Modifier.size(110.dp)) }
}

@Composable
fun SubtitleOverlay(cues: List<androidx.media3.common.text.Cue>, videoW: Int = 0, videoH: Int = 0) {
    if (cues.isEmpty()) return
    val hasBitmap = cues.any { it.bitmap != null }

    // onCues 可能比 onVideoSizeChanged 先触发，缓存视频尺寸等下一帧
    var cachedW by remember { mutableIntStateOf(videoW) }
    var cachedH by remember { mutableIntStateOf(videoH) }
    if (videoW > 0 && videoH > 0) { cachedW = videoW; cachedH = videoH }
    if (cachedW <= 0 || cachedH <= 0) return

    Log.d("SubTitle", "cues=${cues.size} hasBitmap=$hasBitmap video=${cachedW}x${cachedH}")

    if (hasBitmap) {
        val sizes = cues.map { c -> c.bitmap?.width to c.bitmap?.height }
        Log.d("LibassCues", "cues=${cues.size} sizes=$sizes")

        val bitmaps = cues.mapNotNull { c -> c.bitmap?.takeIf { it.width > 0 && it.height > 0 } }
        if (bitmaps.isEmpty()) return

        // 用视频分辨率作为画布（libass 已按视频分辨率把文字画到正确位置）
        val combined = android.graphics.Bitmap.createBitmap(cachedW, cachedH, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(combined)
        c.drawColor(android.graphics.Color.TRANSPARENT)
        for (cue in cues) {
            val bm = cue.bitmap ?: continue
            if (bm.width <= 0 || bm.height <= 0) continue
            c.drawBitmap(bm, 0f, 0f, null)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = combined.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val lines = cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                Text(
                    text = lines.joinToString("\n"),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp,
                    style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 2f))
                )
            }
        }
    }
}

@Composable
fun AlbumArtView(url: String?, bytes: ByteArray? = null) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1E1E24)) {
        AnimatedContent(targetState = (url ?: "") + (bytes?.size ?: 0).toString(), transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }, modifier = Modifier.fillMaxSize(), label = "AlbumArtContent") { _ ->
            if (bytes != null) { AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(bytes).crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            else if (url != null) { AsyncImage(model = url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            else { Box(contentAlignment = Alignment.Center, modifier = Modifier.background(Brush.radialGradient(listOf(Color(0xFF2E2E38), Color(0xFF141419))))) { Icon(Icons.Filled.MusicNote, null, Modifier.size(110.dp), tint = Color.White.copy(alpha = 0.15f)) } }
        }
    }
}

@Composable
fun Modifier.clickableWithoutRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.then(Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick))
}

private fun isAdjacent(a: Int, b: Int): Boolean { return a == b - 1 || a == b - 2 || a == b + 1 || a == b + 2 }

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L); val minutes = totalSeconds / 60; val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun MarqueeText(text: String, modifier: Modifier = Modifier, color: Color = Color.White, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, fontWeight: FontWeight = FontWeight.Normal, textAlign: TextAlign = TextAlign.Start, scrollSpeed: Float = 25f, pauseDurationMs: Int = 2000) {
    var containerWidth by remember { mutableFloatStateOf(0f) }
    Box(modifier = modifier.clipToBounds().onSizeChanged { containerWidth = it.width.toFloat() }, contentAlignment = Alignment.CenterStart) {
        key(text) { MarqueeInner(text = text, containerWidth = containerWidth, color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign, scrollSpeed = scrollSpeed, pauseDurationMs = pauseDurationMs) }
    }
}

@Composable
fun MarqueeInner(text: String, containerWidth: Float, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: FontWeight, textAlign: TextAlign, scrollSpeed: Float, pauseDurationMs: Int) {
    var textWidth by remember { mutableStateOf(0f) }
    if (textWidth <= 0f || containerWidth <= 0f) { Text(text = text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false, textAlign = textAlign, onTextLayout = { textWidth = it.size.width.toFloat() }, modifier = Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start)); return }
    val overflowPx = (textWidth - containerWidth).coerceAtLeast(0f)
    if (overflowPx <= 0f) { Text(text = text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, textAlign = textAlign, modifier = Modifier.fillMaxWidth()); return }
    val scrollDurationMs = (overflowPx / scrollSpeed * 1000).toInt().coerceAtLeast(800); val totalDurationMs = pauseDurationMs * 2 + scrollDurationMs * 2
    val infiniteTransition = rememberInfiniteTransition(label = "marquee")
    val offset by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 0f, animationSpec = infiniteRepeatable(animation = keyframes { durationMillis = totalDurationMs; 0f at 0; 0f at pauseDurationMs; -overflowPx at pauseDurationMs + scrollDurationMs; -overflowPx at pauseDurationMs * 2 + scrollDurationMs; 0f at totalDurationMs }, repeatMode = androidx.compose.animation.core.RepeatMode.Restart), label = "marqueeOffset")
    Text(text = text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false, textAlign = textAlign, modifier = Modifier.graphicsLayer { translationX = offset }.wrapContentWidth(unbounded = true, align = Alignment.Start))
}


private fun formatSmbSize(bytes: Long): String {
    return when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0); bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024)); else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024)) }
}
