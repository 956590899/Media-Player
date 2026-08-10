package com.mediaplayer.plus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween

// =====================================================================
// ⚙️ 设置页面（页面栈 + 返回手势）
// =====================================================================
enum class SettingsPage {
    Home,
    VideoDecoder,
    ThemeCustom
}

@Composable
fun SettingsScreen(
    floatingLyricsEnabled: Boolean,
    onFloatingLyricsToggle: (Boolean) -> Unit,
    bluetoothLyricsEnabled: Boolean = false,
    onBluetoothLyricsToggle: (Boolean) -> Unit = {},
    uiBackgroundReview: Boolean = false,
    onUiBackgroundReviewToggle: (Boolean) -> Unit = {},
    lyricsFilterEnabled: Boolean = true,
    onLyricsFilterToggle: (Boolean) -> Unit = {},
    libassEnabled: Boolean = false,
    onLibassToggle: (Boolean) -> Unit = {},
    dlnaAlbumArtEnabled: Boolean = false,
    onDlnaAlbumArtToggle: (Boolean) -> Unit = {},
    tvMode: PlayerViewModel.TvMode = PlayerViewModel.TvMode.AUTO,
    onTvModeChange: (PlayerViewModel.TvMode) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var navStack by remember { mutableStateOf(listOf(SettingsPage.Home)) }
    val current = navStack.last()

    // 返回手势：优先退出子页面
    BackHandler(current != SettingsPage.Home) {
        if (navStack.size > 1) navStack = navStack.dropLast(1)
    }
    // 最外层返回：关闭设置页
    BackHandler(current == SettingsPage.Home && onBack != null) {
        onBack?.invoke()
    }

    // 进入子页面
    fun navigateTo(page: SettingsPage) {
        navStack = navStack + page
    }
    // 返回上一级
    fun navigateBack() {
        if (navStack.size > 1) navStack = navStack.dropLast(1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                if (targetState != SettingsPage.Home) {
                    (slideIntoContainer(animationSpec = tween(220), towards = AnimatedContentTransitionScope.SlideDirection.Left) + fadeIn(tween(120))) togetherWith
                    (slideOutOfContainer(animationSpec = tween(220), towards = AnimatedContentTransitionScope.SlideDirection.Left) + fadeOut(tween(120)))
                } else {
                    (slideIntoContainer(animationSpec = tween(220), towards = AnimatedContentTransitionScope.SlideDirection.Right) + fadeIn(tween(120))) togetherWith
                    (slideOutOfContainer(animationSpec = tween(220), towards = AnimatedContentTransitionScope.SlideDirection.Right) + fadeOut(tween(120)))
                }
            },
            label = "settings_page"
        ) { page ->
            when (page) {
                SettingsPage.Home -> SettingsHome(
                    floatingLyricsEnabled = floatingLyricsEnabled,
                    onFloatingLyricsToggle = onFloatingLyricsToggle,
                    bluetoothLyricsEnabled = bluetoothLyricsEnabled,
                    onBluetoothLyricsToggle = onBluetoothLyricsToggle,
                    uiBackgroundReview = uiBackgroundReview,
                    onUiBackgroundReviewToggle = onUiBackgroundReviewToggle,
                    lyricsFilterEnabled = lyricsFilterEnabled,
                    onLyricsFilterToggle = onLyricsFilterToggle,
                    libassEnabled = libassEnabled,
                    onLibassToggle = onLibassToggle,
                    dlnaAlbumArtEnabled = dlnaAlbumArtEnabled,
                    onDlnaAlbumArtToggle = onDlnaAlbumArtToggle,
                    onEnterVideoDecoder = { navigateTo(SettingsPage.VideoDecoder) },
                    onEnterThemeCustom = { navigateTo(SettingsPage.ThemeCustom) }
                )
                SettingsPage.VideoDecoder -> VideoDecoderSubPage(onBack = { navigateBack() })
                SettingsPage.ThemeCustom -> ThemeCustomSubPage(
                    tvMode = tvMode,
                    onTvModeChange = onTvModeChange,
                    onBack = { navigateBack() }
                )
            }
        }
    }
}

// ===== 首页 =====
@Composable
private fun SettingsHome(
    floatingLyricsEnabled: Boolean,
    onFloatingLyricsToggle: (Boolean) -> Unit,
    bluetoothLyricsEnabled: Boolean,
    onBluetoothLyricsToggle: (Boolean) -> Unit,
    uiBackgroundReview: Boolean,
    onUiBackgroundReviewToggle: (Boolean) -> Unit,
    lyricsFilterEnabled: Boolean,
    onLyricsFilterToggle: (Boolean) -> Unit,
    libassEnabled: Boolean,
    onLibassToggle: (Boolean) -> Unit,
    dlnaAlbumArtEnabled: Boolean,
    onDlnaAlbumArtToggle: (Boolean) -> Unit,
    onEnterVideoDecoder: () -> Unit,
    onEnterThemeCustom: () -> Unit
) {
    // 默认不展开；同一时刻只能展开一个分组
    var expandedGroup by remember { mutableIntStateOf(-1) }

    fun toggleGroup(index: Int) {
        if (expandedGroup == index) return
        expandedGroup = index
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { /* 搜索 */ }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Color.White.copy(alpha = 0.6f))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            // ===== 主题与外观 (0) =====
            SettingsAccordionHeader(
                icon = Icons.Filled.Palette,
                iconColor = Color(0xFFBA68C8),
                title = "主题与外观",
                isExpanded = expandedGroup == 0,
                onClick = { toggleGroup(0) }
            )
            AnimatedVisibility(
                visible = expandedGroup == 0,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                    SettingsEntry(
                        title = "界面主题定制",
                        subtitle = "自定义界面颜色、风格、显示模式",
                        onClick = onEnterThemeCustom
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchItem(
                        title = "UI 背景审查",
                        subtitle = "显示歌名/歌词/控件半透明背景",
                        checked = uiBackgroundReview,
                        onCheckedChange = onUiBackgroundReviewToggle
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 歌词与字幕 (1) =====
            SettingsAccordionHeader(
                icon = Icons.Filled.Subtitles,
                iconColor = Color(0xFFE57373),
                title = "歌词与字幕",
                isExpanded = expandedGroup == 1,
                onClick = { toggleGroup(1) }
            )
            AnimatedVisibility(
                visible = expandedGroup == 1,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    SettingsSwitchItem(
                        title = "悬浮歌词",
                        subtitle = "桌面悬浮显示歌词（需悬浮窗权限）",
                        checked = floatingLyricsEnabled,
                        onCheckedChange = onFloatingLyricsToggle
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchItem(
                        title = "蓝牙歌词",
                        subtitle = "通过 AVRCP 向蓝牙耳机/车载推送当前歌词",
                        checked = bluetoothLyricsEnabled,
                        onCheckedChange = onBluetoothLyricsToggle
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchItem(
                        title = "歌词过滤",
                        subtitle = "自动过滤版权信息、翻译标记等冗余内容",
                        checked = lyricsFilterEnabled,
                        onCheckedChange = onLyricsFilterToggle
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchItem(
                        title = "Libass 渲染",
                        subtitle = "使用 Libass 库渲染 ASS/SSA 字幕，支持特效与定位",
                        checked = libassEnabled,
                        onCheckedChange = onLibassToggle
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 音乐 (2) =====
            SettingsAccordionHeader(
                icon = Icons.Filled.MusicNote,
                iconColor = Color(0xFFFFB74D),
                title = "音乐",
                isExpanded = expandedGroup == 2,
                onClick = { toggleGroup(2) }
            )
            AnimatedVisibility(
                visible = expandedGroup == 2,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                SettingsSwitchItem(
                    title = "DLNA 音乐同时推送专辑图",
                    subtitle = "投屏音乐时将专辑封面同步推送至设备",
                    checked = dlnaAlbumArtEnabled,
                    onCheckedChange = onDlnaAlbumArtToggle
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 视频 (3) =====
            SettingsAccordionHeader(
                icon = Icons.Filled.Videocam,
                iconColor = Color(0xFF64B5F6),
                title = "视频",
                isExpanded = expandedGroup == 3,
                onClick = { toggleGroup(3) }
            )
            AnimatedVisibility(
                visible = expandedGroup == 3,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                Text(
                    "暂无设置项",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 解码 (4) =====
            SettingsAccordionHeader(
                icon = Icons.Filled.VideoSettings,
                iconColor = Color(0xFF00E5FF),
                title = "解码",
                isExpanded = expandedGroup == 4,
                onClick = { toggleGroup(4) }
            )
            AnimatedVisibility(
                visible = expandedGroup == 4,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                SettingsEntry(
                    title = "视频解码器",
                    subtitle = "硬解 / HW+ / 软解",
                    onClick = onEnterVideoDecoder
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 其他 (5) =====
            SettingsAccordionHeader(
                icon = Icons.Filled.MoreHoriz,
                iconColor = Color(0xFF81C784),
                title = "其他",
                isExpanded = expandedGroup == 5,
                onClick = { toggleGroup(5) }
            )
            AnimatedVisibility(
                visible = expandedGroup == 5,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                Text(
                    "暂无设置项",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// === 可折叠分组标题 ===
@Composable
private fun SettingsAccordionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = Color(0xFF64B5F6),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = Color(0xFF64B5F6).copy(alpha = 0.7f),
            modifier = Modifier
                .size(20.dp)
                .rotate(if (isExpanded) 180f else 0f)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

// === 可点击设置条目 ===
@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E28))
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 5.dp)
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.padding(end = 5.dp))
    }
}

// === 开关设置条目 ===
@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E28)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 5.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.width(48.dp).height(28.dp).padding(end = 5.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF64B5F6),
                checkedTrackColor = Color(0xFF64B5F6).copy(alpha = 0.3f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

// === 二级：视频解码器子页面（独立页面，返回手势）===
@Composable
private fun VideoDecoderSubPage(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("视频解码器", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Divider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(12.dp))

        listOf(
            "硬解" to "auto-safe — 最省电稳定，不兼容时自动回退软解",
            "HW+" to "auto — 激进硬解，尝试更多硬件路径",
            "软解" to "no — 纯 CPU 解码，最稳定但耗电"
        ).forEach { (title, desc) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E1E28))
                    .clickable { /* TODO */ },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(desc, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

// === 二级：主题定制子页面（独立页面，返回手势）===
@Composable
private fun ThemeCustomSubPage(
    tvMode: PlayerViewModel.TvMode,
    onTvModeChange: (PlayerViewModel.TvMode) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("界面主题定制", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Divider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(12.dp))

        Text("显示模式", color = Color(0xFF64B5F6), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(modifier = Modifier.height(8.dp))

        listOf(
            PlayerViewModel.TvMode.AUTO to "自动检测 (推荐)",
            PlayerViewModel.TvMode.ON to "强制 TV 模式 (横屏)",
            PlayerViewModel.TvMode.OFF to "强制手机模式 (竖屏)"
        ).forEach { (mode, label) ->
            val isSelected = tvMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color(0xFF64B5F6).copy(alpha = 0.15f) else Color(0xFF1E1E28))
                    .clickable { onTvModeChange(mode) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, color = if (isSelected) Color(0xFF64B5F6) else Color.White, fontSize = 15.sp)
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF64B5F6))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}