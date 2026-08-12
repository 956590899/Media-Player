package com.mediaplayer.plus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.mediaplayer.plus.player.PlayerState
import com.mediaplayer.plus.player.RepeatMode
import com.mediaplayer.plus.player.VideoScaleMode
import kotlinx.coroutines.delay

@Composable
fun TvPlayerScreen(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatModeChange: (RepeatMode) -> Unit,
    onSettingsClick: () -> Unit,
    onBack: () -> Unit,
    onSurfaceCreated: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    playlistIndex: Int = 0,
    playlistTotal: Int = 0,
    onSeek: (Float) -> Unit = {},
    onSeekMs: (Long) -> Unit = {},
    isKeepScreenOn: Boolean = false,
    onToggleKeepScreenOn: () -> Unit = {},
    showPlaylist: Boolean = false,
    playlist: List<com.mediaplayer.plus.data.MediaItem> = emptyList(),
    onPlayFromPlaylist: (Int) -> Unit = {},
    onPlaylistClick: () -> Unit = {},
    onClosePlaylist: () -> Unit = {},
    videoSurface: (@Composable (Boolean) -> Unit)? = null
) {
    // Intercept back button
    BackHandler {
        if (showPlaylist) {
            onClosePlaylist()
        } else {
            onBack()
        }
    }

    // For audio, always show controls. For video, auto-hide.
    var showControls by remember { mutableStateOf(!state.isVideo) }
    
    // Auto-hide controls for video only
    LaunchedEffect(state.isPlaying, showControls, state.isVideo) {
        if (state.isVideo && state.isPlaying && showControls) {
            delay(5000)
            showControls = false
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .focusable()
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { if (state.isVideo) showControls = !showControls }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    when (it.key) {
                        Key.Back -> {
                            if (showPlaylist) {
                                onClosePlaylist()
                                true
                            } else if (!state.isVideo) {
                                onBack()
                                true
                            } else false
                        }
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            if (state.isVideo) showControls = !showControls
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. Background (Video or Blurred Album Art with smooth transition)
        if (state.isVideo) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val scope = this
                val containerWidth = scope.maxWidth
                val containerHeight = scope.maxHeight
                val containerRatio = if (containerHeight > 0.dp) containerWidth / containerHeight else 16f / 9f

                val videoModifier = when (state.videoScaleMode) {
                    VideoScaleMode.STRETCH, VideoScaleMode.CROP -> Modifier.fillMaxSize()
                    else -> {
                        val targetRatio = when (state.videoScaleMode) {
                            VideoScaleMode.RATIO_16_9 -> 16f / 9f
                            VideoScaleMode.RATIO_4_3 -> 4f / 3f
                            else -> state.nativeAspectRatio
                        }

                        if (targetRatio <= containerRatio) {
                            Modifier.fillMaxHeight().width(containerHeight * targetRatio)
                        } else {
                            Modifier.fillMaxWidth().height(containerWidth / targetRatio)
                        }
                    }
                }

                Box(modifier = videoModifier) {
                    if (videoSurface != null) {
                        videoSurface(true)
                    } else {
                        VideoSurfaceView(
                            onSurfaceCreated = onSurfaceCreated,
                            onSurfaceDestroyed = onSurfaceDestroyed
                        )
                    }
                }
            }
        } else {
            // Blurred Background for Audio with Crossfade
            val backgroundSource = state.albumArtUrl ?: state.albumArtBytes
            Crossfade(
                targetState = backgroundSource,
                animationSpec = tween(1200, easing = LinearOutSlowInEasing),
                label = "BackgroundCrossfade"
            ) { source ->
                Box(modifier = Modifier.fillMaxSize()) {
                    if (source is String) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(source)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.25f
                        )
                    } else if (source is ByteArray) {
                        val bitmap = remember(source) {
                            try { android.graphics.BitmapFactory.decodeByteArray(source, 0, source.size) } catch (_: Exception) { null }
                        }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                alpha = 0.25f
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }

        // 2. Audio Layout (Two Columns) or Video Overlay
        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.isVideo) {
                AudioTvLayout(
                    state = state,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatModeChange = onRepeatModeChange,
                    onSettingsClick = onSettingsClick,
                    playlistIndex = playlistIndex,
                    playlistTotal = playlistTotal,
                    onSeek = onSeek,
                    onSeekMs = onSeekMs,
                    isKeepScreenOn = isKeepScreenOn,
                    onToggleKeepScreenOn = onToggleKeepScreenOn,
                    onPlaylistClick = onPlaylistClick,
                    showPlaylist = showPlaylist
                )
            } else if (showControls) {
                VideoTvOverlay(
                    state = state,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatModeChange = onRepeatModeChange,
                    onSettingsClick = onSettingsClick
                )
            }

            // 播放列表侧边面板 (Overlay 覆盖歌词区域)
            AnimatedVisibility(
                visible = showPlaylist,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                TvPlaylistSidePanel(
                    state = state,
                    playlist = playlist,
                    currentIndex = playlistIndex - 1,
                    onPlayItem = {
                        onPlayFromPlaylist(it)
                        onClosePlaylist()
                    },
                    onClose = onClosePlaylist
                )
            }
        }
    }
}

@Composable
fun TvPlaylistSidePanel(
    state: PlayerState,
    playlist: List<com.mediaplayer.plus.data.MediaItem>,
    currentIndex: Int,
    onPlayItem: (Int) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    
    // 打开时自动滚动到当前项
    LaunchedEffect(true) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 左侧点击区域：点击关闭列表
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClose() })
                }
        )

        // 右侧侧边栏内容
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.45f) // 占据右侧 45% 的宽度，大致覆盖歌词区
                .align(Alignment.CenterEnd)
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { } // 阻止点击事件穿透
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "播放列表 (${playlist.size})",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(playlist) { index, item ->
                        var isItemFocused by remember { mutableStateOf(false) }
                        val itemFocusRequester = remember { FocusRequester() }
                        
                        if (index == currentIndex) {
                            LaunchedEffect(true) {
                                delay(150)
                                try { itemFocusRequester.requestFocus() } catch (_: Exception) {}
                            }
                        }

                        Surface(
                            onClick = { onPlayItem(index) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .focusRequester(itemFocusRequester)
                                .onFocusChanged { isItemFocused = it.isFocused }
                                .onKeyEvent {
                                    // 🌟 TV 遥控器：在列表项上按左键直接关闭列表
                                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                                        onClose()
                                        true
                                    } else false
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isItemFocused) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isItemFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    color = if (index == currentIndex) Color(0xFF64B5F6) else Color.Gray,
                                    fontSize = 14.sp,
                                    modifier = Modifier.width(28.dp)
                                )
                                Column {
                                    Text(
                                        text = item.title,
                                        color = if (index == currentIndex) Color(0xFF64B5F6) else Color.White,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (index == currentIndex) {
                                        Text("正在播放", color = Color(0xFF64B5F6).copy(alpha = 0.7f), fontSize = 11.sp)
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
fun AudioTvLayout(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatModeChange: (RepeatMode) -> Unit,
    onSettingsClick: () -> Unit,
    playlistIndex: Int = 0,
    playlistTotal: Int = 0,
    onSeek: (Float) -> Unit = {},
    onSeekMs: (Long) -> Unit = {},
    isKeepScreenOn: Boolean = false,
    onToggleKeepScreenOn: () -> Unit = {},
    onPlaylistClick: () -> Unit = {},
    showPlaylist: Boolean = false
) {
    val themeColor = Color(state.themeColor)
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val playPauseFr = remember { FocusRequester() }

    // 自动聚焦到播放/暂停按钮
    LaunchedEffect(Unit) {
        delay(200)
        try { playPauseFr.requestFocus() } catch (_: Exception) {}
    }

    // 关闭播放列表后，焦点返回播放列表按钮（仅在从 true→false 时触发，初始加载不触发）
    var prevShowPlaylist by remember { mutableStateOf(showPlaylist) }
    LaunchedEffect(showPlaylist) {
        if (prevShowPlaylist && !showPlaylist) {
            delay(200)
            try { focusRequesters["playlist"]?.requestFocus() } catch (_: Exception) {}
        }
        prevShowPlaylist = showPlaylist
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (state.title.isNotBlank()) onPlayPause()
                            true
                        }
                        Key.MediaPlayPause -> { onPlayPause(); true }
                        Key.MediaNext -> { onNext(); true }
                        Key.MediaPrevious -> { onPrevious(); true }
                        Key.MediaFastForward -> { onSeek(state.currentPositionMs.toFloat() / state.durationMs.coerceAtLeast(1) + 0.05f); true }
                        Key.MediaRewind -> { onSeek(state.currentPositionMs.toFloat() / state.durationMs.coerceAtLeast(1) - 0.05f); true }
                        else -> false
                    }
                } else false
            }
    ) {
        val screenHeightDp = maxHeight
        val screenWidthDp = maxWidth
        
        // Use responsive padding based on screen size
        val horizontalPadding = (screenWidthDp * 0.05f).coerceAtLeast(20.dp)
        val verticalPadding = (screenHeightDp * 0.04f).coerceAtLeast(16.dp)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            // Left Column: Art + Info + Controls
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // Album Art (Responsive Size)
                val artSize = (screenHeightDp * 0.42f).coerceIn(160.dp, 320.dp)
                Surface(
                    modifier = Modifier.size(artSize),
                    shape = RoundedCornerShape(artSize * 0.05f),
                    color = Color.DarkGray,
                    tonalElevation = 8.dp
                ) {
                    if (state.albumArtUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(state.albumArtUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (state.albumArtBytes != null) {
                        val bitmap = remember(state.albumArtBytes) {
                            try { android.graphics.BitmapFactory.decodeByteArray(state.albumArtBytes, 0, state.albumArtBytes.size) } catch (_: Exception) { null }
                        }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(artSize * 0.3f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(screenHeightDp * 0.02f))

                // Info (Responsive Font Sizes)
                val titleSize = (screenHeightDp.value * 0.04f).coerceIn(16f, 26f).sp
                val artistSize = (screenHeightDp.value * 0.03f).coerceIn(13f, 18f).sp
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.artist,
                    color = Color.LightGray,
                    fontSize = artistSize,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Calculate available width for left column controls
                val leftColWidth = (screenWidthDp - horizontalPadding * 2) * 0.45f
                // Responsive button size: clamp by both height and available width
                val heightBasedBtnSize = (screenHeightDp * 0.11f).coerceIn(36.dp, 72.dp)
                // 7 buttons with SpaceEvenly: 1x full + 2x 0.8x + 4x 0.7x = 5.4x total + ~15% spacing
                val widthBasedBtnSize = (leftColWidth / 6.2f).coerceIn(36.dp, 72.dp)
                val btnSize = minOf(heightBasedBtnSize, widthBasedBtnSize)

                // 条形进度条
                SimpleTvSeekBar(
                    progress = if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs else 0f,
                    onSeek = onSeek,
                    currentPos = state.currentPositionMs,
                    duration = state.durationMs,
                    currentIndex = playlistIndex,
                    totalCount = playlistTotal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                )

                Spacer(modifier = Modifier.height(screenHeightDp * 0.025f))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    // 不锁屏按钮
                    val lockFr = remember { focusRequesters.getOrPut("lock") { FocusRequester() } }
                    var lockFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = onToggleKeepScreenOn,
                        modifier = Modifier
                            .size(btnSize * 0.7f)
                            .focusRequester(lockFr)
                            .onFocusChanged { lockFocused = it.isFocused }
                            .then(if (lockFocused) Modifier.background(Color(0xFF334155), RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Icon(
                            imageVector = if (isKeepScreenOn) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Keep Screen On",
                            tint = if (isKeepScreenOn) themeColor else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(btnSize * 0.4f)
                        )
                    }

                    // Shuffle
                    val shuffleFr = remember { focusRequesters.getOrPut("shuffle") { FocusRequester() } }
                    var shuffleFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = onShuffleToggle,
                        modifier = Modifier
                            .size(btnSize * 0.7f)
                            .focusRequester(shuffleFr)
                            .onFocusChanged { shuffleFocused = it.isFocused }
                            .then(if (shuffleFocused) Modifier.background(Color(0xFF334155), RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (state.isShuffle) themeColor else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(btnSize * 0.4f)
                        )
                    }

                    // Previous
                    val prevFr = remember { focusRequesters.getOrPut("prev") { FocusRequester() } }
                    var prevFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .size(btnSize * 0.8f)
                            .focusRequester(prevFr)
                            .onFocusChanged { prevFocused = it.isFocused }
                            .then(if (prevFocused) Modifier.background(Color(0xFF334155), RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(btnSize * 0.6f))
                    }

                    // Play/Pause
                    var playPauseFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(btnSize)
                            .focusRequester(playPauseFr)
                            .onFocusChanged { playPauseFocused = it.isFocused }
                            .then(if (playPauseFocused) Modifier.background(Color(0xFF1A237E), RoundedCornerShape(12.dp)) else Modifier)
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = if (playPauseFocused) Color.White else Color(0xFF64B5F6),
                            modifier = Modifier.size(btnSize)
                        )
                    }

                    // Next
                    val nextFr = remember { focusRequesters.getOrPut("next") { FocusRequester() } }
                    var nextFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(btnSize * 0.8f)
                            .focusRequester(nextFr)
                            .onFocusChanged { nextFocused = it.isFocused }
                            .then(if (nextFocused) Modifier.background(Color(0xFF334155), RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(btnSize * 0.6f))
                    }

                    // Repeat
                    val repeatFr = remember { focusRequesters.getOrPut("repeat") { FocusRequester() } }
                    var repeatFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            val nextMode = when (state.repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            }
                            onRepeatModeChange(nextMode)
                        },
                        modifier = Modifier
                            .size(btnSize * 0.7f)
                            .focusRequester(repeatFr)
                            .onFocusChanged { repeatFocused = it.isFocused }
                            .then(if (repeatFocused) Modifier.background(Color(0xFF334155), RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        val (icon, tint) = when (state.repeatMode) {
                            RepeatMode.OFF -> Icons.Default.Repeat to Color.White.copy(alpha = 0.5f)
                            RepeatMode.ALL -> Icons.Default.Repeat to themeColor
                            RepeatMode.ONE -> Icons.Default.RepeatOne to themeColor
                        }
                        Icon(icon, contentDescription = "Repeat", tint = tint, modifier = Modifier.size(btnSize * 0.4f))
                    }

                    // 播放列表按钮
                    val playlistFr = remember { focusRequesters.getOrPut("playlist") { FocusRequester() } }
                    var playlistFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = onPlaylistClick,
                        modifier = Modifier
                            .size(btnSize * 0.7f)
                            .focusRequester(playlistFr)
                            .onFocusChanged { playlistFocused = it.isFocused }
                            .then(if (playlistFocused) Modifier.background(Color(0xFF334155), RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = "Playlist",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(btnSize * 0.5f)
                        )
                    }
                }
            }

            // Right Column: Lyrics
            Column(
                modifier = Modifier.weight(0.55f).fillMaxHeight().padding(start = horizontalPadding),
                verticalArrangement = Arrangement.Center
            ) {
                TvLyricsView(state, screenHeightDp, onSeekMs)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTvSeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    currentPos: Long,
    duration: Long,
    currentIndex: Int = 0,
    totalCount: Int = 0,
    modifier: Modifier = Modifier
) {
    var dragProgress by remember { mutableStateOf(progress) }
    var isDragging by remember { mutableStateOf(false) }
    val seekBarFr = remember { FocusRequester() }
    var isSeekBarFocused by remember { mutableStateOf(false) }
    
    LaunchedEffect(progress, isDragging) {
        if (!isDragging) dragProgress = progress
    }
    
    Column(modifier = modifier) {
        // 使用更加扁平化的自定义进度条样式
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .focusRequester(seekBarFr)
                .focusable()
                .onFocusChanged { isSeekBarFocused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && duration > 0) {
                        when (event.key) {
                            Key.DirectionRight -> {
                                val newPos = (currentPos + 5000).coerceAtMost(duration)
                                onSeek(newPos.toFloat() / duration)
                                true
                            }
                            Key.DirectionLeft -> {
                                val newPos = (currentPos - 5000).coerceAtLeast(0)
                                onSeek(newPos.toFloat() / duration)
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .then(if (isSeekBarFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            // 背景轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            )
            
            // 已播放部分
            Box(
                modifier = Modifier
                    .fillMaxWidth(dragProgress)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF64B5F6))
            )

            // 交互层：使用透明 Slider 处理手势
            Slider(
                value = dragProgress,
                onValueChange = { 
                    isDragging = true
                    dragProgress = it 
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek(dragProgress)
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                thumb = {
                    Box(modifier = Modifier.size(0.dp))
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text(
                text = tvFormatTime(currentPos),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            
            Text(
                text = "$currentIndex / $totalCount",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            
            Text(
                text = tvFormatTime(duration),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

private fun tvFormatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun VideoTvOverlay(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatModeChange: (RepeatMode) -> Unit,
    onSettingsClick: () -> Unit
) {
    val themeColor = Color(state.themeColor)
    // Dark Overlay
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))

    // Top Bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.End
    ) {
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "正在播放视频",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = state.title,
                color = Color.White,
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(color = Color.Black, blurRadius = 8f)
                ),
                maxLines = 1
            )
            Text(
                text = state.artist,
                color = Color.LightGray,
                fontSize = 20.sp
            )

            Spacer(modifier = Modifier.height(60.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val btnSize = 80.dp
                
                // Shuffle
                IconButton(onClick = onShuffleToggle, modifier = Modifier.size(btnSize * 0.7f)) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.isShuffle) themeColor else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(btnSize * 0.4f)
                    )
                }

                IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(modifier = Modifier.width(32.dp))
                IconButton(onClick = onPlayPause, modifier = Modifier.size(btnSize)) {
                    Icon(
                        if (state.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(80.dp)
                    )
                }
                Spacer(modifier = Modifier.width(32.dp))
                IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
                
                // Repeat
                IconButton(
                    onClick = {
                        val nextMode = when (state.repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        }
                        onRepeatModeChange(nextMode)
                    },
                    modifier = Modifier.size(btnSize * 0.7f)
                ) {
                    val (icon, tint) = when (state.repeatMode) {
                        RepeatMode.OFF -> Icons.Default.Repeat to Color.White.copy(alpha = 0.5f)
                        RepeatMode.ALL -> Icons.Default.Repeat to Color(0xFF64B5F6)
                        RepeatMode.ONE -> Icons.Default.RepeatOne to Color(0xFF64B5F6)
                    }
                    Icon(icon, contentDescription = "Repeat", tint = tint, modifier = Modifier.size(btnSize * 0.4f))
                }
            }
        }
        
        // Bottom Hint
        Text(
            text = "使用遥控器方向键操作，点击屏幕显示/隐藏控制栏",
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            fontSize = 14.sp
        )
    }
}

@Composable
fun TvLyricsView(state: PlayerState, screenHeight: androidx.compose.ui.unit.Dp, onSeek: (Long) -> Unit = {}) {
    val listState = rememberLazyListState()
    
    // Reset scroll position when the track changes
    LaunchedEffect(state.title, state.artist) {
        listState.scrollToItem(0)
    }

    if (state.lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val emptyLyricSize = (screenHeight.value * 0.04f).coerceIn(16f, 24f).sp
            Text("暂无歌词", color = Color.DarkGray, fontSize = emptyLyricSize)
        }
        return
    }

    // Auto-scroll to current lyric
    LaunchedEffect(state.currentLyricIndex) {
        if (state.currentLyricIndex >= 0) {
            listState.animateScrollToItem(state.currentLyricIndex, scrollOffset = -(screenHeight.value * 0.3f).toInt())
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = screenHeight * 0.4f)
    ) {
        itemsIndexed(state.lyrics) { index, lyric ->
            val isCurrent = index == state.currentLyricIndex
            val color by animateColorAsState(if (isCurrent) Color.White else Color.Gray.copy(alpha = 0.5f))
            
            val lyricSize = if (isCurrent) {
                (screenHeight.value * 0.05f).coerceIn(24f, 36f).sp
            } else {
                (screenHeight.value * 0.04f).coerceIn(20f, 28f).sp
            }
            Text(
                text = lyric.text,
                color = color,
                fontSize = lyricSize,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clickable { onSeek(lyric.timeMillis) }
            )
        }
    }
}

// 已删除重复的 VideoSurfaceView，统一使用 PlayerScreen.kt 中的版本
