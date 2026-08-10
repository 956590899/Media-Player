package com.mediaplayer.plus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
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
    videoSurface: (@Composable (Boolean) -> Unit)? = null
) {
    // Intercept back button to return to home screen instead of exiting app
    BackHandler {
        onBack()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .clickable { if (state.isVideo) showControls = !showControls },
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
        if (!state.isVideo) {
            AudioTvLayout(
                state = state,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onShuffleToggle = onShuffleToggle,
                onRepeatModeChange = onRepeatModeChange,
                onSettingsClick = onSettingsClick
            )
        } else if (showControls) {
            Box(modifier = Modifier.fillMaxSize()) {
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
    onSettingsClick: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightDp = maxHeight
        val screenWidthDp = maxWidth
        
        // Use responsive padding based on screen size
        val horizontalPadding = (screenWidthDp * 0.05f).coerceAtLeast(24.dp)
        val verticalPadding = (screenHeightDp * 0.05f).coerceAtLeast(24.dp)

        Row(modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = verticalPadding)) {
            // Left Column: Art + Info + Controls
            Column(
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(screenWidthDp * 0.02f))

                // Album Art (Responsive Size)
                val artSize = (screenHeightDp * 0.5f).coerceIn(200.dp, 400.dp)
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

                Spacer(modifier = Modifier.height(screenHeightDp * 0.04f))

                // Info (Responsive Font Sizes)
                val titleSize = (screenHeightDp.value * 0.05f).coerceIn(20f, 32f).sp
                val artistSize = (screenHeightDp.value * 0.035f).coerceIn(16f, 24f).sp
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

                Spacer(modifier = Modifier.height(screenHeightDp * 0.05f))

                // Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val btnSize = (screenHeightDp * 0.12f).coerceIn(48.dp, 80.dp)
                    
                    // Shuffle
                    IconButton(onClick = onShuffleToggle, modifier = Modifier.size(btnSize * 0.7f)) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (state.isShuffle) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(btnSize * 0.4f)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))

                    IconButton(onClick = onPrevious, modifier = Modifier.size(btnSize * 0.8f)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(btnSize * 0.6f))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(btnSize)) {
                        Icon(
                            if (state.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(btnSize)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(onClick = onNext, modifier = Modifier.size(btnSize * 0.8f)) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(btnSize * 0.6f))
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))

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
                
                // Settings Button at bottom of left col
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
                }
            }

            // Right Column: Lyrics
            Column(
                modifier = Modifier.weight(0.55f).fillMaxHeight().padding(start = horizontalPadding),
                verticalArrangement = Arrangement.Center
            ) {
                TvLyricsView(state, screenHeightDp)
            }
        }
    }
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
                        tint = if (state.isShuffle) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.5f),
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
fun TvLyricsView(state: PlayerState, screenHeight: androidx.compose.ui.unit.Dp) {
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
            )
        }
    }
}

// 已删除重复的 VideoSurfaceView，统一使用 PlayerScreen.kt 中的版本
