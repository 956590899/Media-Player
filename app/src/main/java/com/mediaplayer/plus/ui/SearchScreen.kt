package com.mediaplayer.plus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.mediaplayer.plus.data.SmbBookmark
import com.mediaplayer.plus.data.SmbEntry
import com.mediaplayer.plus.data.SmbHttpProxy
import com.mediaplayer.plus.data.SmbManager
import com.mediaplayer.plus.data.SmbMediaItem
import com.mediaplayer.plus.data.SmbServer
import com.mediaplayer.plus.data.Song
import com.mediaplayer.plus.data.Video

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatSmbSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

@Composable
private fun smbDialogColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFF64B5F6),
    unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
    cursorColor = Color.White,
    focusedLabelColor = Color(0xFF64B5F6),
    unfocusedLabelColor = Color.Gray
)

// 仿真搜索页面
@Composable
fun SimpleSearchView(
    songs: List<Song> = emptyList(),
    videos: List<Video> = emptyList(),
    smbManager: SmbManager? = null,
    smbServers: List<SmbServer> = emptyList(),
    // 🌟 SMB 浏览状态（从 ViewModel 提升，跨 tab 切换保持）
    smbCurrentServer: SmbServer? = null,
    smbCurrentPath: String = "",
    smbEntries: List<SmbEntry> = emptyList(),
    isSmbLoading: Boolean = false,
    smbError: String? = null,
    hasSmbBrowseStack: Boolean = false,
    // 🌟 像素级滚动位置恢复（lambda 延迟读取，确保读取 ViewModel 最新值）
    smbScrollIndex: () -> Int = { 0 },
    smbScrollOffset: () -> Int = { 0 },
    // 🌟 搜索页进入计数器，每次进入搜索页时递增，强制 remember 重建 LazyListState
    searchEntryCount: Int = 0,
    onSaveSmbScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    onBrowseSmbServer: (SmbServer, String) -> Unit = { _, _ -> },
    onEnterSmbFolder: (SmbEntry) -> Unit = {},
    onGoBackSmb: () -> Unit = {},
    onCloseSmb: () -> Unit = {},
    onSongClick: (List<Song>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<Video>, Int) -> Unit = { _, _ -> },
    onPlaySmbFile: (isVideo: Boolean, playlist: List<SmbMediaItem>, playlistIndex: Int) -> Unit = { _, _, _ -> },
    onRefreshSmbServers: () -> Unit = {},
    onSmbScanClick: () -> Unit = {},
    isScanningSmb: Boolean = false,
    smbScanProgress: String = ""
) {
    var query by remember { mutableStateOf("") }
    // 🌟 像素级恢复：searchEntryCount 每次进入搜索页递增，强制 remember 重建 LazyListState
    // smbCurrentPath 在进入子文件夹时变化，重建 LazyListState 并从顶部开始
    // smbScrollIndex() / smbScrollOffset() 在组合时实时读取 ViewModel 最新保存值
    val listState = remember(searchEntryCount, smbCurrentPath) {
        LazyListState(
            firstVisibleItemIndex = smbScrollIndex(),
            firstVisibleItemScrollOffset = smbScrollOffset()
        )
    }
    // 🌟 持续保存滚动位置到 ViewModel
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            onSaveSmbScrollPosition(index, offset)
        }
    }

    val filteredSongs = remember(query, songs) {
        if (query.isBlank()) emptyList()
        else songs.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true) ||
            it.album.contains(query, ignoreCase = true)
        }
    }

    val filteredVideos = remember(query, videos) {
        if (query.isBlank()) emptyList()
        else videos.filter {
            it.title.contains(query, ignoreCase = true)
        }
    }

    // 🌟 SMB 状态已提升至 ViewModel，通过参数传入（跨 tab 切换保持）
    var showSmbAddDialog by remember { mutableStateOf(false) }
    var smbEditingServer by remember { mutableStateOf<SmbServer?>(null) }
    var smbBookmarks by remember { mutableStateOf(smbManager?.getBookmarks() ?: emptyList()) }
    // 收藏夹自定义名称对话框
    var showBookmarkNameDialog by remember { mutableStateOf(false) }
    var bookmarkPendingServer by remember { mutableStateOf<SmbServer?>(null) }
    var bookmarkPendingPath by remember { mutableStateOf("") }
    var bookmarkPendingLabel by remember { mutableStateOf("") }

    // 返回手势：进入 SMB 服务器/文件夹后，返回上级而非关闭菜单
    BackHandler(enabled = smbCurrentServer != null) {
        onGoBackSmb()
    }

    // 播放 SMB 文件
    fun playSmbEntry(entry: SmbEntry) {
        val server = smbCurrentServer ?: return
        // 基于当前文件夹内所有媒体文件生成播放列表
        val mediaFiles = smbEntries.filter { it.isMediaFile }
        val playlist = mediaFiles.map { f ->
            SmbMediaItem(
                serverId = server.id,
                smbPath = f.path,
                fileName = f.name,
                fileSize = f.size,
                isVideoFile = f.isVideoFile,
                host = server.host,
                share = server.share,
                isGuest = server.isGuest,
                username = server.username,
                password = server.password
            )
        }
        val index = mediaFiles.indexOf(entry).coerceAtLeast(0)
        onPlaySmbFile(entry.isVideoFile, playlist, index)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        PageHeader("搜索")
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索本地音乐或本地视频", color = Color.Gray) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.3f),
                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                cursorColor = Color.White
            ),
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (query.isBlank()) {
            // 无搜索关键词时：显示 SMB 局域网 + 搜索提示
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                // SMB 局域网区域
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    // 标题行：单独一行
                    Text(
                        "🌐 局域网 (SMB)",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 操作行：自动扫描 + 添加按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isScanningSmb) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF81C784),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                smbScanProgress.ifEmpty { "扫描中..." },
                                color = Color(0xFF81C784),
                                fontSize = 12.sp
                            )
                        } else {
                            TextButton(onClick = onSmbScanClick) {
                                Text("🔍 自动扫描", color = Color(0xFF81C784), fontSize = 13.sp)
                            }
                        }
                        TextButton(onClick = { smbEditingServer = null; showSmbAddDialog = true }) {
                            Text("＋ 添加", color = Color(0xFF64B5F6), fontSize = 13.sp)
                        }
                    }
                }

                // 移除原有的独立扫描进度 item
                // if (isScanningSmb) { ... } 会被移除，因为它现在整合进了 Header Row



                // 收藏夹
                val bookmarks = smbBookmarks
                if (bookmarks.isNotEmpty() && smbCurrentServer == null) {
                    item {
                        Text(
                            "⭐ 收藏夹",
                            color = Color(0xFFFFD54F).copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(bookmarks.size) { index ->
                        val bm = bookmarks[index]
                        val server = smbServers.find { it.id == bm.serverId }
                        MediaListRow(
                            title = bm.label,
                            subtitle = if (server != null) "smb://${server.host}${bm.path.removePrefix(server.smbRoot)}" else bm.path,
                            albumArtUrl = null,
                            durationMs = 0L,
                            icon = Icons.Filled.Star,
                            isCurrent = false,
                            onClick = {
                                server?.let { onBrowseSmbServer(it, bm.path) }
                            },
                            trailing = {
                                IconButton(onClick = {
                                    smbManager?.removeBookmark(bm.id)
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, "取消收藏", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                    item {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                if (smbCurrentServer != null) {
                    // SMB 浏览中
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasSmbBrowseStack) {
                                IconButton(onClick = { onGoBackSmb() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(
                                "${smbCurrentServer!!.displayName}${if (smbCurrentPath.isNotEmpty()) " / ${smbCurrentPath.trimEnd('/').substringAfterLast("/")}" else ""}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            // 收藏按钮
                            val isFav = smbCurrentServer != null && smbManager?.isBookmarked(
                                smbCurrentServer!!.id,
                                smbCurrentPath.ifEmpty { smbCurrentServer!!.smbRoot }
                            ) == true
                            IconButton(onClick = {
                                val srv = smbCurrentServer ?: return@IconButton
                                val p = smbCurrentPath.ifEmpty { srv.smbRoot }
                                val label = if (smbCurrentPath.isEmpty()) srv.displayName
                                    else smbCurrentPath.trimEnd('/').substringAfterLast("/").ifEmpty { srv.displayName }
                                if (isFav) {
                                    smbManager?.removeBookmarkByPath(srv.id, p)
                                    smbBookmarks = smbManager?.getBookmarks() ?: emptyList()
                                } else {
                                    bookmarkPendingServer = srv
                                    bookmarkPendingPath = p
                                    bookmarkPendingLabel = label
                                    showBookmarkNameDialog = true
                                }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    "收藏",
                                    tint = if (isFav) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { onCloseSmb() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, "关闭", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (isSmbLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (smbError != null) {
                        item {
                            Text(smbError!!, color = Color(0xFFEF5350), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    } else {
                        items(smbEntries.size) { index ->
                            val entry = smbEntries[index]
                            if (entry.isDirectory) {
                                MediaListRow(
                                    title = entry.name,
                                    subtitle = if (entry.lastModified > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(entry.lastModified)) else "",
                                    albumArtUrl = null,
                                    durationMs = 0L,
                                    icon = Icons.Filled.Folder,
                                    isCurrent = false,
                                    onClick = { onEnterSmbFolder(entry) }
                                )
                            } else if (entry.isMediaFile) {
                                MediaListRow(
                                    title = entry.name,
                                    subtitle = if (entry.size > 0) formatSmbSize(entry.size) else "",
                                    albumArtUrl = null,
                                    durationMs = 0L,
                                    icon = if (entry.isVideoFile) Icons.Filled.Videocam else Icons.Filled.MusicNote,
                                    isCurrent = false,
                                    onClick = { playSmbEntry(entry) }
                                )
                            }
                        }
                    }
                } else {
                    // SMB 服务器列表
                    if (smbServers.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "点击上方「＋ 添加服务器」添加局域网共享",
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(smbServers.size) { index ->
                            val server = smbServers[index]
                            MediaListRow(
                                title = server.displayName,
                                subtitle = "smb://${server.host}" + if (server.share.isNotEmpty()) "/${server.share}" else "",
                                albumArtUrl = null,
                                durationMs = 0L,
                                icon = Icons.Filled.Storage,
                                isCurrent = false,
                                onClick = { onBrowseSmbServer(server, "") },
                                trailing = {
                                    Row {
                                        IconButton(onClick = {
                                            smbEditingServer = server
                                            showSmbAddDialog = true
                                        }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Filled.Edit, "编辑", tint = Color(0xFF64B5F6).copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = {
                                            smbManager?.removeServer(server.id)
                                            onRefreshSmbServers()
                                        }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFEF5350).copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // 分隔线
                item {
                    Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        } else if (filteredSongs.isEmpty() && filteredVideos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("无匹配结果", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(bottom = 88.dp)
            ) {
                // 音频结果
                if (filteredSongs.isNotEmpty()) {
                    item {
                        Text(
                            "🎵 音乐",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(filteredSongs.size) { index ->
                        val song = filteredSongs[index]
                        MediaListRow(
                            title = song.title,
                            subtitle = "${song.artist} · ${song.album}",
                            albumArtUrl = song.albumArtUrl,
                            durationMs = song.duration,
                            isCurrent = false,
                            onClick = { onSongClick(filteredSongs, index) }
                        )
                    }
                }

                // 视频结果
                if (filteredVideos.isNotEmpty()) {
                    item {
                        Text(
                            "🎬 视频",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(filteredVideos.size) { index ->
                        val video = filteredVideos[index]
                        MediaListRow(
                            title = video.title,
                            subtitle = formatTime(video.duration),
                            albumArtUrl = video.uri.toString(),
                            durationMs = video.duration,
                            icon = Icons.Filled.Videocam,
                            isCurrent = false,
                            onClick = { onVideoClick(filteredVideos, index) }
                        )
                    }
                }
            }
        }
    }

    // SMB 对话框
    if (showSmbAddDialog) {
        SmbServerDialog(
            existingServer = smbEditingServer,
            onDismiss = { showSmbAddDialog = false; smbEditingServer = null },
            onSave = { server ->
                if (smbEditingServer != null) {
                    // 编辑模式：删除旧服务器，添加新服务器
                    smbManager?.removeServer(smbEditingServer!!.id)
                }
                smbManager?.addServer(server)
                onRefreshSmbServers()
                showSmbAddDialog = false
                smbEditingServer = null
            }
        )
    }

    // 收藏夹自定义名称对话框
    if (showBookmarkNameDialog) {
        var nameInput by remember { mutableStateOf(bookmarkPendingLabel) }
        AlertDialog(
            onDismissRequest = { showBookmarkNameDialog = false },
            title = { Text("收藏文件夹", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("名称", color = Color.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = smbDialogColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val srv = bookmarkPendingServer ?: return@TextButton
                    smbManager?.addBookmark(srv.id, bookmarkPendingPath, nameInput.ifBlank { bookmarkPendingLabel })
                    smbBookmarks = smbManager?.getBookmarks() ?: emptyList()
                    onRefreshSmbServers()
                    showBookmarkNameDialog = false
                }) { Text("收藏", color = Color(0xFFFFD54F)) }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkNameDialog = false }) { Text("取消", color = Color.White.copy(alpha = 0.5f)) }
            },
            containerColor = Color(0xFF1E1E2E),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// 添加 SMB 服务器对话框
@Composable
fun SmbServerDialog(
    onDismiss: () -> Unit,
    onSave: (SmbServer) -> Unit,
    existingServer: SmbServer? = null
) {
    var host by remember { mutableStateOf(existingServer?.host ?: "") }
    var share by remember { mutableStateOf(existingServer?.share ?: "") }
    var username by remember { mutableStateOf(existingServer?.username ?: "") }
    var password by remember { mutableStateOf(existingServer?.password ?: "") }
    var label by remember { mutableStateOf(existingServer?.label ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var isGuest by remember { mutableStateOf(existingServer?.isGuest ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A24),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text(existingServer?.let { "编辑服务器" } ?: "添加 SMB 服务器")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("显示名称 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = smbDialogColors()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址 *") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = smbDialogColors()
                )
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text("共享名 (可选)") },
                    placeholder = { Text("media") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = smbDialogColors()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = smbDialogColors()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = smbDialogColors()
                )
                // 匿名/免密登录开关
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isGuest,
                        onCheckedChange = { isGuest = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF64B5F6),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Text(
                        "匿名/免密登录 (Guest)",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (host.isNotBlank()) {
                        onSave(
                            SmbServer(
                                id = existingServer?.id ?: java.util.UUID.randomUUID().toString(),
                                host = host.trim(),
                                share = share.trim(),
                                username = username.trim(),
                                password = password,
                                label = label.trim(),
                                isGuest = isGuest
                            )
                        )
                    }
                },
                enabled = host.isNotBlank()
            ) {
                Text("保存", color = if (host.isNotBlank()) Color(0xFF64B5F6) else Color.Gray)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.Gray)
            }
        }
    )
}
