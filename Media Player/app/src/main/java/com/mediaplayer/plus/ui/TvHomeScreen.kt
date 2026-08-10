package com.mediaplayer.plus.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaplayer.plus.data.MediaItem
import com.mediaplayer.plus.data.Song
import com.mediaplayer.plus.data.Video
import com.mediaplayer.plus.data.SmbMediaItem

enum class TvTab { Recent, Video, Music, Files, Settings }

@Composable
fun TvHomeScreen(
    songs: List<Song>,
    videos: List<Video>,
    localFiles: List<PlayerViewModel.FileEntry>,
    recentlyPlayed: MediaItem?,
    currentDirPath: String,
    onSongClick: (List<Song>, Int) -> Unit,
    onVideoClick: (List<Video>, Int) -> Unit,
    onRecentlyPlayedClick: (MediaItem) -> Unit,
    onFileClick: (PlayerViewModel.FileEntry) -> Unit,
    onFileBack: () -> Unit,
    isLocalRoot: Boolean,
    smbServers: List<com.mediaplayer.plus.data.SmbServer>,
    onSmbServerClick: (com.mediaplayer.plus.data.SmbServer, String?) -> Unit,
    onSmbScanClick: () -> Unit,
    isScanningSmb: Boolean = false,
    onSmbAddClick: (com.mediaplayer.plus.data.SmbServer) -> Unit,
    smbEntries: List<com.mediaplayer.plus.data.SmbEntry>,
    currentSmbServer: com.mediaplayer.plus.data.SmbServer?,
    currentSmbPath: String,
    smbBookmarks: List<com.mediaplayer.plus.data.SmbBookmark>,
    isSmbLoading: Boolean,
    onSmbEntryClick: (com.mediaplayer.plus.data.SmbEntry) -> Unit,
    onSmbBookmarkToggle: (com.mediaplayer.plus.data.SmbServer, String, String) -> Unit,
    onSmbBack: () -> Unit,
    tvMode: PlayerViewModel.TvMode,
    isRealTv: Boolean,
    onTvModeChange: (PlayerViewModel.TvMode) -> Unit,
    selectedTab: TvTab,
    onTabChange: (TvTab) -> Unit,
    layoutMode: PlayerViewModel.LayoutMode,
    onLayoutModeChange: (PlayerViewModel.LayoutMode) -> Unit,
    inSmbMode: Boolean,
    onInSmbModeChange: (Boolean) -> Unit
) {
    val sidebarFocusRequesters = remember { TvTab.entries.associateWith { FocusRequester() } }
    
    var showAddSmbDialog by remember { mutableStateOf(false) }
    var isSidebarFocused by remember { mutableStateOf(false) }

    // Handle back button on TV
    val isFilesTab = selectedTab == TvTab.Files
    val canGoBackFiles = isFilesTab && !isLocalRoot && !inSmbMode
    
    androidx.activity.compose.BackHandler(enabled = inSmbMode || showAddSmbDialog || canGoBackFiles) {
        if (showAddSmbDialog) {
            showAddSmbDialog = false
        } else if (inSmbMode) {
            if (currentSmbServer != null) {
                onSmbBack()
            } else {
                onInSmbModeChange(false)
            }
        } else if (canGoBackFiles) {
            onFileBack()
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        // Left Sidebar
        LazyColumn(
            modifier = Modifier
                .width(110.dp)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A))
                .onFocusChanged { isSidebarFocused = it.hasFocus },
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            item {
                TvSidebarItem(
                    icon = Icons.Default.History, 
                    label = "最近", 
                    isSelected = selectedTab == TvTab.Recent,
                    modifier = Modifier.focusRequester(sidebarFocusRequesters[TvTab.Recent]!!)
                ) { 
                    onTabChange(TvTab.Recent)
                }
            }
            item {
                TvSidebarItem(
                    icon = Icons.Default.Videocam, 
                    label = "视频", 
                    isSelected = selectedTab == TvTab.Video,
                    modifier = Modifier.focusRequester(sidebarFocusRequesters[TvTab.Video]!!)
                ) { 
                    onTabChange(TvTab.Video)
                }
            }
            item {
                TvSidebarItem(
                    icon = Icons.Default.MusicNote, 
                    label = "音乐", 
                    isSelected = selectedTab == TvTab.Music,
                    modifier = Modifier.focusRequester(sidebarFocusRequesters[TvTab.Music]!!)
                ) { 
                    onTabChange(TvTab.Music)
                }
            }
            item {
                TvSidebarItem(
                    icon = Icons.Default.Folder, 
                    label = "文件", 
                    isSelected = selectedTab == TvTab.Files,
                    modifier = Modifier.focusRequester(sidebarFocusRequesters[TvTab.Files]!!)
                ) { 
                    onTabChange(TvTab.Files)
                }
            }
            
            item { Spacer(modifier = Modifier.height(40.dp)) }
            
            item {
                TvSidebarItem(
                    icon = Icons.Default.Settings, 
                    label = "设置", 
                    isSelected = selectedTab == TvTab.Settings,
                    modifier = Modifier.focusRequester(sidebarFocusRequesters[TvTab.Settings]!!)
                ) { 
                    onTabChange(TvTab.Settings)
                    onInSmbModeChange(false)
                }
            }
        }

        // Right Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp)
                .focusProperties {
                    // 🌟 核心修复：当从右侧向左移动时，强制指向当前选中的 Tab
                    left = sidebarFocusRequesters[selectedTab]!!
                }
        ) {
            Column {
                when (selectedTab) {
                    TvTab.Recent -> {
                        val hasHistory = recentlyPlayed != null
                        val hasBookmarks = smbBookmarks.isNotEmpty()
                        
                        val firstItemFocusRequester = remember { FocusRequester() }
                        LaunchedEffect(recentlyPlayed, smbBookmarks.size) {
                            if ((hasHistory || hasBookmarks) && !isSidebarFocused) {
                                kotlinx.coroutines.delay(10)
                                try {
                                    firstItemFocusRequester.requestFocus()
                                } catch (e: Exception) {}
                            }
                        }

                        if (hasHistory || hasBookmarks) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 100.dp)
                            ) {
                                if (hasHistory) {
                                    item {
                                        Text("最近播放", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        TvMediaItemCard(
                                            item = recentlyPlayed!!, 
                                            isGrid = layoutMode == PlayerViewModel.LayoutMode.GRID,
                                            modifier = Modifier.focusRequester(firstItemFocusRequester)
                                        ) {
                                            onRecentlyPlayedClick(recentlyPlayed)
                                        }
                                    }
                                }
                                
                                if (hasBookmarks) {
                                    item {
                                        Text("我的收藏 (SMB)", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    val chunkedBookmarks = smbBookmarks.chunked(if (layoutMode == PlayerViewModel.LayoutMode.GRID) 6 else 1)
                                    
                                    itemsIndexed(chunkedBookmarks) { rowIndex, row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            row.forEachIndexed { colIndex, bookmark ->
                                                TvSmbBookmarkCard(
                                                    bookmark = bookmark, 
                                                    isGrid = layoutMode == PlayerViewModel.LayoutMode.GRID,
                                                    modifier = if (!hasHistory && rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                                                ) {
                                                    val server = smbServers.find { it.id == bookmark.serverId }
                                                    if (server != null) {
                                                        onTabChange(TvTab.Files)
                                                        onInSmbModeChange(true)
                                                        onSmbServerClick(server, bookmark.path)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize().focusable(), contentAlignment = Alignment.Center) {
                                Text("暂无播放记录或收藏", color = Color.Gray)
                            }
                        }
                    }
                    TvTab.Video -> TvMediaGrid(
                        items = videos, 
                        title = "所有视频", 
                        layoutMode = layoutMode,
                        onLayoutModeToggle = onLayoutModeChange,
                        onItemClick = { index -> onVideoClick(videos, index) },
                        shouldAutoFocus = !isSidebarFocused
                    )
                    TvTab.Music -> TvMediaGrid(
                        items = songs, 
                        title = "所有音乐", 
                        layoutMode = layoutMode,
                        onLayoutModeToggle = onLayoutModeChange,
                        onItemClick = { index -> onSongClick(songs, index) },
                        shouldAutoFocus = !isSidebarFocused
                    )
                    TvTab.Files -> {
                        if (inSmbMode) {
                            if (currentSmbServer != null) {
                                TvSmbEntryList(
                                    entries = smbEntries,
                                    currentPath = currentSmbPath,
                                    currentServer = currentSmbServer,
                                    isLoading = isSmbLoading,
                                    isBookmarked = smbBookmarks.any { it.serverId == currentSmbServer.id && it.path == currentSmbPath },
                                    layoutMode = layoutMode,
                                    onLayoutModeToggle = onLayoutModeChange,
                                    onEntryClick = onSmbEntryClick,
                                    onBookmarkToggle = { onSmbBookmarkToggle(currentSmbServer, currentSmbPath, currentSmbServer.displayName + "/" + currentSmbPath.substringAfterLast("/")) },
                                    shouldAutoFocus = !isSidebarFocused
                                )
                            } else {
                                TvSmbServerList(
                                    servers = smbServers,
                                    onServerClick = onSmbServerClick,
                                    onScanClick = onSmbScanClick,
                                    onAddClick = { showAddSmbDialog = true },
                                    isScanning = isScanningSmb,
                                    shouldAutoFocus = !isSidebarFocused
                                )
                            }
                        } else {
                            TvFileBrowser(
                                files = localFiles,
                                currentPath = currentDirPath,
                                layoutMode = layoutMode,
                                onLayoutModeToggle = onLayoutModeChange,
                                onFileClick = { file ->
                                    if (file.path == "::smb::") onInSmbModeChange(true)
                                    else onFileClick(file)
                                },
                                shouldAutoFocus = !isSidebarFocused
                            )
                        }
                    }
                    TvTab.Settings -> TvSettingsView(tvMode, isRealTv, onTvModeChange)
                }
            }

            if (showAddSmbDialog) {
                TvSmbAddDialog(
                    onDismiss = { showAddSmbDialog = false },
                    onConfirm = { 
                        onSmbAddClick(it)
                        showAddSmbDialog = false
                    }
                )
            }
        }
    }
}

@Composable
fun TvSmbEntryList(
    entries: List<com.mediaplayer.plus.data.SmbEntry>,
    currentPath: String,
    currentServer: com.mediaplayer.plus.data.SmbServer,
    isLoading: Boolean,
    isBookmarked: Boolean,
    layoutMode: PlayerViewModel.LayoutMode,
    onLayoutModeToggle: (PlayerViewModel.LayoutMode) -> Unit,
    onEntryClick: (com.mediaplayer.plus.data.SmbEntry) -> Unit,
    onBookmarkToggle: () -> Unit,
    shouldAutoFocus: Boolean = true
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    // 🌟 核心优化：路径变化时，通过极短延迟立即捕获焦点，减少闪烁感
    LaunchedEffect(currentPath, entries.size) {
        if (entries.isNotEmpty() && shouldAutoFocus) {
            // 使用极小延迟，只要新列表一上屏就立即夺回焦点
            kotlinx.coroutines.delay(10)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("网络文件浏览", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(currentPath, color = Color.Gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Bookmark Toggle
                IconButton(onClick = onBookmarkToggle) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isBookmarked) Color(0xFFE57373) else Color.White.copy(alpha = 0.7f)
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))

                // Layout Toggle Button
                IconButton(
                    onClick = { 
                        onLayoutModeToggle(
                            if (layoutMode == PlayerViewModel.LayoutMode.GRID) PlayerViewModel.LayoutMode.LIST 
                            else PlayerViewModel.LayoutMode.GRID
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (layoutMode == PlayerViewModel.LayoutMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = "切换布局",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

    val loadingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            kotlinx.coroutines.delay(100)
            try { loadingFocusRequester.requestFocus() } catch (e: Exception) {}
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(loadingFocusRequester)
                .focusable(), 
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF64B5F6))
        }
    } else {
            if (layoutMode == PlayerViewModel.LayoutMode.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    gridItemsIndexed(entries) { index, entry ->
                        TvSmbEntryCard(
                            entry = entry, 
                            isGrid = true,
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                        ) { onEntryClick(entry) }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(entries) { index, entry ->
                        TvSmbEntryCard(
                            entry = entry, 
                            isGrid = false,
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                        ) { onEntryClick(entry) }
                    }
                }
            }
        }
    }
}

@Composable
fun TvSmbEntryCard(entry: com.mediaplayer.plus.data.SmbEntry, isGrid: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    if (isGrid) {
        Column(
            modifier = modifier
                .width(100.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable { onClick() }
                .background(if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.1f) else Color.Transparent)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDirectory) Color(0xFFFFCA28) else Color.Gray,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = entry.name,
                color = if (isFocused) Color.White else Color.Gray,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .onFocusChanged { isFocused = it.isFocused },
            color = if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color.Transparent,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (entry.isDirectory) Color(0xFFFFCA28) else Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = entry.name,
                    color = if (isFocused) Color.White else Color.LightGray,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TvSmbBookmarkCard(bookmark: com.mediaplayer.plus.data.SmbBookmark, isGrid: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    if (isGrid) {
        Column(
            modifier = modifier
                .width(100.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable { onClick() }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1E28),
                border = if (isFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF64B5F6)) else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(48.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = bookmark.label,
                color = if (isFocused) Color.White else Color.Gray,
                fontSize = 11.sp,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(64.dp).onFocusChanged { isFocused = it.isFocused },
            color = if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color(0xFF1E1E28),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(bookmark.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(bookmark.path, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun TvSmbAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (com.mediaplayer.plus.data.SmbServer) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(500.dp),
            color = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("添加网络共享", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))

                TvInputField(label = "服务器地址 (IP)", value = host, onValueChange = { host = it })
                TvInputField(label = "共享文件夹名", value = share, onValueChange = { share = it })
                TvInputField(label = "用户名 (可选)", value = user, onValueChange = { user = it })
                TvInputField(label = "密码 (可选)", value = pass, onValueChange = { pass = it }, isPassword = true)
                TvInputField(label = "显示名称", value = label, onValueChange = { label = it })

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            if (host.isNotBlank()) {
                                onConfirm(
                                    com.mediaplayer.plus.data.SmbServer(
                                        id = java.util.UUID.randomUUID().toString(),
                                        host = host,
                                        share = share,
                                        username = user,
                                        password = pass,
                                        label = label
                                    )
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64B5F6))
                    ) {
                        Text("确认添加", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun TvInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.1f),
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color(0xFF64B5F6),
                unfocusedIndicatorColor = Color.DarkGray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )
    }
}

@Composable
fun TvSettingsView(
    currentMode: PlayerViewModel.TvMode,
    isRealTv: Boolean,
    onModeChange: (PlayerViewModel.TvMode) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("系统设置", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(24.dp))
        
        if (!isRealTv) {
            Text("显示模式", color = Color(0xFF64B5F6), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            val options = listOf(
                PlayerViewModel.TvMode.AUTO to "自动检测 (默认)",
                PlayerViewModel.TvMode.ON to "强制 TV 模式 (横屏)",
                PlayerViewModel.TvMode.OFF to "强制手机模式 (竖屏)"
            )

            options.forEach { (mode, label) ->
                TvSettingsOption(
                    label = label,
                    isSelected = currentMode == mode,
                    onClick = { onModeChange(mode) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前已在电视设备上运行", color = Color.Gray)
            }
        }
    }
}

@Composable
fun TvSettingsOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { isFocused = it.isFocused },
        color = if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color(0xFF1E1E28),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = if (isFocused) Color.White else Color.Gray,
                fontSize = 18.sp
            )
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF64B5F6))
            }
        }
    }
}

@Composable
fun TvSidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 🌟 核心优化：移动到选项上自动进入对应页，增加 400ms 防抖
    // 只有当侧边栏真的拥有焦点，且选中的不是当前项时才触发
    LaunchedEffect(isFocused) {
        if (isFocused && !isSelected) {
            kotlinx.coroutines.delay(400)
            if (isFocused) {
                onClick()
            }
        }
    }

    val backgroundColor by animateColorAsState(
        if (isFocused || isSelected) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color.Transparent,
        label = "bg"
    )
    val contentColor = if (isFocused || isSelected) Color(0xFF64B5F6) else Color.Gray

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .background(backgroundColor)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TvSmbServerList(
    servers: List<com.mediaplayer.plus.data.SmbServer>,
    onServerClick: (com.mediaplayer.plus.data.SmbServer, String?) -> Unit,
    onScanClick: () -> Unit,
    onAddClick: () -> Unit,
    isScanning: Boolean = false,
    shouldAutoFocus: Boolean = true
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val scanButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (shouldAutoFocus && servers.isEmpty()) {
            kotlinx.coroutines.delay(100)
            try { scanButtonFocusRequester.requestFocus() } catch (e: Exception) {}
        }
    }

    LaunchedEffect(servers.size) {
        if (servers.isNotEmpty() && shouldAutoFocus) {
            kotlinx.coroutines.delay(200)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网络共享服务器", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (isScanning) {
                    Spacer(modifier = Modifier.width(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF64B5F6), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在搜寻设备...", color = Color(0xFF64B5F6), fontSize = 14.sp)
                }
            }
            
            Row {
                var isAddFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onAddClick,
                    enabled = !isScanning,
                    modifier = Modifier.onFocusChanged { isAddFocused = it.isFocused },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAddFocused) Color.White else Color(0xFF81C784),
                        contentColor = Color.Black
                    ),
                    border = if (isAddFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("手动添加", fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                var isScanFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onScanClick,
                    enabled = !isScanning,
                    modifier = Modifier
                        .focusRequester(scanButtonFocusRequester)
                        .onFocusChanged { isScanFocused = it.isFocused },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanFocused) Color.White else Color(0xFF64B5F6),
                        contentColor = Color.Black
                    ),
                    border = if (isScanFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF64B5F6)) else null
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isScanning) "扫描中..." else "扫描局域网", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        if (servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().focusable(), contentAlignment = Alignment.Center) {
                Text("未发现服务器，请在手机端添加", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                gridItemsIndexed(servers) { index, server ->
                    TvServerCard(
                        server = server,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                    ) { onServerClick(server, null) }
                }
            }
        }
    }
}

@Composable
fun TvServerCard(server: com.mediaplayer.plus.data.SmbServer, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(200.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .background(if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.1f) else Color(0xFF1E1E28))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(server.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(server.host, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
fun TvFileBrowser(
    files: List<PlayerViewModel.FileEntry>,
    currentPath: String,
    layoutMode: PlayerViewModel.LayoutMode,
    onLayoutModeToggle: (PlayerViewModel.LayoutMode) -> Unit,
    onFileClick: (PlayerViewModel.FileEntry) -> Unit,
    shouldAutoFocus: Boolean = true
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    // 🌟 核心优化：进入新文件夹后，立即尝试捕获焦点
    LaunchedEffect(currentPath, files.size) {
        if (files.isNotEmpty() && shouldAutoFocus) {
            kotlinx.coroutines.delay(10)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("文件浏览器", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(currentPath, color = Color.Gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Layout Toggle Button
            IconButton(
                onClick = { 
                    onLayoutModeToggle(
                        if (layoutMode == PlayerViewModel.LayoutMode.GRID) PlayerViewModel.LayoutMode.LIST 
                        else PlayerViewModel.LayoutMode.GRID
                    )
                }
            ) {
                Icon(
                    imageVector = if (layoutMode == PlayerViewModel.LayoutMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    contentDescription = "切换布局",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (layoutMode == PlayerViewModel.LayoutMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                contentPadding = PaddingValues(bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItemsIndexed(files) { index, file ->
                    TvFileItemCard(
                        file = file, 
                        isGrid = true,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                    ) { onFileClick(file) }
                }
            }
        } else {
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                coroutineScope.launch {
                                    val target = (listState.firstVisibleItemIndex - 10).coerceAtLeast(0)
                                    listState.animateScrollToItem(target)
                                }
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionRight -> {
                                coroutineScope.launch {
                                    val target = (listState.firstVisibleItemIndex + 10).coerceAtMost(files.size - 1)
                                    listState.animateScrollToItem(target)
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                }
            ) {
                itemsIndexed(files) { index, file ->
                    TvFileItemCard(
                        file = file, 
                        isGrid = false,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                    ) { onFileClick(file) }
                }
            }
        }
    }
}

@Composable
fun TvFileItemCard(file: PlayerViewModel.FileEntry, isGrid: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    if (isGrid) {
        Column(
            modifier = modifier
                .width(100.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable { onClick() }
                .background(if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.1f) else Color.Transparent)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = when {
                    file.path == "::smb::" -> Icons.Default.Dns
                    file.isDirectory -> Icons.Default.Folder
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    file.path == "::smb::" -> Color(0xFF64B5F6)
                    file.isDirectory -> Color(0xFFFFCA28)
                    else -> Color.Gray
                },
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = file.name,
                color = if (isFocused) Color.White else Color.Gray,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .onFocusChanged { isFocused = it.isFocused },
            color = if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color.Transparent,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        file.path == "::smb::" -> Icons.Default.Dns
                        file.isDirectory -> Icons.Default.Folder
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = when {
                        file.path == "::smb::" -> Color(0xFF64B5F6)
                        file.isDirectory -> Color(0xFFFFCA28)
                        else -> Color.Gray
                    },
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = file.name,
                    color = if (isFocused) Color.White else Color.LightGray,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun <T : MediaItem> TvMediaGrid(
    items: List<T>,
    title: String,
    layoutMode: PlayerViewModel.LayoutMode,
    onLayoutModeToggle: (PlayerViewModel.LayoutMode) -> Unit,
    onItemClick: (Int) -> Unit,
    shouldAutoFocus: Boolean = true
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(title, items.size) {
        if (items.isNotEmpty() && shouldAutoFocus) {
            kotlinx.coroutines.delay(10)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            
            // Layout Toggle Button
            IconButton(
                onClick = { 
                    onLayoutModeToggle(
                        if (layoutMode == PlayerViewModel.LayoutMode.GRID) PlayerViewModel.LayoutMode.LIST 
                        else PlayerViewModel.LayoutMode.GRID
                    )
                }
            ) {
                Icon(
                    imageVector = if (layoutMode == PlayerViewModel.LayoutMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    contentDescription = "切换布局",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (layoutMode == PlayerViewModel.LayoutMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp), // Reduced to 100.dp as requested
                contentPadding = PaddingValues(bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItemsIndexed(items) { index, item ->
                    TvMediaItemCard(
                        item = item, 
                        isGrid = true,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                    ) { onItemClick(index) }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    TvMediaItemCard(
                        item = item, 
                        isGrid = false,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                    ) { onItemClick(index) }
                }
            }
        }
    }
}

@Composable
fun TvMediaItemCard(item: MediaItem, isGrid: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    val artUrl = when (item) {
        is Song -> item.albumArtUrl
        is Video -> item.uri.toString() // Load thumbnail from video URI
        is SmbMediaItem -> null // SMB might need special handling
        else -> null
    }

    if (isGrid) {
        Column(
            modifier = modifier
                .width(100.dp) // Fixed width 100.dp
                .onFocusChanged { isFocused = it.isFocused }
                .clickable { onClick() }
                .padding(2.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2A2A2A),
                border = if (isFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                tonalElevation = if (isFocused) 6.dp else 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (artUrl != null) {
                        coil.compose.AsyncImage(
                            model = artUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (item is Video) Icons.Default.PlayCircle else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    
                    if (item is Video) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))))
                        )
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.title,
                color = if (isFocused) Color.White else Color.Gray,
                fontSize = 11.sp, // Smaller font for 100.dp
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        // List Mode
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(72.dp)
                .onFocusChanged { isFocused = it.isFocused },
            color = if (isFocused) Color(0xFF64B5F6).copy(alpha = 0.2f) else Color.Transparent,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.DarkGray
                ) {
                    if (artUrl != null) {
                        coil.compose.AsyncImage(
                            model = artUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (item is Video) Icons.Default.PlayCircle else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title, 
                        color = if (isFocused) Color.White else Color.LightGray, 
                        fontSize = 18.sp, 
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item is Song && item.artist.isNotBlank() && item.artist != "Unknown") {
                        Text(item.artist, color = Color.Gray, fontSize = 14.sp)
                    }
                }
                if (isFocused) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF64B5F6))
                }
            }
        }
    }
}

@Composable
fun TvNetworkPlaceholder(onSmbClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Dns, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(100.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("局域网共享 (SMB)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("播放 NAS 或电脑上的文件", color = Color.Gray, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onSmbClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64B5F6))
        ) {
            Text("立即连接", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
