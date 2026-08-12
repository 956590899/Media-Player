@file:OptIn(ExperimentalComposeUiApi::class)

package com.mediaplayer.plus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaplayer.plus.data.MediaItem
import com.mediaplayer.plus.data.SmbEntry
import com.mediaplayer.plus.data.SmbServer
import com.mediaplayer.plus.data.Song
import com.mediaplayer.plus.data.Video
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class TvSection { Recent, Video, Music, Files, Settings }

enum class TvBrowserType {
    AllVideos,
    AllSongs,
    Artists,
    Albums,
    Folders,
    VideoFolders,
    LocalFiles,
    Smb
}

@Composable
fun TvHomeScreen(
    songs: List<Song>,
    videos: List<Video>,
    localFiles: List<PlayerViewModel.FileEntry>,
    recentlyPlayed: MediaItem?,
    smbServers: List<SmbServer>,
    smbBookmarks: List<com.mediaplayer.plus.data.SmbBookmark>,
    isScanningSmb: Boolean,
    smbScanProgress: String,
    currentDirPath: String,
    sectionOrder: List<TvSection>,
    activeBrowserType: TvBrowserType?,
    onActiveBrowserTypeChange: (TvBrowserType?) -> Unit,
    onSectionOrderChange: (List<TvSection>) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onVideoClick: (List<Video>, Int) -> Unit,
    onRecentlyPlayedClick: (MediaItem) -> Unit,
    onFileClick: (PlayerViewModel.FileEntry) -> Unit,
    onScanSmb: () -> Unit,
    onBrowseLocalDir: (String) -> Unit,
    onNavigateLocalUp: () -> Unit,
    onSettingsClick: () -> Unit,
    isLocalRoot: Boolean,
    onBrowseSmbServer: (SmbServer) -> Unit = {},
    smbEntries: List<SmbEntry> = emptyList(),
    currentSmbServer: SmbServer? = null,
    currentSmbPath: String = "",
    isSmbLoading: Boolean = false,
    onSmbNavigateUp: () -> Unit = {},
    onSmbEntryClick: (SmbEntry) -> Unit = {},
    onEnterSmbDirectory: (SmbEntry, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onNavigateSmbUpStack: () -> Unit = {},
    smbRestoreState: PlayerViewModel.DirectoryStackEntry? = null,
    isSmbBookmarked: Boolean = false,
    onToggleSmbBookmark: () -> Unit = {},
    onOpenPlaylist: () -> Unit = {},
    onBookmarkClick: (com.mediaplayer.plus.data.SmbBookmark) -> Unit = {},
    playlistCount: Int = 0,
    isPlaying: Boolean = false,
    showPlaylist: Boolean = false,
    onTogglePlaylist: () -> Unit = {},
    currentPlaylist: List<MediaItem> = emptyList(),
    playlistIndex: Int = 0,
    onPlayFromPlaylist: (Int) -> Unit = {},
    tvLayoutMode: PlayerViewModel.LayoutMode = PlayerViewModel.LayoutMode.GRID,
    onTvLayoutModeChange: (PlayerViewModel.LayoutMode) -> Unit = {},
    onEnterLocalDirectory: (PlayerViewModel.FileEntry, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onSaveLocalScrollPosition: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onSaveSmbScrollPosition: (Int, Int, Int) -> Unit = { _, _, _ -> },
    localRestoreState: PlayerViewModel.DirectoryStackEntry? = null,
) {
    var showSectionSortDialog by remember { mutableStateOf(false) }
    val scrollState = rememberLazyListState()
    val lastFocusedSection = remember { mutableStateOf<TvSection?>(null) }
    val sectionFocusRequesters = remember { mutableMapOf<TvSection, FocusRequester>() }
    fun getFocusRequester(section: TvSection): FocusRequester {
        return sectionFocusRequesters.getOrPut(section) { FocusRequester() }
    }

    // 返回主页时恢复焦点到对应分区
    LaunchedEffect(activeBrowserType) {
        if (activeBrowserType == null) {
            delay(50)
            lastFocusedSection.value?.let { section ->
                sectionFocusRequesters[section]?.requestFocus()
            }
        }
    }

    // 按遥控器返回键返回主页或上级目录
    BackHandler(enabled = currentSmbServer != null || activeBrowserType != null || showSectionSortDialog || showPlaylist) {
        if (showPlaylist) {
            onTogglePlaylist()
        } else if (showSectionSortDialog) {
            showSectionSortDialog = false
        } else if (currentSmbServer != null) {
            onNavigateSmbUpStack()
        } else if (activeBrowserType == TvBrowserType.LocalFiles && !isLocalRoot) {
            onNavigateLocalUp()
        } else {
            onActiveBrowserTypeChange(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (currentSmbServer != null) {
            // 🌟 独立 SMB 文件浏览器（全屏，不嵌套）
            TvSmbFileBrowserScreen(
                entries = smbEntries,
                isLoading = isSmbLoading,
                serverName = currentSmbServer.displayName,
                currentSmbPath = currentSmbPath,
                isBookmarked = isSmbBookmarked,
                onToggleBookmark = onToggleSmbBookmark,
                onEntryClick = { entry, scrollIdx, scrollOffset, focusedIdx ->
                    if (entry.isDirectory) {
                        onEnterSmbDirectory(entry, scrollIdx, scrollOffset, focusedIdx)
                    } else {
                        onSaveSmbScrollPosition(scrollIdx, scrollOffset, focusedIdx)
                        onSmbEntryClick(entry)
                    }
                },
                onBack = onSmbNavigateUp,
                onNavigateUp = onNavigateSmbUpStack,
                smbRestoreState = smbRestoreState,
                tvLayoutMode = tvLayoutMode,
                onTvLayoutModeChange = onTvLayoutModeChange
            )
        } else if (activeBrowserType != null) {
            // 🌟 独立文件/媒体浏览器组件（带记忆焦点与自动滚动定位功能）
            TvBrowserDetailScreen(
                browserType = activeBrowserType,
                songs = songs,
                videos = videos,
                localFiles = localFiles,
                recentlyPlayed = recentlyPlayed,
                smbServers = smbServers,
                isScanningSmb = isScanningSmb,
                smbScanProgress = smbScanProgress,
                currentDirPath = currentDirPath,
                onBack = { onActiveBrowserTypeChange(null) },
                onSongClick = onSongClick,
                onVideoClick = onVideoClick,
                onFileClick = onFileClick,
                onEnterDirectory = onEnterLocalDirectory,
                onSaveScrollPosition = onSaveLocalScrollPosition,
                onNavigateLocalUp = onNavigateLocalUp,
                localRestoreState = localRestoreState,
                onScanSmb = onScanSmb,
                onBrowseSmbServer = onBrowseSmbServer,
                tvLayoutMode = tvLayoutMode,
                onTvLayoutModeChange = onTvLayoutModeChange
            )
        } else {
            // 🌟 首页瀑布流
            LazyColumn(
                state = scrollState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Media Player",
                            color = Color(0xFF64B5F6),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                }

                sectionOrder.forEach { section ->
                    item {
                        TvWaterfallSection(
                            section = section,
                            songs = songs,
                            videos = videos,
                            localFiles = localFiles,
                            recentlyPlayed = recentlyPlayed,
                            smbServers = smbServers,
                            smbBookmarks = smbBookmarks,
                            onOpenBrowser = { type ->
                                onActiveBrowserTypeChange(type)
                                if (type == TvBrowserType.Smb) onScanSmb()
                                if (type == TvBrowserType.LocalFiles) onBrowseLocalDir(android.os.Environment.getExternalStorageDirectory().absolutePath)
                            },
                            onVideoClick = onVideoClick,
                            onRecentlyPlayedClick = onRecentlyPlayedClick,
                            onSettingsClick = onSettingsClick,
                            focusRequester = getFocusRequester(section),
                            onOpenPlaylist = onOpenPlaylist,
                            onBookmarkClick = onBookmarkClick,
                            playlistCount = playlistCount,
                            isPlaying = isPlaying,
                            onFocusGained = { section -> lastFocusedSection.value = section }
                        )
                    }
                }
            }
        }

        AnimatedVisibility(showSectionSortDialog, enter = fadeIn(), exit = fadeOut()) {
            TvSectionSortDialog(
                order = sectionOrder,
                onConfirm = {
                    onSectionOrderChange(it)
                    showSectionSortDialog = false
                },
                onDismiss = { showSectionSortDialog = false }
            )
        }

        AnimatedVisibility(showPlaylist, enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }), exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })) {
            TvPlaylistOverlay(
                playlist = currentPlaylist,
                currentIndex = playlistIndex,
                onPlayItem = { index ->
                    onPlayFromPlaylist(index)
                    onTogglePlaylist()
                },
                onClose = { onTogglePlaylist() }
            )
        }
    }
}

// 🌟 播放列表覆盖层
@Composable
fun TvPlaylistOverlay(
    playlist: List<MediaItem>,
    currentIndex: Int,
    onPlayItem: (Int) -> Unit,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onClose() }
        )
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp),
            color = Color(0xFF1A1A2E),
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "播放列表 (${playlist.size})",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A))
                    ) {
                        Text("关闭", color = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (playlist.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("播放列表为空", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(currentIndex) {
                        if (currentIndex in playlist.indices) {
                            listState.animateScrollToItem(currentIndex)
                        }
                    }

                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(playlist) { index, item ->
                            val isCurrent = index == currentIndex
                            val focusRequester = remember { FocusRequester() }

                            Surface(
                                onClick = { onPlayItem(index) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                color = if (isCurrent) Color(0xFF0D47A1) else Color(0xFF2A2A3A),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        "${index + 1}",
                                        color = if (isCurrent) Color(0xFF64B5F6) else Color.Gray,
                                        fontSize = 13.sp,
                                        modifier = Modifier.width(28.dp)
                                    )
                                    Text(
                                        item.title,
                                        color = if (isCurrent) Color.White else Color.LightGray,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
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
fun TvWaterfallSection(
    section: TvSection,
    songs: List<Song>,
    videos: List<Video>,
    localFiles: List<PlayerViewModel.FileEntry>,
    recentlyPlayed: MediaItem?,
    smbServers: List<SmbServer>,
    smbBookmarks: List<com.mediaplayer.plus.data.SmbBookmark>,
    onOpenBrowser: (TvBrowserType) -> Unit,
    onVideoClick: (List<Video>, Int) -> Unit,
    onRecentlyPlayedClick: (MediaItem) -> Unit,
    onSettingsClick: () -> Unit,
    focusRequester: FocusRequester,
    onOpenPlaylist: () -> Unit = {},
    onBookmarkClick: (com.mediaplayer.plus.data.SmbBookmark) -> Unit = {},
    playlistCount: Int = 0,
    isPlaying: Boolean = false,
    onFocusGained: (TvSection) -> Unit = {},
) {
    Column(modifier = Modifier.focusGroup()) {
        val (sectionIcon, sectionTitle) = when (section) {
            TvSection.Recent -> Icons.Default.History to "最近"
            TvSection.Video -> Icons.Default.Videocam to "视频"
            TvSection.Music -> Icons.Default.MusicNote to "音频"
            TvSection.Files -> Icons.Default.FolderOpen to "文件浏览器"
            TvSection.Settings -> Icons.Default.Settings to "设置"
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(sectionIcon, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(sectionTitle, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        when (section) {
            TvSection.Recent -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.QueueMusic,
                            title = "最近列表",
                            subtitle = if (playlistCount > 0) "${playlistCount} 首" else "播放列表为空",
                            cardColor = Color(0xFF0D47A1),
                            iconTint = Color(0xFF64B5F6),
                            onFocusGained = { onFocusGained(section) },
                        ) {
                            onOpenPlaylist()
                        }
                    }
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.PlayArrow,
                            title = "最近播放",
                            subtitle = if (isPlaying) "正在播放: ${recentlyPlayed?.title ?: ""}" else if (recentlyPlayed != null) recentlyPlayed.title else "暂无记录",
                            cardColor = Color(0xFF1A237E),
                            iconTint = Color(0xFF64B5F6),
                            onFocusGained = { onFocusGained(section) },
                        ) {
                            recentlyPlayed?.let { onRecentlyPlayedClick(it) }
                        }
                    }
                    items(smbBookmarks) { bookmark ->
                        TvWaterfallCard(
                            icon = Icons.Default.Folder,
                            title = bookmark.label,
                            subtitle = bookmark.path.removePrefix("smb://").substringBefore("/"),
                            cardColor = Color(0xFF1B5E20),
                            iconTint = Color(0xFF81C784),
                            onFocusGained = { onFocusGained(section) },
                        ) {
                            onBookmarkClick(bookmark)
                        }
                    }
                }
            }
            TvSection.Video -> {
                val videoFolderCount = videos.map { java.io.File(it.path).parentFile?.name ?: "根目录" }.distinct().size

                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.VideoLibrary,
                            title = "所有视频",
                            subtitle = "${videos.size} 个视频",
                            cardColor = Color(0xFFB71C1C),
                            iconTint = Color(0xFFE57373),
                            onFocusGained = { onFocusGained(section) },
                        ) {
                            onOpenBrowser(TvBrowserType.AllVideos)
                        }
                    }
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Folder,
                            title = "文件夹",
                            subtitle = "$videoFolderCount 个",
                            cardColor = Color(0xFF1A237E),
                            iconTint = Color(0xFFFFCA28),
                            onFocusGained = { onFocusGained(section) },
                        ) {
                            onOpenBrowser(TvBrowserType.VideoFolders)
                        }
                    }
                    items(minOf(videos.size, 15)) { index ->
                        TvVideoThumbnailCard(video = videos[index]) {
                            onVideoClick(videos, index)
                        }
                    }
                }
            }
            TvSection.Music -> {
                val artistCount = songs.map { it.artist }.filter { it.isNotBlank() && it != "Unknown" }.distinct().size
                val albumCount = songs.map { it.album }.filter { it.isNotBlank() }.distinct().size
                val folderCount = songs.map { java.io.File(it.path).parentFile?.name ?: "Root" }.distinct().size

                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.LibraryMusic,
                            title = "所有音频",
                            subtitle = "${songs.size} 首",
                            cardColor = Color(0xFF4A148C),
                            iconTint = Color(0xFFCE93D8),
                            onFocusGained = { onFocusGained(section) },
                        ) { onOpenBrowser(TvBrowserType.AllSongs) }
                    }
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Person,
                            title = "艺术家",
                            subtitle = "$artistCount 位",
                            cardColor = Color(0xFF880E4F),
                            iconTint = Color(0xFFFFAB91),
                            onFocusGained = { onFocusGained(section) },
                        ) { onOpenBrowser(TvBrowserType.Artists) }
                    }
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Album,
                            title = "专辑",
                            subtitle = "$albumCount 张",
                            cardColor = Color(0xFFE65100),
                            iconTint = Color(0xFFFFB74D),
                            onFocusGained = { onFocusGained(section) },
                        ) { onOpenBrowser(TvBrowserType.Albums) }
                    }
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Folder,
                            title = "文件夹",
                            subtitle = "$folderCount 个",
                            cardColor = Color(0xFF1A237E),
                            iconTint = Color(0xFFFFCA28),
                            onFocusGained = { onFocusGained(section) },
                        ) { onOpenBrowser(TvBrowserType.Folders) }
                    }
                }
            }
            TvSection.Files -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Dns,
                            title = "SMB 网络共享",
                            subtitle = if (smbServers.isNotEmpty()) "${smbServers.size} 台服务器" else "点击扫描共享",
                            cardColor = Color(0xFF004D40),
                            iconTint = Color(0xFF80CBC4),
                            onFocusGained = { onFocusGained(section) },
                        ) { onOpenBrowser(TvBrowserType.Smb) }
                    }
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Storage,
                            title = "本地文件",
                            subtitle = "${localFiles.size} 个条目",
                            cardColor = Color(0xFF263238),
                            iconTint = Color(0xFF90A4AE),
                            onFocusGained = { onFocusGained(section) },
                        ) { onOpenBrowser(TvBrowserType.LocalFiles) }
                    }
                }
            }
            TvSection.Settings -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        TvWaterfallCard(
                            icon = Icons.Default.Settings,
                            title = "设置",
                            subtitle = "应用设置、主页排序",
                            cardColor = Color(0xFF212121),
                            iconTint = Color(0xFFB0BEC5),
                            onFocusGained = { onFocusGained(section) },
                        ) { onSettingsClick() }
                    }
                }
            }
        }
    }
}

// 🌟 独立文件/媒体浏览器组件（动态控制 Header 可聚焦性，防止第一帧抢焦点）
@Composable
fun TvBrowserDetailScreen(
    browserType: TvBrowserType,
    songs: List<Song>,
    videos: List<Video>,
    localFiles: List<PlayerViewModel.FileEntry>,
    recentlyPlayed: MediaItem?,
    smbServers: List<SmbServer>,
    isScanningSmb: Boolean,
    smbScanProgress: String,
    currentDirPath: String,
    onBack: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onVideoClick: (List<Video>, Int) -> Unit,
    onFileClick: (PlayerViewModel.FileEntry) -> Unit,
    onEnterDirectory: (PlayerViewModel.FileEntry, scrollIdx: Int, scrollOffset: Int, focusedIdx: Int) -> Unit = { _, _, _, _ -> },
    onSaveScrollPosition: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onNavigateLocalUp: () -> Unit = {},
    localRestoreState: PlayerViewModel.DirectoryStackEntry? = null,
    onScanSmb: () -> Unit,
    onBrowseSmbServer: (SmbServer) -> Unit = {},
    tvLayoutMode: PlayerViewModel.LayoutMode = PlayerViewModel.LayoutMode.GRID,
    onTvLayoutModeChange: (PlayerViewModel.LayoutMode) -> Unit = {}
) {
    var selectedGroupKey by remember { mutableStateOf<String?>(null) }
    val isGridView = tvLayoutMode == PlayerViewModel.LayoutMode.GRID

    val restoreScrollIndex = localRestoreState?.scrollIndex ?: 0
    val restoreScrollOffset = localRestoreState?.scrollOffset ?: 0
    val restoreFocusIndex = localRestoreState?.focusedIndex ?: 0

    // 🌟 状态控制：在列表项目拿到焦点之前，Header 不接受焦点，促使 Compose 寻找第一项
    var isContentFocused by remember(browserType, currentDirPath, selectedGroupKey) { mutableStateOf(false) }

    val hasEntries = when (browserType) {
        TvBrowserType.AllVideos -> videos.isNotEmpty()
        TvBrowserType.AllSongs -> songs.isNotEmpty()
        TvBrowserType.LocalFiles -> localFiles.isNotEmpty()
        TvBrowserType.Smb -> smbServers.isNotEmpty()
        else -> true
    }
    val canHeaderFocus = isContentFocused || !hasEntries

    val videoSelectedIndex = remember(videos, recentlyPlayed) {
        videos.indexOfFirst { it.getIdentificationPath() == recentlyPlayed?.getIdentificationPath() }.coerceAtLeast(0)
    }
    val songSelectedIndex = remember(songs, recentlyPlayed) {
        songs.indexOfFirst { it.getIdentificationPath() == recentlyPlayed?.getIdentificationPath() }.coerceAtLeast(0)
    }
    val localFileSelectedIndex = remember(localFiles, recentlyPlayed) {
        localFiles.indexOfFirst { it.path == recentlyPlayed?.getIdentificationPath() }.coerceAtLeast(0)
    }

    val pageTitle = when (browserType) {
        TvBrowserType.AllVideos -> "所有视频 (${videos.size})"
        TvBrowserType.AllSongs -> "所有音频 (${songs.size})"
        TvBrowserType.Artists -> if (selectedGroupKey != null) "艺术家: $selectedGroupKey" else "艺术家分类"
        TvBrowserType.Albums -> if (selectedGroupKey != null) "专辑: $selectedGroupKey" else "专辑分类"
        TvBrowserType.Folders -> if (selectedGroupKey != null) "文件夹: $selectedGroupKey" else "文件夹分类"
        TvBrowserType.VideoFolders -> if (selectedGroupKey != null) "视频文件夹: $selectedGroupKey" else "视频文件夹分类"
        TvBrowserType.LocalFiles -> "本地文件 ($currentDirPath)"
        TvBrowserType.Smb -> "SMB 网络共享"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .focusGroup()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                // 🌟 拦截：列表未聚焦前，Header 暂不接受初始聚焦
                .focusProperties { canFocus = canHeaderFocus }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                var backFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        if (selectedGroupKey != null) {
                            selectedGroupKey = null
                        } else {
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                    modifier = Modifier
                        .onFocusChanged { backFocused = it.isFocused }
                        .then(if (backFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("返回", color = Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = pageTitle,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (browserType == TvBrowserType.Smb) {
                    var scanFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = onScanSmb,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004D40)),
                        modifier = Modifier
                            .onFocusChanged { scanFocused = it.isFocused }
                            .then(if (scanFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isScanningSmb) "扫描中..." else "扫描局域网", color = Color.White)
                    }
                }

                var layoutFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = { onTvLayoutModeChange(if (isGridView) PlayerViewModel.LayoutMode.LIST else PlayerViewModel.LayoutMode.GRID) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGridView) Color(0xFF64B5F6) else Color(0xFF2A2A3A)
                    ),
                    modifier = Modifier
                        .onFocusChanged { layoutFocused = it.isFocused }
                        .then(if (layoutFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Icon(
                        imageVector = if (isGridView) Icons.Default.Apps else Icons.Default.List,
                        contentDescription = null,
                        tint = if (isGridView) Color.Black else Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isGridView) "宫格模式" else "列表模式",
                        color = if (isGridView) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (browserType == TvBrowserType.Smb && isScanningSmb && smbScanProgress.isNotBlank()) {
            Text(smbScanProgress, color = Color(0xFF80CBC4), fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (browserType) {
                TvBrowserType.AllVideos -> {
                    TvMediaItemList(
                        items = videos.map { it.title to formatDuration(it.duration) },
                        icon = Icons.Default.Movie,
                        isGridView = isGridView,
                        selectedIndex = videoSelectedIndex,
                        onFocused = { isContentFocused = true },
                        onItemClick = { index -> onVideoClick(videos, index) }
                    )
                }
                TvBrowserType.AllSongs -> {
                    TvMediaItemList(
                        items = songs.map { it.title to "${it.artist} • ${it.album}" },
                        icon = Icons.Default.MusicNote,
                        isGridView = isGridView,
                        selectedIndex = songSelectedIndex,
                        onFocused = { isContentFocused = true },
                        onItemClick = { index -> onSongClick(songs, index) }
                    )
                }
                TvBrowserType.Artists, TvBrowserType.Albums, TvBrowserType.Folders, TvBrowserType.VideoFolders -> {
                    val groupedMap = remember(songs, videos, browserType) {
                        when (browserType) {
                            TvBrowserType.Artists -> songs.groupBy { it.artist.ifBlank { "未知艺术家" } }
                            TvBrowserType.Albums -> songs.groupBy { it.album.ifBlank { "未知专辑" } }
                            TvBrowserType.Folders -> songs.groupBy { java.io.File(it.path).parentFile?.name ?: "根目录" }
                            TvBrowserType.VideoFolders -> videos.groupBy { java.io.File(it.path).parentFile?.name ?: "根目录" }
                            else -> emptyMap()
                        }
                    }

                    if (selectedGroupKey == null) {
                        TvGroupList(
                            groups = groupedMap.map { (key, list) -> key to "${list.size} ${if (browserType == TvBrowserType.VideoFolders) "个视频" else "首歌曲"}" },
                            isGridView = isGridView,
                            onFocused = { isContentFocused = true },
                            onGroupClick = { key -> selectedGroupKey = key }
                        )
                    } else if (browserType == TvBrowserType.VideoFolders) {
                        val groupVideos = (groupedMap[selectedGroupKey] ?: emptyList<Any>()).filterIsInstance<Video>()
                        val groupSelectedIndex = remember(groupVideos, recentlyPlayed) {
                            groupVideos.indexOfFirst { it.getIdentificationPath() == recentlyPlayed?.getIdentificationPath() }.coerceAtLeast(0)
                        }
                        TvMediaItemList(
                            items = groupVideos.map { it.title to it.path },
                            icon = Icons.Default.Videocam,
                            isGridView = isGridView,
                            selectedIndex = groupSelectedIndex,
                            onFocused = { isContentFocused = true },
                            onItemClick = { index -> onVideoClick(groupVideos, index) }
                        )
                    } else {
                        val groupSongs = (groupedMap[selectedGroupKey] ?: emptyList<Any>()).filterIsInstance<Song>()
                        val groupSelectedIndex = remember(groupSongs, recentlyPlayed) {
                            groupSongs.indexOfFirst { it.getIdentificationPath() == recentlyPlayed?.getIdentificationPath() }.coerceAtLeast(0)
                        }
                        TvMediaItemList(
                            items = groupSongs.map { it.title to "${it.artist} • ${it.album}" },
                            icon = Icons.Default.MusicNote,
                            isGridView = isGridView,
                            selectedIndex = groupSelectedIndex,
                            onFocused = { isContentFocused = true },
                            onItemClick = { index -> onSongClick(groupSongs, index) }
                        )
                    }
                }
                TvBrowserType.LocalFiles -> {
                    TvFileListView(
                        entries = localFiles,
                        isGridView = isGridView,
                        currentDirPath = currentDirPath,
                        selectedIndex = localFileSelectedIndex,
                        restoreScrollIndex = restoreScrollIndex,
                        restoreScrollOffset = restoreScrollOffset,
                        restoreFocusIndex = restoreFocusIndex,
                        onFocused = { isContentFocused = true },
                        onEntryClick = { entry, scrollIdx, scrollOffset, focusedIdx ->
                            if (entry.isDirectory) {
                                onEnterDirectory(entry, scrollIdx, scrollOffset, focusedIdx)
                            } else {
                                onSaveScrollPosition(scrollIdx, scrollOffset, focusedIdx)
                                onFileClick(entry)
                            }
                        }
                    )
                }
                TvBrowserType.Smb -> {
                    if (smbServers.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (isScanningSmb) "正在搜索局域网中的 SMB 设备..." else "未发现 SMB 服务器，点击右上角【扫描局域网】开始搜索",
                                color = Color.Gray,
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        TvSmbServerList(
                            servers = smbServers,
                            isGridView = isGridView,
                            onFocused = { isContentFocused = true },
                            onServerClick = { server -> onBrowseSmbServer(server) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        val remMinutes = minutes % 60
        String.format("%d:%02d:%02d", hours, remMinutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

// 🌟 媒体条目列表
@Composable
fun TvMediaItemList(
    items: List<Pair<String, String>>,
    icon: ImageVector,
    isGridView: Boolean,
    selectedIndex: Int = 0,
    onFocused: () -> Unit = {},
    onItemClick: (Int) -> Unit
) {
    val initialIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)

    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    LaunchedEffect(initialIndex, items) {
        if (items.isNotEmpty() && initialIndex in items.indices) {
            delay(50)
            focusRequesters[initialIndex]?.requestFocus()
        }
    }

    if (isGridView) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(items) { index, (title, subtitle) ->
                var isFocused by remember { mutableStateOf(false) }
                val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                Surface(
                    onClick = { onItemClick(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { 
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused()
                        },
                    color = if (isFocused) Color(0xFF334155) else Color(0xFF1E1E28),
                    shape = RoundedCornerShape(12.dp),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                    tonalElevation = if (isFocused) 8.dp else 2.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                color = Color.Gray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { index, (title, subtitle) ->
                var isFocused by remember { mutableStateOf(false) }
                val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                Surface(
                    onClick = { onItemClick(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { 
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused()
                        },
                    color = if (isFocused) Color(0xFF334155) else Color(0xFF1E1E28),
                    shape = RoundedCornerShape(8.dp),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = Color(0xFF64B5F6))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvGroupList(
    groups: List<Pair<String, String>>,
    isGridView: Boolean,
    onFocused: () -> Unit = {},
    onGroupClick: (String) -> Unit
) {
    if (isGridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(groups) { (groupName, subtitle) ->
                var isFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onGroupClick(groupName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .onFocusChanged { 
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused()
                        },
                    color = if (isFocused) Color(0xFF1E293B) else Color(0xFF1E1E28),
                    shape = RoundedCornerShape(12.dp),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                    tonalElevation = if (isFocused) 8.dp else 2.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            groupName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            subtitle,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { (groupName, subtitle) ->
                var isFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onGroupClick(groupName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .onFocusChanged { 
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused()
                        },
                    color = if (isFocused) Color(0xFF1E293B) else Color(0xFF1E1E28),
                    shape = RoundedCornerShape(8.dp),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFCA28))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(groupName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// 🌟 本地文件树列表组件
@Composable
fun TvFileListView(
    entries: List<PlayerViewModel.FileEntry>,
    isGridView: Boolean,
    currentDirPath: String,
    selectedIndex: Int = 0,
    restoreScrollIndex: Int = 0,
    restoreScrollOffset: Int = 0,
    restoreFocusIndex: Int = 0,
    onFocused: () -> Unit = {},
    onEntryClick: (PlayerViewModel.FileEntry, scrollIdx: Int, scrollOffset: Int, focusedIdx: Int) -> Unit,
    onScrollIndexChanged: (Int) -> Unit = {}
) {
    val gridState = key(currentDirPath) {
        rememberLazyGridState(
            initialFirstVisibleItemIndex = restoreScrollIndex,
            initialFirstVisibleItemScrollOffset = restoreScrollOffset
        )
    }
    val listState = key(currentDirPath) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = restoreScrollIndex,
            initialFirstVisibleItemScrollOffset = restoreScrollOffset
        )
    }

    val focusRequesters = key(currentDirPath) { remember { mutableMapOf<Int, FocusRequester>() } }

    LaunchedEffect(restoreFocusIndex, entries, currentDirPath) {
        if (entries.isNotEmpty() && restoreFocusIndex in entries.indices) {
            delay(50)
            focusRequesters[restoreFocusIndex]?.requestFocus()
        }
    }

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("目录为空或没有匹配的文件", color = Color.Gray, fontSize = 16.sp)
        }
    } else {
        if (isGridView) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    var isFocused by remember { mutableStateOf(false) }
                    val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                    Surface(
                        onClick = {
                            val scrollIdx = gridState.firstVisibleItemIndex
                            val scrollOff = gridState.firstVisibleItemScrollOffset
                            onEntryClick(entry, scrollIdx, scrollOff, index)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { 
                                isFocused = it.isFocused
                                if (it.isFocused) onFocused()
                            },
                        color = if (isFocused) Color(0xFF334155) else Color(0xFF1E1E28),
                        shape = RoundedCornerShape(12.dp),
                        border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                        tonalElevation = if (isFocused) 8.dp else 2.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = if (entry.isDirectory) Color(0xFFFFCA28) else Color(0xFF64B5F6),
                                modifier = Modifier.size(38.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = entry.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    var isFocused by remember { mutableStateOf(false) }
                    val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                    Surface(
                        onClick = {
                            val scrollIdx = listState.firstVisibleItemIndex
                            val scrollOff = listState.firstVisibleItemScrollOffset
                            onEntryClick(entry, scrollIdx, scrollOff, index)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { 
                                isFocused = it.isFocused
                                if (it.isFocused) onFocused()
                            },
                        color = if (isFocused) Color(0xFF334155) else Color(0xFF1E1E28),
                        shape = RoundedCornerShape(8.dp),
                        border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = if (entry.isDirectory) Color(0xFFFFCA28) else Color(0xFF64B5F6)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = entry.name,
                                color = Color.White,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// 🌟 独立 SMB 文件浏览器
@Composable
fun TvSmbFileBrowserScreen(
    entries: List<SmbEntry>,
    isLoading: Boolean,
    serverName: String,
    currentSmbPath: String,
    isBookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    onEntryClick: (SmbEntry, scrollIdx: Int, scrollOffset: Int, focusedIdx: Int) -> Unit,
    onBack: () -> Unit,
    onNavigateUp: () -> Unit = {},
    onSaveScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    smbRestoreState: PlayerViewModel.DirectoryStackEntry? = null,
    tvLayoutMode: PlayerViewModel.LayoutMode = PlayerViewModel.LayoutMode.GRID,
    onTvLayoutModeChange: (PlayerViewModel.LayoutMode) -> Unit = {}
) {
    val isGridView = tvLayoutMode == PlayerViewModel.LayoutMode.GRID

    val restoreScrollIndex = smbRestoreState?.scrollIndex ?: 0
    val restoreScrollOffset = smbRestoreState?.scrollOffset ?: 0
    val restoreFocusIndex = smbRestoreState?.focusedIndex ?: 0

    // 🌟 状态控制：在 SMB 列表未拿到焦点前，Header 暂不接受初始焦点
    var isContentFocused by remember(currentSmbPath) { mutableStateOf(false) }
    val canHeaderFocus = isContentFocused || entries.isEmpty() || isLoading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .focusGroup()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                // 🌟 拦截初始焦点
                .focusProperties { canFocus = canHeaderFocus }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                var backFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                    modifier = Modifier
                        .onFocusChanged { backFocused = it.isFocused }
                        .then(if (backFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("返回", color = Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = serverName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            var favFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onToggleBookmark,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBookmarked) Color(0xFFE65100) else Color(0xFF2A2A3A)
                ),
                modifier = Modifier
                    .onFocusChanged { favFocused = it.isFocused }
                    .then(if (favFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarOutline,
                    contentDescription = null,
                    tint = if (isBookmarked) Color(0xFFFFB74D) else Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBookmarked) "已收藏" else "收藏",
                    color = if (isBookmarked) Color(0xFFFFB74D) else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            var layoutFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { onTvLayoutModeChange(if (isGridView) PlayerViewModel.LayoutMode.LIST else PlayerViewModel.LayoutMode.GRID) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGridView) Color(0xFF64B5F6) else Color(0xFF2A2A3A)
                ),
                modifier = Modifier
                    .onFocusChanged { layoutFocused = it.isFocused }
                    .then(if (layoutFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            ) {
                Icon(
                    imageVector = if (isGridView) Icons.Default.Apps else Icons.Default.List,
                    contentDescription = null,
                    tint = if (isGridView) Color.Black else Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isGridView) "宫格模式" else "列表模式",
                    color = if (isGridView) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", color = Color.Gray, fontSize = 16.sp)
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("目录为空", color = Color.Gray, fontSize = 16.sp)
                }
            } else {
                val focusRequesters = key(currentSmbPath) { remember { mutableMapOf<Int, FocusRequester>() } }
                val smbGridState = key(currentSmbPath) {
                    rememberLazyGridState(
                        initialFirstVisibleItemIndex = restoreScrollIndex,
                        initialFirstVisibleItemScrollOffset = restoreScrollOffset
                    )
                }
                val smbListState = key(currentSmbPath) {
                    rememberLazyListState(
                        initialFirstVisibleItemIndex = restoreScrollIndex,
                        initialFirstVisibleItemScrollOffset = restoreScrollOffset
                    )
                }

                LaunchedEffect(restoreFocusIndex, entries, currentSmbPath) {
                    if (entries.isNotEmpty() && restoreFocusIndex in entries.indices) {
                        delay(50)
                        focusRequesters[restoreFocusIndex]?.requestFocus()
                    }
                }

                if (isGridView) {
                    LazyVerticalGrid(
                        state = smbGridState,
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(entries) { index, entry ->
                            var isFocused by remember { mutableStateOf(false) }
                            val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                            Surface(
                                onClick = {
                                    val scrollIdx = smbGridState.firstVisibleItemIndex
                                    val scrollOff = smbGridState.firstVisibleItemScrollOffset
                                    onEntryClick(entry, scrollIdx, scrollOff, index)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { 
                                        isFocused = it.isFocused
                                        if (it.isFocused) isContentFocused = true
                                    },
                                color = if (isFocused) Color(0xFF334155) else Color(0xFF1E1E28),
                                shape = RoundedCornerShape(12.dp),
                                border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                                tonalElevation = if (isFocused) 8.dp else 2.dp
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) Color(0xFFFFCA28) else Color(0xFF64B5F6),
                                        modifier = Modifier.size(38.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        entry.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = smbListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(entries) { index, entry ->
                            var isFocused by remember { mutableStateOf(false) }
                            val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                            Surface(
                                onClick = {
                                    val scrollIdx = smbListState.firstVisibleItemIndex
                                    val scrollOff = smbListState.firstVisibleItemScrollOffset
                                    onEntryClick(entry, scrollIdx, scrollOff, index)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { 
                                        isFocused = it.isFocused
                                        if (it.isFocused) isContentFocused = true
                                    },
                                color = if (isFocused) Color(0xFF334155) else Color(0xFF1E1E28),
                                shape = RoundedCornerShape(8.dp),
                                border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) Color(0xFFFFCA28) else Color(0xFF64B5F6)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        entry.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 🌟 SMB 服务器列表组件
@Composable
fun TvSmbServerList(
    servers: List<SmbServer>,
    isGridView: Boolean,
    onFocused: () -> Unit = {},
    onServerClick: (SmbServer) -> Unit
) {
    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    LaunchedEffect(servers) {
        if (servers.isNotEmpty()) {
            delay(50)
            focusRequesters[0]?.requestFocus()
        }
    }

    if (isGridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(servers) { index, server ->
                var isFocused by remember { mutableStateOf(false) }
                val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                Surface(
                    onClick = { onServerClick(server) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { 
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused()
                        },
                    color = if (isFocused) Color(0xFF1E293B) else Color(0xFF1E1E28),
                    shape = RoundedCornerShape(12.dp),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                    tonalElevation = if (isFocused) 8.dp else 2.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = Color(0xFF80CBC4),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            server.displayName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            server.host,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(servers) { index, server ->
                var isFocused by remember { mutableStateOf(false) }
                val focusRequester = remember { focusRequesters.getOrPut(index) { FocusRequester() } }

                Surface(
                    onClick = { onServerClick(server) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { 
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused()
                        },
                    color = if (isFocused) Color(0xFF1E293B) else Color(0xFF1E1E28),
                    shape = RoundedCornerShape(8.dp),
                    border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = Color(0xFF80CBC4),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                server.displayName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                server.host,
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvWaterfallCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    cardColor: Color,
    iconTint: Color,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusGained: (FocusRequester) -> Unit = {},
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(120.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { 
                isFocused = it.isFocused
                if (it.isFocused) onFocusGained(focusRequester)
            },
        shape = RoundedCornerShape(12.dp),
        color = cardColor,
        border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
        tonalElevation = if (isFocused) 8.dp else 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon, contentDescription = null,
                tint = if (isFocused) Color.White else iconTint,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                title,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.95f),
                fontSize = 16.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = Color.Gray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun TvVideoThumbnailCard(video: Video, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(160.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2A2A2A),
            border = if (isFocused) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
            tonalElevation = if (isFocused) 6.dp else 2.dp
        ) {
            Box {
                AsyncImage(
                    model = video.uri.toString(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                )))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            video.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun TvSectionSortDialog(
    order: List<TvSection>,
    onConfirm: (List<TvSection>) -> Unit,
    onDismiss: () -> Unit
) {
    var sortOrder by remember { mutableStateOf(order.toMutableList()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(500.dp).height(480.dp),
            color = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(24.dp).focusGroup()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主页排序", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("上/下 键调整顺序，确认 保存", color = Color.Gray, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sortOrder) { index, section ->
                        val (icon, label) = section.sectionLabel()
                        val itemFocusRequester = remember { FocusRequester() }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .focusRequester(itemFocusRequester)
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                if (index > 0) {
                                                    val a = sortOrder[index]
                                                    sortOrder[index] = sortOrder[index - 1]
                                                    sortOrder[index - 1] = a
                                                }
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                if (index < sortOrder.size - 1) {
                                                    val a = sortOrder[index]
                                                    sortOrder[index] = sortOrder[index + 1]
                                                    sortOrder[index + 1] = a
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .clickable {},
                            color = Color(0xFF2A2A3A),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.weight(1f))
                                Text("${index + 1}", color = Color.Gray, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onConfirm(sortOrder.toList()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64B5F6))
                    ) {
                        Text("确认", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun TvSection.sectionLabel(): Pair<ImageVector, String> {
    return when (this) {
        TvSection.Recent -> Icons.Default.History to "最近"
        TvSection.Video -> Icons.Default.Videocam to "视频"
        TvSection.Music -> Icons.Default.MusicNote to "音频"
        TvSection.Files -> Icons.Default.FolderOpen to "文件浏览器"
        TvSection.Settings -> Icons.Default.Settings to "设置"
    }
}