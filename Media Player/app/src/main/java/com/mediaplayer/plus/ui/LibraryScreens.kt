package com.mediaplayer.plus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.mediaplayer.plus.data.Song
import com.mediaplayer.plus.data.Video
import kotlinx.coroutines.launch
import java.io.File

// =====================================================================
// 📑 分页筛选选项
// =====================================================================
enum class MusicFilterOption(val label: String) {
    ALL("全部文件"),
    ARTIST("歌手"),
    ALBUM("专辑"),
    FOLDER("文件夹")
}

enum class VideoFilterOption(val label: String) {
    ALL("全部文件"),
    FOLDER("文件夹")
}

// =====================================================================
// 🔀 排序选项
// =====================================================================
enum class MusicSortOption(val label: String) {
    ARTIST("按歌手"),
    TITLE("按歌名"),
    ALBUM("按专辑")
}

enum class VideoSortOption(val label: String) {
    TITLE("按文件名"),
    DURATION("按时长"),
    SIZE("按大小")
}

// =====================================================================
// 📄 页面顶部居中标题
// =====================================================================
@Composable
fun PageHeader(title: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 28.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    )
}

// =====================================================================
// 📑 分页筛选标签栏（水平可滚动，含扫描按钮 + 排序按钮）
// =====================================================================
@Composable
fun <T> FilterTabRow(
    options: Array<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelProvider: (T) -> String,
    onScanClick: () -> Unit,
    onSortClick: () -> Unit,
    onSortMenu: (@Composable BoxScope.() -> Unit) = {}
) where T : Enum<*> {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                border = if (isSelected) null
                else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                modifier = Modifier.clickable { onSelect(option) }
            ) {
                Text(
                    text = labelProvider(option),
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        IconButton(
            onClick = onScanClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = "扫描设置",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 排序按钮 + 下拉菜单锚点
        Box {
            IconButton(
                onClick = onSortClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "排序",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
            onSortMenu()
        }
    }
}

// =====================================================================
// 📜 PowerAmp 风格右侧快速滚动条
// =====================================================================
@Composable
fun FastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    var containerHeight by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // 内容是否超出可视区域
    val totalItems = listState.layoutInfo.totalItemsCount
    val visibleItems = listState.layoutInfo.visibleItemsInfo.size
    val isOverflowing = totalItems > visibleItems

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .background(Color.Transparent)
            .padding(vertical = 8.dp)
            .onSizeChanged { containerHeight = it.height.toFloat() }
            .then(
                if (isOverflowing) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val downEvent = awaitPointerEvent()
                                val downChange = downEvent.changes.firstOrNull() ?: continue
                                if (!downChange.pressed) continue
                                downChange.consume()

                                val total = listState.layoutInfo.totalItemsCount
                                if (total <= 0 || containerHeight <= 0f) continue

                                // 拖拽中：手指位置直接映射到列表位置（绝对定位，跟手）
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    change.consume()

                                    val fraction = (change.position.y / containerHeight).coerceIn(0f, 1f)
                                    val targetIndex = (fraction * (total - 1)).toInt()
                                    coroutineScope.launch {
                                        listState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (isOverflowing && containerHeight > 0f && totalItems > 0) {
            val firstVisibleIndex = listState.firstVisibleItemIndex

            val thumbHeightFraction = visibleItems.toFloat() / totalItems.toFloat()
            val thumbHeight = (containerHeight * thumbHeightFraction).coerceAtLeast(56f)
            val maxThumbTop = containerHeight - thumbHeight
            val maxScrollIndex = (totalItems - visibleItems).coerceAtLeast(1)
            val thumbTop = maxThumbTop * (firstVisibleIndex.toFloat() / maxScrollIndex)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackWidth = 5.dp.toPx()
                val trackX = size.width - trackWidth  // 贴右边缘绘制

                // 轨道（半透明细线）
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(trackX, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(2.5.dp.toPx())
                )

                // 滑块（当前位置指示器）
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(trackX, thumbTop),
                    size = Size(trackWidth, thumbHeight),
                    cornerRadius = CornerRadius(2.5.dp.toPx())
                )
            }
        }
    }
}

// =====================================================================
// 🎵 音乐媒体库
// =====================================================================
@Composable
fun MusicLibraryScreen(
    songs: List<Song>,
    currentTitle: String = "",
    currentArtist: String = "",
    scanAllAudio: Boolean = true,
    scanFoldersAudio: List<String> = emptyList(),
    onSongClick: (List<Song>, Int) -> Unit,
    onSetScanAllAudio: (Boolean) -> Unit = {},
    onAddScanFolderAudio: (String) -> Unit = {},
    onRemoveScanFolderAudio: (String) -> Unit = {},
    initialFilterType: String = "all",
    initialFilterValue: String = "",
    onFilterChange: (filterType: String, filterValue: String) -> Unit = { _, _ -> }
) {
    var filterOption by remember { mutableStateOf(
        MusicFilterOption.entries.find { it.name.lowercase() == initialFilterType.lowercase() } ?: MusicFilterOption.ALL
    ) }
    var sortOption by remember { mutableStateOf(MusicSortOption.ARTIST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }

    // 二级导航：进入歌手/专辑/文件夹后的子页面
    var subTitle by remember { mutableStateOf<String?>(null) }
    var subSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    // 手势返回：子页面时返回上一页，主页时交给外层关闭菜单
    androidx.activity.compose.BackHandler(enabled = subTitle != null) {
        subTitle = null
        subSongs = emptyList()
    }

    // 自动定位到上次播放的分页（仅首次渲染时生效）
    LaunchedEffect(initialFilterType, initialFilterValue) {
        if (initialFilterType != "all" && initialFilterValue.isNotEmpty()) {
            filterOption = MusicFilterOption.entries.find { it.name.lowercase() == initialFilterType.lowercase() } ?: MusicFilterOption.ALL
            // 对于文件夹/歌手/专辑，自动进入子页面
            when (initialFilterType.lowercase()) {
                "folder" -> {
                    subTitle = initialFilterValue
                    subSongs = songs.filter { java.io.File(it.path).parentFile?.name == initialFilterValue }
                }
                "artist" -> {
                    subTitle = initialFilterValue
                    subSongs = songs.filter { it.artist == initialFilterValue }
                }
                "album" -> {
                    subTitle = initialFilterValue
                    subSongs = songs.filter { it.album == initialFilterValue }
                }
            }
        }
    }

    // 按选择的排序方式排序全部歌曲
    val sortedSongs = remember(songs, sortOption) {
        when (sortOption) {
            MusicSortOption.ARTIST -> songs.sortedBy { it.artist.lowercase() }
            MusicSortOption.TITLE -> songs.sortedBy { it.title.lowercase() }
            MusicSortOption.ALBUM -> songs.sortedBy { it.album.lowercase() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题行：返回按钮 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (subTitle != null) {
                IconButton(
                    onClick = {
                        subTitle = null
                        onFilterChange("all", "")
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = if (subTitle != null) subTitle!! else "音乐库",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (subTitle != null) 36.dp else 0.dp)
            )
        }

        if (subTitle == null) {
            // ===== 主页面：分页标签 + 扫描 + 排序（同一行水平滚动）=====
            FilterTabRow(
                options = MusicFilterOption.entries.toTypedArray(),
                selected = filterOption,
                onSelect = {
                    filterOption = it
                    if (it == MusicFilterOption.ALL) onFilterChange("all", "")
                },
                labelProvider = { it.label },
                onScanClick = { showScanDialog = true },
                onSortClick = { showSortMenu = true },
                onSortMenu = {
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        MusicSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        color = if (option == sortOption) Color(0xFF64B5F6) else Color.White
                                    )
                                },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            )

            if (sortedSongs.isEmpty()) {
                EmptyState("未找到音乐")
            } else {
                when (filterOption) {
                    MusicFilterOption.ALL -> {
                        SongListView(
                            songs = sortedSongs,
                            currentTitle = currentTitle,
                            currentArtist = currentArtist,
                            onSongClick = onSongClick
                        )
                    }
                    MusicFilterOption.ARTIST -> {
                        ArtistListView(
                            songs = sortedSongs,
                            onArtistClick = { artist ->
                                subTitle = artist
                                subSongs = sortedSongs.filter { it.artist == artist }
                                onFilterChange("artist", artist)
                            }
                        )
                    }
                    MusicFilterOption.ALBUM -> {
                        AlbumListView(
                            songs = sortedSongs,
                            onAlbumClick = { album ->
                                subTitle = album
                                subSongs = sortedSongs.filter { it.album == album }
                                onFilterChange("album", album)
                            }
                        )
                    }
                    MusicFilterOption.FOLDER -> {
                        FolderListView(
                            songs = sortedSongs,
                            onFolderClick = { folder ->
                                subTitle = folder
                                subSongs = sortedSongs.filter { getParentFolderName(it.path) == folder }
                                onFilterChange("folder", folder)
                            }
                        )
                    }
                }
            }
        } else {
            // ===== 子页面：显示过滤后的歌曲列表 =====
            if (subSongs.isEmpty()) {
                EmptyState("未找到歌曲")
            } else {
                SongListView(
                    songs = subSongs,
                    currentTitle = currentTitle,
                    currentArtist = currentArtist,
                    onSongClick = onSongClick
                )
            }
        }
    }

    // 扫描文件夹对话框
    if (showScanDialog) {
        ScanFolderDialog(
            title = "音频扫描设置",
            scanAll = scanAllAudio,
            folders = scanFoldersAudio,
            onScanAllChange = onSetScanAllAudio,
            onAddFolder = onAddScanFolderAudio,
            onRemoveFolder = onRemoveScanFolderAudio,
            onDismiss = { showScanDialog = false }
        )
    }
}

// =====================================================================
// 🎬 视频媒体库
// =====================================================================
@Composable
fun VideoLibraryScreen(
    videos: List<Video>,
    currentTitle: String = "",
    scanAllVideo: Boolean = true,
    scanFoldersVideo: List<String> = emptyList(),
    onVideoClick: (List<Video>, Int) -> Unit,
    onSetScanAllVideo: (Boolean) -> Unit = {},
    onAddScanFolderVideo: (String) -> Unit = {},
    onRemoveScanFolderVideo: (String) -> Unit = {}
) {
    var filterOption by remember { mutableStateOf(VideoFilterOption.ALL) }
    var sortOption by remember { mutableStateOf(VideoSortOption.TITLE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }

    // 二级导航
    var subTitle by remember { mutableStateOf<String?>(null) }
    var subVideos by remember { mutableStateOf<List<Video>>(emptyList()) }

    // 手势返回：子页面时返回上一页，主页时交给外层关闭菜单
    androidx.activity.compose.BackHandler(enabled = subTitle != null) {
        subTitle = null
        subVideos = emptyList()
    }

    // 按选择的排序方式排序全部视频
    val sortedVideos = remember(videos, sortOption) {
        when (sortOption) {
            VideoSortOption.TITLE -> videos.sortedBy { it.title.lowercase() }
            VideoSortOption.DURATION -> videos.sortedByDescending { it.duration }
            VideoSortOption.SIZE -> videos.sortedByDescending { it.size }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题行：返回按钮 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (subTitle != null) {
                IconButton(
                    onClick = { subTitle = null },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = if (subTitle != null) subTitle!! else "视频库",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (subTitle != null) 36.dp else 0.dp)
            )
        }

        if (subTitle == null) {
            // ===== 主页面：分页标签 + 扫描 + 排序（同一行水平滚动）=====
            FilterTabRow(
                options = VideoFilterOption.entries.toTypedArray(),
                selected = filterOption,
                onSelect = { filterOption = it },
                labelProvider = { it.label },
                onScanClick = { showScanDialog = true },
                onSortClick = { showSortMenu = true },
                onSortMenu = {
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        VideoSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        color = if (option == sortOption) Color(0xFF64B5F6) else Color.White
                                    )
                                },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            )

            if (sortedVideos.isEmpty()) {
                EmptyState("未找到视频")
            } else {
                when (filterOption) {
                    VideoFilterOption.ALL -> {
                        VideoListView(
                            videos = sortedVideos,
                            currentTitle = currentTitle,
                            onVideoClick = onVideoClick
                        )
                    }
                    VideoFilterOption.FOLDER -> {
                        VideoFolderListView(
                            videos = sortedVideos,
                            onFolderClick = { folder ->
                                subTitle = folder
                                subVideos = sortedVideos.filter { getParentFolderName(it.path) == folder }
                            }
                        )
                    }
                }
            }
        } else {
            // ===== 子页面：显示过滤后的视频列表 =====
            if (subVideos.isEmpty()) {
                EmptyState("未找到视频")
            } else {
                VideoListView(
                    videos = subVideos,
                    currentTitle = currentTitle,
                    onVideoClick = onVideoClick
                )
            }
        }
    }

    // 扫描文件夹对话框
    if (showScanDialog) {
        ScanFolderDialog(
            title = "视频扫描设置",
            scanAll = scanAllVideo,
            folders = scanFoldersVideo,
            onScanAllChange = onSetScanAllVideo,
            onAddFolder = onAddScanFolderVideo,
            onRemoveFolder = onRemoveScanFolderVideo,
            onDismiss = { showScanDialog = false }
        )
    }
}

// =====================================================================
// 🎵 歌曲列表视图（复用）
// =====================================================================
@Composable
private fun SongListView(
    songs: List<Song>,
    currentTitle: String,
    currentArtist: String,
    onSongClick: (List<Song>, Int) -> Unit
) {
    val listState = rememberLazyListState()

    val currentIndex = remember(songs, currentTitle, currentArtist) {
        songs.indexOfFirst { it.title == currentTitle && it.artist == currentArtist }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.scrollToItem(currentIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 4.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    MediaListRow(
                        title = song.title,
                        subtitle = if (song.artist.isBlank() || song.artist == "Unknown") "" else song.artist,
                        albumArtUrl = song.albumArtUrl,
                        durationMs = song.duration,
                        isCurrent = index == currentIndex,
                        onClick = { onSongClick(songs, index) }
                    )
                }
            }

            FastScrollbar(
                listState = listState,
                modifier = Modifier.fillMaxHeight().padding(bottom = 70.dp)
            )
        }
    }
}

// =====================================================================
// 🎬 视频列表视图（复用）
// =====================================================================
@Composable
private fun VideoListView(
    videos: List<Video>,
    currentTitle: String,
    onVideoClick: (List<Video>, Int) -> Unit
) {
    val listState = rememberLazyListState()

    val currentIndex = remember(videos, currentTitle) {
        videos.indexOfFirst { it.title == currentTitle }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.scrollToItem(currentIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 4.dp)
            ) {
                itemsIndexed(videos) { index, video ->
                    MediaListRow(
                        title = video.title,
                        subtitle = formatDuration(video.duration),
                        albumArtUrl = video.uri.toString(),
                        durationMs = video.duration,
                        icon = Icons.Default.Videocam,
                        isCurrent = index == currentIndex,
                        onClick = { onVideoClick(videos, index) }
                    )
                }
            }

            FastScrollbar(
                listState = listState,
                modifier = Modifier.fillMaxHeight().padding(bottom = 70.dp)
            )
        }
    }
}

// =====================================================================
// 👤 歌手列表视图
// =====================================================================
@Composable
private fun ArtistListView(
    songs: List<Song>,
    onArtistClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    // 按歌手分组，统计歌曲数
    val artistGroups = remember(songs) {
        songs.groupBy { it.artist }
            .map { (artist, songs) -> artist to songs.size }
            .sortedBy { it.first.lowercase() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 4.dp)
            ) {
                items(artistGroups) { (artist, count) ->
                    GroupListRow(
                        title = artist,
                        subtitle = "$count 首歌曲",
                        onClick = { onArtistClick(artist) }
                    )
                }
            }

            FastScrollbar(
                listState = listState,
                modifier = Modifier.fillMaxHeight().padding(bottom = 70.dp)
            )
        }
    }
}

// =====================================================================
// 💿 专辑列表视图
// =====================================================================
@Composable
private fun AlbumListView(
    songs: List<Song>,
    onAlbumClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    // 按专辑分组，取第一个歌曲的专辑图作为封面
    val albumGroups = remember(songs) {
        songs.groupBy { it.album }
            .map { (album, songs) -> Triple(album, songs.size, songs.first().albumArtUrl) }
            .sortedBy { it.first.lowercase() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 4.dp)
            ) {
                items(albumGroups) { (album, count, artUrl) ->
                    GroupListRow(
                        title = album,
                        subtitle = "$count 首歌曲",
                        albumArtUrl = artUrl,
                        onClick = { onAlbumClick(album) }
                    )
                }
            }

            FastScrollbar(
                listState = listState,
                modifier = Modifier.fillMaxHeight().padding(bottom = 70.dp)
            )
        }
    }
}

// =====================================================================
// 📁 文件夹列表视图（音频）
// =====================================================================
@Composable
private fun FolderListView(
    songs: List<Song>,
    onFolderClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    val folderGroups = remember(songs) {
        songs.groupBy { getParentFolderName(it.path) }
            .map { (folder, songs) -> folder to songs.size }
            .sortedBy { it.first.lowercase() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 4.dp)
            ) {
                items(folderGroups) { (folder, count) ->
                    GroupListRow(
                        title = folder,
                        subtitle = "$count 首歌曲",
                        onClick = { onFolderClick(folder) }
                    )
                }
            }

            FastScrollbar(
                listState = listState,
                modifier = Modifier.fillMaxHeight().padding(bottom = 70.dp)
            )
        }
    }
}

// =====================================================================
// 📁 文件夹列表视图（视频）
// =====================================================================
@Composable
private fun VideoFolderListView(
    videos: List<Video>,
    onFolderClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    val folderGroups = remember(videos) {
        videos.groupBy { getParentFolderName(it.path) }
            .map { (folder, videos) -> folder to videos.size }
            .sortedBy { it.first.lowercase() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 4.dp)
            ) {
                items(folderGroups) { (folder, count) ->
                    GroupListRow(
                        title = folder,
                        subtitle = "$count 个视频",
                        onClick = { onFolderClick(folder) }
                    )
                }
            }

            FastScrollbar(
                listState = listState,
                modifier = Modifier.fillMaxHeight().padding(bottom = 70.dp)
            )
        }
    }
}

// =====================================================================
// 📋 分组列表行（歌手/专辑/文件夹通用）
// =====================================================================
@Composable
private fun GroupListRow(
    title: String,
    subtitle: String,
    albumArtUrl: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面图或占位图标
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (albumArtUrl != null) {
                AsyncImage(
                    model = albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.DarkGray.copy(alpha = 0.3f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MusicNote,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.LightGray.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =====================================================================
// 🎵 媒体列表行
// =====================================================================
@Composable
fun MediaListRow(
    title: String,
    subtitle: String,
    albumArtUrl: String?,
    durationMs: Long,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.MusicNote,
    thumbnailPath: String? = null,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val displayImage = thumbnailPath ?: albumArtUrl

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art or fallback icon
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (displayImage != null) {
                AsyncImage(
                    model = displayImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.DarkGray.copy(alpha = 0.3f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title & artist
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = Color.LightGray.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Duration on right
        if (durationMs > 0) {
            Text(
                text = formatDuration(durationMs),
                color = Color.LightGray.copy(alpha = 0.5f),
                fontSize = 13.sp
            )
        }
        // Trailing content (e.g., delete button)
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Color.Gray, fontSize = 18.sp)
    }
}

// =====================================================================
// � 扫描文件夹设置对话框
// =====================================================================
@Composable
fun ScanFolderDialog(
    title: String,
    scanAll: Boolean,
    folders: List<String>,
    onScanAllChange: (Boolean) -> Unit,
    onAddFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // SAF 文件夹选择器
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = treeUriToPath(context, uri)
            if (path != null) {
                onAddFolder(path)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White, fontSize = 18.sp)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Column {
                // 扫描全部开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("扫描全部文件", color = Color.White, fontSize = 15.sp)
                    Switch(
                        checked = scanAll,
                        onCheckedChange = onScanAllChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF64B5F6)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!scanAll) {
                    // 指定文件夹列表
                    Text(
                        "指定扫描文件夹:",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (folders.isEmpty()) {
                        Text(
                            "尚未添加文件夹，请点击下方按钮添加",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 240.dp)
                        ) {
                            items(folders.size) { index ->
                                val folder = folders[index]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder,
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onRemoveFolder(folder) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除",
                                            tint = Color(0xFFEF5350).copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 添加文件夹按钮
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White.copy(alpha = 0.7f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, Color.White.copy(alpha = 0.2f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加文件夹", fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = Color(0xFF1E1E28),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

// =====================================================================
// � 工具函数
// =====================================================================
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 从文件路径中提取父文件夹名称
 * 例如: /storage/emulated/0/Music/Artist/Song.mp3 -> "Artist"
 */
private fun getParentFolderName(path: String): String {
    if (path.isBlank()) return "未知文件夹"
    val parent = File(path).parentFile?.name ?: return "根目录"
    return parent.ifBlank { "根目录" }
}

/**
 * 将 SAF 文档树 URI 转换为文件系统路径
 * 例如: content://com.android.externalstorage.documents/tree/primary%3AMusic
 *       -> /storage/emulated/0/Music
 */
private fun treeUriToPath(context: android.content.Context, uri: android.net.Uri): String? {
    try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":", limit = 2)
        if (split.size < 2) return null

        val type = split[0]
        val path = split[1]

        return when {
            type.equals("primary", ignoreCase = true) -> {
                "/storage/emulated/0/$path"
            }
            type.startsWith("home", ignoreCase = true) -> {
                // Some devices use "home:folder" for primary storage
                "/storage/emulated/0/$path"
            }
            else -> {
                // SD card or external storage: XXXX-XXXX
                "/storage/$type/$path"
            }
        }
    } catch (e: Exception) {
        return null
    }
}