package com.mediaplayer.plus.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.compose.ui.unit.Dp
import com.mediaplayer.plus.player.VideoScaleMode
import com.mediaplayer.plus.player.PlayerState
import com.mediaplayer.plus.player.TrackInfo
import com.mediaplayer.plus.player.SubtitleOverlay
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class GestureHudType { NONE, BRIGHTNESS, VOLUME, ASPECT_RATIO }

enum class GestureType { NONE, LOCKED_SEEK, LOCKED_VOLUME_BRIGHTNESS }

/**
 * 仿 MX Player 全屏视频播放器 UI (修复版)
 */
@Composable
fun FullScreenVideoOverlay(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekByOffsetMs: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onNext: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onSelectAudioTrack: (Int) -> Unit = {},
    onSelectSubtitleTrack: (Int) -> Unit = {},
    onSelectVideoTrack: (Int) -> Unit = {},
    onDecoderChange: (String) -> Unit = {},
    onAspectRatioChange: () -> Unit = {},
    onPipClick: () -> Unit = {},
    onRefreshTrackList: () -> Unit = {},
    onImportSubtitle: () -> Unit = {},
    onImportAudioTrack: () -> Unit = {},
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onExitFullscreen: () -> Unit,
    onPlaylistClick: () -> Unit = {},
    showPlaylist: Boolean = false,
    videoSurface: (@Composable (Boolean) -> Unit)? = null,
    showBackArrow: Boolean = true,
    isInPipMode: Boolean = false,
    onCanvasSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    playlist: List<com.mediaplayer.plus.data.MediaItem> = emptyList(),
    playlistIndex: Int = 0,
    onPlayFromPlaylist: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val statusBarPad = 24.dp

    var showControls by remember { mutableStateOf(false) }
    var lastKeyPressTime by remember { mutableStateOf(0L) }
    val playPauseFocusRequester = remember { FocusRequester() }
    val playlistFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showControls) {
        if (showControls && !showPlaylist) {
            // 当控制栏显示时，尝试将焦点落到播放按钮上，方便遥控器操作
            try {
                playPauseFocusRequester.requestFocus()
            } catch (e: Exception) {
                Log.e("TV_FOCUS", "Failed to request focus", e)
            }
        }
    }

    LaunchedEffect(showPlaylist) {
        if (showPlaylist) {
            try {
                playlistFocusRequester.requestFocus()
            } catch (e: Exception) {}
        } else if (showControls) {
            // 关闭播放列表后，焦点返回播放列表按钮
            try {
                playlistFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }
    var isLocked by remember { mutableStateOf(false) }
    var showUnlockHint by remember { mutableStateOf(false) }

    LaunchedEffect(showUnlockHint) {
        if (showUnlockHint) {
            delay(2000)
            showUnlockHint = false
        }
    }

    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }
    var showVideoTrackMenu by remember { mutableStateOf(false) }
    var showDecoderMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    var draggingProgress by remember { mutableFloatStateOf(-1f) }

    var hudType by remember { mutableStateOf(GestureHudType.BRIGHTNESS) }
    var showGestureHud by remember { mutableStateOf(false) }
    var hudProgress by remember { mutableFloatStateOf(0f) }
    var isDraggingGesture by remember { mutableStateOf(false) }

    // 左右滑动快进/快退 HUD
    var showSeekHud by remember { mutableStateOf(false) }
    var seekHudOffsetMs by remember { mutableLongStateOf(0L) }
    var seekHudDirection by remember { mutableStateOf(1) } // 1=right/+5s, -1=left/-5s
    var seekHudX by remember { mutableFloatStateOf(0f) }

    // 长按 5 倍速
    var isLongPressed by remember { mutableStateOf(false) }
    var originalSpeed by remember { mutableFloatStateOf(1.0f) }
    val coroutineScope = rememberCoroutineScope()

    // 跨 pointerInput 共享的状态
    var gestureActive by remember { mutableStateOf(false) }
    var longPressActive by remember { mutableStateOf(false) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }
    var currentDragType by remember { mutableStateOf(GestureHudType.NONE) }
    var gestureType by remember { mutableStateOf(GestureType.NONE) }
    var startVolume by remember { mutableIntStateOf(0) }
    var startBrightness by remember { mutableFloatStateOf(0.5f) }

    // 🌟 画面比例切换 HUD 状态
    var showAspectRatioHud by remember { mutableStateOf(false) }
    var lastScaleMode by remember { mutableStateOf(state.videoScaleMode) }
    LaunchedEffect(state.videoScaleMode) {
        if (state.videoScaleMode != lastScaleMode) {
            lastScaleMode = state.videoScaleMode
            showAspectRatioHud = true
            delay(1500)
            showAspectRatioHud = false
        }
    }

    LaunchedEffect(hudType, isDraggingGesture) {
        if (!isDraggingGesture && showGestureHud) {
            delay(1200)
            showGestureHud = false
        }
    }

    val isAnyMenuOpen = showAudioMenu || showSubMenu || showDecoderMenu || showSpeedMenu || showPlaylist
    LaunchedEffect(showControls, state.isPlaying, isLocked, isAnyMenuOpen, isInPipMode, lastKeyPressTime) {
        if (isInPipMode) {
            showControls = false
            return@LaunchedEffect
        }
        if (showControls && state.isPlaying && !isLocked && !isAnyMenuOpen) {
            delay(5000)
            val timeSinceLastKey = System.currentTimeMillis() - lastKeyPressTime
            if (timeSinceLastKey >= 5000 && showControls) {
                showControls = false
            }
        }
    }

    BackHandler(enabled = true) {
        if (showPlaylist) {
            onPlaylistClick()
        } else if (showControls && !isLocked) {
            showControls = false
        } else if (isLocked) {
            showUnlockHint = true
        } else {
            onExitFullscreen()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // 保证比例切换时的黑边效果
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                lastKeyPressTime = System.currentTimeMillis()
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Menu -> {
                            showControls = !showControls
                            true
                        }
                        Key.Enter, Key.DirectionCenter -> {
                            if (!showControls) {
                                showControls = true
                                true
                            } else {
                                false // 让系统处理点击事件，如点击已获焦的按钮
                            }
                        }
                        Key.DirectionLeft -> {
                            if (!showControls) {
                                onSeekByOffsetMs(-10000L)
                                true
                            } else false
                        }
                        Key.DirectionRight -> {
                            if (!showControls) {
                                onSeekByOffsetMs(10000L)
                                true
                            } else false
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (!showControls) {
                                showControls = true
                                true
                            } else false
                        }
                        Key.Back -> {
                            if (showPlaylist) {
                                onPlaylistClick()
                                true
                            } else if (showControls && !isLocked) {
                                showControls = false
                                true
                            } else if (isLocked) {
                                showUnlockHint = true
                                true
                            } else {
                                onExitFullscreen()
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
            .pointerInput(Unit) {
                val edgeMargin = size.width * 0.05f
                val centerZoneStart = size.width * 0.2f
                val centerZoneEnd = size.width * 0.8f
                val centerZoneYStart = size.height * 0.2f
                val centerZoneYEnd = size.height * 0.8f

                detectTapGestures(
                    onTap = { offset ->
                        if (isLocked) {
                            showUnlockHint = true
                        } else {
                            showControls = !showControls
                        }
                    },
                    onDoubleTap = { offset ->
                        if (isLocked) return@detectTapGestures
                        val screenWidth = size.width
                        if (offset.x < screenWidth / 3f) {
                            onSeekByOffsetMs(-10000L)
                        } else if (offset.x > screenWidth * 2f / 3f) {
                            onSeekByOffsetMs(10000L)
                        } else {
                            onPlayPause()
                        }
                    },
                    onPress = { pressOffset ->
                        if (pressOffset.x in centerZoneStart..centerZoneEnd &&
                            pressOffset.y in centerZoneYStart..centerZoneYEnd &&
                            !isLocked) {
                            try {
                                delay(500)
                                if (!gestureActive && !longPressActive) {
                                    longPressActive = true
                                    isLongPressed = true
                                    originalSpeed = state.playbackSpeed
                                    onSpeedChange(5.0f)
                                    awaitRelease()
                                }
                            } finally {
                                longPressActive = false
                                isLongPressed = false
                                onSpeedChange(originalSpeed)
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput

                val edgeMargin = size.width * 0.05f
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                detectDragGestures(
                    onDragStart = { offset ->
                        gestureActive = true
                        dragStartX = offset.x
                        dragStartY = offset.y
                        totalDragX = 0f
                        totalDragY = 0f
                        currentDragType = GestureHudType.NONE
                        gestureType = GestureType.NONE
                        hudType = GestureHudType.NONE
                        if (offset.x < edgeMargin || offset.x > size.width - edgeMargin) {
                            return@detectDragGestures
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y

                        when (gestureType) {
                            GestureType.LOCKED_SEEK -> {
                                val threshold = 80f
                                val steps = (totalDragX / threshold).toInt()
                                if (steps != 0) {
                                    val offsetMs = steps * 5000L
                                    onSeekByOffsetMs(offsetMs)
                                    totalDragX = (totalDragX % threshold)
                                    seekHudDirection = if (steps > 0) 1 else -1
                                    seekHudOffsetMs = offsetMs * seekHudDirection
                                    seekHudX = change.position.x
                                    showSeekHud = true
                                }
                                return@detectDragGestures
                            }
                            GestureType.LOCKED_VOLUME_BRIGHTNESS -> {
                                val deltaY = dragStartY - change.position.y
                                val sensitivity = size.height * 0.7f
                                showGestureHud = true
                                isDraggingGesture = true
                                if (currentDragType == GestureHudType.BRIGHTNESS && activity != null) {
                                    val newBrightness = (startBrightness + (deltaY / sensitivity)).coerceIn(0.01f, 1.0f)
                                    val lp = activity.window.attributes
                                    lp.screenBrightness = newBrightness
                                    activity.window.attributes = lp
                                    hudProgress = newBrightness
                                } else if (currentDragType == GestureHudType.VOLUME) {
                                    val deltaVol = ((deltaY / sensitivity) * maxVolume).toInt()
                                    val newVol = (startVolume + deltaVol).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    hudProgress = newVol.toFloat() / maxVolume
                                }
                                return@detectDragGestures
                            }
                            GestureType.NONE -> {}
                        }

                        if (abs(totalDragX) > 20f) {
                            gestureType = GestureType.LOCKED_SEEK
                            val threshold = 80f
                            val steps = (totalDragX / threshold).toInt()
                            if (steps != 0) {
                                val offsetMs = steps * 5000L
                                onSeekByOffsetMs(offsetMs)
                                totalDragX = (totalDragX % threshold)
                                seekHudDirection = if (steps > 0) 1 else -1
                                seekHudOffsetMs = offsetMs * seekHudDirection
                                seekHudX = change.position.x
                                showSeekHud = true
                            }
                            return@detectDragGestures
                        }

                        if (abs(totalDragY) > 60f) {
                            gestureType = GestureType.LOCKED_VOLUME_BRIGHTNESS
                            val isLeft = dragStartX < size.width / 2f
                            currentDragType = if (isLeft) GestureHudType.BRIGHTNESS else GestureHudType.VOLUME
                            hudType = currentDragType
                            if (currentDragType == GestureHudType.BRIGHTNESS) {
                                val lp = activity?.window?.attributes
                                startBrightness = if (lp?.screenBrightness ?: -1f < 0f) 0.5f else lp!!.screenBrightness
                            } else {
                                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            }
                            val deltaY = dragStartY - change.position.y
                            val sensitivity = size.height * 0.7f
                            showGestureHud = true
                            isDraggingGesture = true
                            if (currentDragType == GestureHudType.BRIGHTNESS && activity != null) {
                                val newBrightness = (startBrightness + (deltaY / sensitivity)).coerceIn(0.01f, 1.0f)
                                val lp = activity.window.attributes
                                lp.screenBrightness = newBrightness
                                activity.window.attributes = lp
                                hudProgress = newBrightness
                            } else if (currentDragType == GestureHudType.VOLUME) {
                                val deltaVol = ((deltaY / sensitivity) * maxVolume).toInt()
                                val newVol = (startVolume + deltaVol).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                hudProgress = newVol.toFloat() / maxVolume
                            }
                        }
                    },
                    onDragEnd = {
                        gestureActive = false
                        isDraggingGesture = false
                        if (showSeekHud) {
                            coroutineScope.launch {
                                delay(600)
                                showSeekHud = false
                            }
                        }
                        // 长按释放：恢复原始播放速度
                        if (longPressActive) {
                            longPressActive = false
                            isLongPressed = false
                            onSpeedChange(originalSpeed)
                        }
                    },
                    onDragCancel = {
                        gestureActive = false
                        isDraggingGesture = false
                        if (longPressActive) {
                            longPressActive = false
                            isLongPressed = false
                            onSpeedChange(originalSpeed)
                        }
                    }
                )
            }
    ) {
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

            // 根据比例模式计算视频容器的 Modifier
            val videoModifier = when (state.videoScaleMode) {
                VideoScaleMode.STRETCH, VideoScaleMode.CROP -> Modifier.fillMaxSize()
                else -> {
                    val targetRatio = when (state.videoScaleMode) {
                        VideoScaleMode.RATIO_16_9 -> 16f / 9f
                        VideoScaleMode.RATIO_4_3 -> 4f / 3f
                        else -> state.nativeAspectRatio // FIT, ORIGINAL
                    }

                    if (targetRatio <= containerRatio) {
                        // 视频比屏幕窄（产生左右黑边）：高度顶格，宽度按比例缩进
                        Modifier.fillMaxHeight().width(containerHeight * targetRatio)
                    } else {
                        // 视频比屏幕宽（产生上下黑边）：宽度顶格，高度按比例缩进
                        Modifier.fillMaxWidth().height(containerWidth / targetRatio)
                    }
                }
            }

            Box(modifier = videoModifier) {
                if (videoSurface != null) {
                    videoSurface(true)
                } else {
                    VideoSurfaceView(
                        isFullScreen = true,
                        onSurfaceCreated = onSurfaceCreated,
                        onSurfaceDestroyed = onSurfaceDestroyed
                    )
                }
            }
        }

        if (isInPipMode) return@Box

        KeepScreenOn(keepOn = state.isVideo && state.isPlaying)

        // 外挂字幕图层（ExoPlayer 内置渲染）
        SubtitleOverlay(
            cues = state.cues,
            modifier = Modifier
                .fillMaxSize(),
            videoW = state.videoWidth,
            videoH = state.videoHeight
        )

        AnimatedVisibility(
            visible = showGestureHud,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                    modifier = Modifier
                        .size(110.dp, 155.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (hudType == GestureHudType.BRIGHTNESS) Icons.Filled.WbSunny else Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Canvas(
                            modifier = Modifier
                                .width(24.dp)
                                .height(64.dp)
                        ) {
                            val barWidth = 5.dp.toPx()
                            val leftX = (size.width - barWidth) / 2
                            val totalH = size.height

                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.2f),
                                topLeft = Offset(leftX, 0f),
                                size = Size(barWidth, totalH),
                                cornerRadius = CornerRadius(barWidth / 2)
                            )

                            val activeHeight = totalH * hudProgress.coerceIn(0f, 1f)
                            if (activeHeight > 0f) {
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color(0xFFFF4081), Color(0xFF7C4DFF), Color(0xFF00E5FF))
                                    ),
                                    topLeft = Offset(leftX, totalH - activeHeight),
                                    size = Size(barWidth, activeHeight),
                                    cornerRadius = CornerRadius(barWidth / 2)
                                )

                                drawCircle(
                                    color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                                    radius = 7.dp.toPx(),
                                    center = Offset(size.width / 2, totalH - activeHeight)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 4.dp.toPx(),
                                    center = Offset(size.width / 2, totalH - activeHeight)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(hudProgress * 100).toInt()}%",
                            color = Color(0xFF00E5FF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
        }

        // 🌟 画面比例切换 HUD
        AnimatedVisibility(
            visible = showAspectRatioHud,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.AspectRatio,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = state.videoScaleMode.label,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ⏩ 左右滑动快进快退 HUD
        AnimatedVisibility(
            visible = showSeekHud,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (seekHudDirection > 0) Icons.Filled.Forward5 else Icons.Filled.Replay5,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "${seekHudOffsetMs / 1000}s",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ⚡ 长按 5 倍速 HUD
        AnimatedVisibility(
            visible = isLongPressed,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "5.0×",
                        color = Color(0xFFFFD54F),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "长按加速播放",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (isLocked) {
            AnimatedVisibility(
                visible = showUnlockHint,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                IconButton(
                    onClick = { isLocked = false },
                    modifier = Modifier.padding(16.dp + statusBarPad, bottom = 16.dp + statusBarPad)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = "解锁",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp + statusBarPad, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBackArrow) {
                        IconButton(onClick = onExitFullscreen) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = java.io.File(state.mediaPath).name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            var isAudioFocused by remember { mutableStateOf(false) }
                            val audioBgColor by animateColorAsState(
                                if (isAudioFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                                label = "audioBg"
                            )
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(audioBgColor)
                                    .onFocusChanged { isAudioFocused = it.isFocused }
                                    .focusable()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showAudioMenu = true }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.MusicNote, contentDescription = "音轨", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = showAudioMenu,
                                onDismissRequest = { showAudioMenu = false },
                                modifier = Modifier.background(Color(0xFF22222B)),
                                offset = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("导入音轨", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = {
                                        onImportAudioTrack()
                                        showAudioMenu = false
                                    }
                                )
                                if (state.audioTracks.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("无可加音轨", color = Color.Gray) },
                                        onClick = {}
                                    )
                                } else {
                                    state.audioTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = track.title,
                                                        color = if (track.isSelected) Color(0xFF00E5FF) else Color.White,
                                                        fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (track.isSelected) {
                                                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF00E5FF))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                onSelectAudioTrack(track.id)
                                                showAudioMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Box {
                            var isSubFocused by remember { mutableStateOf(false) }
                            val subBgColor by animateColorAsState(
                                if (isSubFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                                label = "subBg"
                            )
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(subBgColor)
                                    .onFocusChanged { isSubFocused = it.isFocused }
                                    .focusable()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showSubMenu = true }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Subtitles, contentDescription = "字幕", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = showSubMenu,
                                onDismissRequest = { showSubMenu = false },
                                modifier = Modifier.background(Color(0xFF22222B)),
                                offset = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("导入字幕", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = {
                                        onImportSubtitle()
                                        showSubMenu = false
                                    }
                                )
                                state.subtitleTracks.forEach { sub ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = sub.title,
                                                    color = if (sub.isSelected) Color(0xFF00E5FF) else Color.White,
                                                    fontWeight = if (sub.isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (sub.isSelected) {
                                                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF00E5FF))
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSelectSubtitleTrack(sub.id)
                                            showSubMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        if (state.videoTracks.size > 1) {
                            Box {
                                var isVideoTrackFocused by remember { mutableStateOf(false) }
                                val videoTrackBgColor by animateColorAsState(
                                    if (isVideoTrackFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                                    label = "videoTrackBg"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(videoTrackBgColor)
                                        .onFocusChanged { isVideoTrackFocused = it.isFocused }
                                        .focusable()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { showVideoTrackMenu = true }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.VideoSettings, contentDescription = "视频轨道", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showVideoTrackMenu,
                                    onDismissRequest = { showVideoTrackMenu = false },
                                    modifier = Modifier.background(Color(0xFF22222B)),
                                    offset = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp)
                                ) {
                                    state.videoTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = track.title,
                                                        color = if (track.isSelected) Color(0xFF00E5FF) else Color.White,
                                                        fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (track.isSelected) {
                                                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF00E5FF))
                                                    }
                                                }
                                            },
                                            onClick = {
                                                onSelectVideoTrack(track.id)
                                                showVideoTrackMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Box {
                            var isDecoderFocused by remember { mutableStateOf(false) }
                            val decoderBgColor by animateColorAsState(
                                if (isDecoderFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                                label = "decoderBg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(decoderBgColor)
                                    .onFocusChanged { isDecoderFocused = it.isFocused }
                                    .focusable()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showDecoderMenu = true }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = state.currentDecoder,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            DropdownMenu(
                                expanded = showDecoderMenu,
                                onDismissRequest = { showDecoderMenu = false },
                                modifier = Modifier.background(Color(0xFF22222B))
                            ) {
                                listOf("自动解码", "硬件解码", "软件解码").forEach { decoder ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = decoder,
                                                    color = if (state.currentDecoder == decoder) Color(0xFF00E5FF) else Color.White,
                                                    fontWeight = if (state.currentDecoder == decoder) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (state.currentDecoder == decoder) {
                                                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF00E5FF))
                                                }
                                            }
                                        },
                                        onClick = {
                                            onDecoderChange(decoder)
                                            showDecoderMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp + statusBarPad, vertical = 8.dp)
            ) {
                val progress = if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs else 0f
                val displayPosition = if (draggingProgress >= 0f) (draggingProgress * state.durationMs).toLong() else state.currentPositionMs

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimeForFullScreen(displayPosition),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    EnergyProgressBar(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        progress = progress,
                        onSeek = { p ->
                            draggingProgress = p
                        },
                        onSeekFinished = { p ->
                            draggingProgress = -1f
                            onSeek(p)
                        }
                    )

                    Text(
                        text = formatTimeForFullScreen(state.durationMs),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    // 锁屏按钮（控制栏最左边，独立）
                    TvControlIconButton(
                        onClick = { isLocked = !isLocked },
                        icon = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (isLocked) "已锁定" else "锁屏",
                        tint = if (isLocked) Color(0xFFFFD700) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.CenterStart)
                    )

                    // 中央控制（绝对居中）
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvControlIconButton(
                            onClick = onToggleShuffle,
                            icon = Icons.Filled.Shuffle,
                            contentDescription = "随机播放",
                            tint = if (state.isShuffle) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.7f)
                        )

                        TvControlIconButton(
                            onClick = { onPrevious?.invoke() },
                            enabled = onPrevious != null,
                            icon = Icons.Filled.SkipPrevious,
                            contentDescription = "上一集",
                            tint = if (onPrevious != null) Color.White else Color.White.copy(alpha = 0.3f),
                            iconSize = 28.dp
                        )

    var isFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isFocused) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f))
                                .onFocusChanged { isFocused = it.isFocused }
                                .focusRequester(playPauseFocusRequester)
                                .focusable()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onPlayPause
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        TvControlIconButton(
                            onClick = { onNext?.invoke() },
                            enabled = onNext != null,
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "下一集",
                            tint = if (onNext != null) Color.White else Color.White.copy(alpha = 0.3f),
                            iconSize = 28.dp
                        )

                        TvControlIconButton(
                            onClick = onToggleRepeat,
                            icon = when (state.repeatMode) {
                                com.mediaplayer.plus.player.RepeatMode.OFF -> Icons.Filled.Repeat
                                com.mediaplayer.plus.player.RepeatMode.ALL -> Icons.Filled.Repeat
                                com.mediaplayer.plus.player.RepeatMode.ONE -> Icons.Filled.RepeatOne
                            },
                            contentDescription = "循环模式",
                            tint = if (state.repeatMode == com.mediaplayer.plus.player.RepeatMode.OFF)
                                Color.White.copy(alpha = 0.7f) else Color(0xFF00E5FF)
                        )
                    }

                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvControlIconButton(
                            onClick = onPlaylistClick,
                            icon = Icons.Filled.QueueMusic,
                            contentDescription = "播放列表",
                            focusRequester = playlistFocusRequester
                        )

                        Box {
                            var isSpeedFocused by remember { mutableStateOf(false) }
                            val speedBgColor by animateColorAsState(
                                if (isSpeedFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                                label = "speedBg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(speedBgColor)
                                    .onFocusChanged { isSpeedFocused = it.isFocused }
                                    .focusable()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showSpeedMenu = true }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (state.playbackSpeed == 1.0f) "倍速" else "${state.playbackSpeed}X",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false },
                                modifier = Modifier.background(Color(0xFF22222B))
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "${speed}X",
                                                    color = if (state.playbackSpeed == speed) Color(0xFF00E5FF) else Color.White,
                                                    fontWeight = if (state.playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (state.playbackSpeed == speed) {
                                                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF00E5FF))
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSpeedChange(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }

                    TvControlIconButton(
                        onClick = onAspectRatioChange,
                        icon = Icons.Filled.AspectRatio,
                        contentDescription = "画面比例 (${state.videoScaleMode.label})"
                    )

                        TvControlIconButton(
                            onClick = onPipClick,
                            icon = Icons.Filled.PictureInPicture,
                            contentDescription = "画中画"
                        )
                    }
                }
            }
        }

        // 🌟 播放列表 Overlay (右侧半屏)
        AnimatedVisibility(
            visible = showPlaylist,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            val listState = rememberLazyListState()
            
            // 🌟 TV 模式：打开时自动滚动到当前项
            LaunchedEffect(showPlaylist) {
                if (showPlaylist && playlistIndex >= 0) {
                    listState.animateScrollToItem(playlistIndex)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // 🌟 左侧点击区：点击播放列表外自动关闭 (完全透明)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onPlaylistClick() })
                        }
                )

                // 🌟 右侧列表区：占据一半宽度，全高度覆盖
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f) // 占据右侧一半空间
                        .background(Color.Black.copy(alpha = 0.8f))
                        .align(Alignment.CenterEnd)
                        .clickable(enabled = true, onClick = {}) // 阻止点击事件穿透
                ) {
                    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
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
                            IconButton(onClick = { onPlaylistClick() }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                            }
                        }
                        
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                        ) {
                            itemsIndexed(playlist) { index, item ->
                                var isItemFocused by remember { mutableStateOf(false) }
                                
                                // 🌟 TV 自动获取焦点的核心：如果是当前播放项，在打开列表时强行请求焦点
                                val itemFocusRequester = remember { FocusRequester() }
                                if (showPlaylist && index == playlistIndex) {
                                    LaunchedEffect(showPlaylist) {
                                        delay(150) // 略微延迟以避开 UI 绘制冲突
                                        try {
                                            itemFocusRequester.requestFocus()
                                        } catch (e: Exception) {}
                                    }
                                }

                                Surface(
                                    onClick = { 
                                        onPlayFromPlaylist(index)
                                        // 手机模式下点击切歌后通常需要关闭列表，或者保持打开由用户手动关闭
                                        // 这里遵循 TV/手机 通用逻辑：切歌后自动关闭列表以便观看
                                        onPlaylistClick()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .focusRequester(itemFocusRequester)
                                        .onFocusChanged { isItemFocused = it.isFocused },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isItemFocused) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color.Transparent,
                                    border = if (isItemFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF00E5FF)) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            color = if (index == playlistIndex) Color(0xFF00E5FF) else Color.Gray,
                                            fontSize = 14.sp,
                                            modifier = Modifier.width(28.dp)
                                        )
                                        Column {
                                            Text(
                                                text = item.title,
                                                color = if (index == playlistIndex) Color(0xFF00E5FF) else Color.White,
                                                fontSize = 16.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (index == playlistIndex) {
                                                Text("正在播放", color = Color(0xFF00E5FF).copy(alpha = 0.7f), fontSize = 11.sp)
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

/**
 * TV 专用高亮控制按钮
 */
@Composable
fun TvControlIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.White,
    iconSize: Dp = 24.dp,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.2f else 1.0f, label = "scale")
    val backgroundColor by animateColorAsState(
        if (isFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
        label = "bg"
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun EnergyProgressBar(
    modifier: Modifier = Modifier,
    progress: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: ((Float) -> Unit)? = null
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var isFocused by remember { mutableStateOf(false) }

    val currentProgress = if (isDragging) dragProgress else progress

    Box(
        modifier = modifier
            .height(26.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable() // 使进度条在 TV 模式下可获获焦
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onSeek((progress - 0.05f).coerceIn(0f, 1f))
                            onSeekFinished?.invoke((progress - 0.05f).coerceIn(0f, 1f))
                            true
                        }
                        Key.DirectionRight -> {
                            onSeek((progress + 0.05f).coerceIn(0f, 1f))
                            onSeekFinished?.invoke((progress + 0.05f).coerceIn(0f, 1f))
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(newProgress)
                    onSeekFinished?.invoke(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeekFinished?.invoke(dragProgress)
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = newProgress
                        onSeek(newProgress)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val barHeight = 6.dp.toPx()
            val topY = (height - barHeight) / 2

            drawRoundRect(
                color = Color.White.copy(alpha = 0.2f),
                topLeft = Offset(0f, topY),
                size = Size(width, barHeight),
                cornerRadius = CornerRadius(barHeight / 2)
            )

            val activeWidth = width * currentProgress.coerceIn(0f, 1f)
            if (activeWidth > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFFFF4081))
                    ),
                    topLeft = Offset(0f, topY),
                    size = Size(activeWidth, barHeight),
                    cornerRadius = CornerRadius(barHeight / 2)
                )

                drawCircle(
                    color = if (isFocused) Color.White else Color(0xFF00E5FF).copy(alpha = 0.5f),
                    radius = (if (isFocused) 10.dp else 8.dp).toPx(),
                    center = Offset(activeWidth, height / 2)
                )
                drawCircle(
                    color = if (isFocused) Color(0xFF00E5FF) else Color.White,
                    radius = (if (isFocused) 6.dp else 5.dp).toPx(),
                    center = Offset(activeWidth, height / 2)
                )
            }
        }
    }
}

fun toggleSystemFullScreen(context: Context, isFullscreen: Boolean) {
    val activity = context.findActivity() ?: return
    val window = activity.window
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)

    // 🌟 核心补正：确保在切换全屏状态时，始终保持 DecorFitsSystemWindows(false)
    // 否则在 Android 9 等系统上，hide 操作可能无法完全生效或导致布局抖动
    WindowCompat.setDecorFitsSystemWindows(window, false)

    if (isFullscreen) {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 🌟 进入全屏：强制横屏
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        // 🌟 退出全屏：恢复默认
        if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatTimeForFullScreen(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}