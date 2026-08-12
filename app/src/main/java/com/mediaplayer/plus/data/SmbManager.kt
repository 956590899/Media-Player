package com.mediaplayer.plus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Properties

// =====================================================================
// SMB 服务器配置
// =====================================================================
data class SmbServer(
    val id: String,
    val host: String,
    val share: String = "",
    val username: String = "",
    val password: String = "",
    val label: String = "",  // 显示名称
    val isGuest: Boolean = false  // 匿名/免密登录
) {
    val displayName: String get() = label.ifBlank { "$host/$share".trimEnd('/') }
    val smbRoot: String get() = "smb://$host/$share".trimEnd('/')
}

// =====================================================================
// SMB 收藏夹
// =====================================================================
data class SmbBookmark(
    val id: String,
    val serverId: String,   // 关联的服务器 ID
    val path: String,       // 完整 smb:// 路径
    val label: String       // 显示名称
)

// =====================================================================
// SMB 文件/文件夹条目
// =====================================================================
data class SmbEntry(
    val name: String,
    val path: String,        // 完整 smb:// 路径
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
) {
    val isMediaFile: Boolean get() {
        val ext = name.substringAfterLast(".").lowercase()
        return ext in MEDIA_EXTENSIONS
    }

    val isAudioFile: Boolean get() {
        val ext = name.substringAfterLast(".").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    val isVideoFile: Boolean get() {
        val ext = name.substringAfterLast(".").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    val isSubtitleFile: Boolean get() {
        val ext = name.substringAfterLast(".").lowercase()
        return ext in SUBTITLE_EXTENSIONS
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "ape", "opus", "aiff", "dsf", "dff")
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m3u8", "ts", "m2ts", "vob", "rmvb")
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
        val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS + SUBTITLE_EXTENSIONS
    }
}

// =====================================================================
// SMB 管理器
// =====================================================================
class SmbManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smb_prefs", Context.MODE_PRIVATE)

    init {
        // 注册 Bouncy Castle 提供者以支持 SMB NTLM 身份验证所需的 MD4 算法
        try {
            java.security.Security.removeProvider("BC")
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            Log.i(TAG, "Bouncy Castle provider registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bouncy Castle provider", e)
        }
    }

    companion object {
        private const val TAG = "SmbManager"
        private const val KEY_SERVERS = "smb_servers_json"
    }

    // ================= 服务器配置管理 =================

    fun getServers(): List<SmbServer> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SmbServer(
                    id = obj.getString("id"),
                    host = obj.getString("host"),
                    share = obj.optString("share", ""),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                    label = obj.optString("label", ""),
                    isGuest = obj.optBoolean("isGuest", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SMB servers", e)
            emptyList()
        }
    }

    fun saveServers(servers: List<SmbServer>) {
        val arr = org.json.JSONArray()
        servers.forEach { s ->
            val obj = org.json.JSONObject()
            obj.put("id", s.id)
            obj.put("host", s.host)
            obj.put("share", s.share)
            obj.put("username", s.username)
            obj.put("password", s.password)
            obj.put("label", s.label)
            obj.put("isGuest", s.isGuest)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun addServer(server: SmbServer) {
        val servers = getServers().toMutableList()
        servers.add(server)
        saveServers(servers)
    }

    fun removeServer(id: String) {
        val servers = getServers().filter { it.id != id }
        saveServers(servers)
    }

    // ================= 收藏夹管理 =================

    fun getBookmarks(): List<SmbBookmark> {
        val json = prefs.getString("smb_bookmarks", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SmbBookmark(
                    id = obj.getString("id"),
                    serverId = obj.getString("serverId"),
                    path = obj.getString("path"),
                    label = obj.getString("label")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bookmarks", e)
            emptyList()
        }
    }

    private fun saveBookmarks(bookmarks: List<SmbBookmark>) {
        val arr = org.json.JSONArray()
        bookmarks.forEach { b ->
            val obj = org.json.JSONObject()
            obj.put("id", b.id)
            obj.put("serverId", b.serverId)
            obj.put("path", b.path)
            obj.put("label", b.label)
            arr.put(obj)
        }
        prefs.edit().putString("smb_bookmarks", arr.toString()).apply()
    }

    fun addBookmark(serverId: String, path: String, label: String) {
        val bookmarks = getBookmarks().toMutableList()
        // 避免重复
        if (bookmarks.none { it.serverId == serverId && it.path == path }) {
            bookmarks.add(SmbBookmark(
                id = java.util.UUID.randomUUID().toString(),
                serverId = serverId,
                path = path,
                label = label
            ))
            saveBookmarks(bookmarks)
        }
    }

    fun removeBookmark(id: String) {
        saveBookmarks(getBookmarks().filter { it.id != id })
    }

    fun isBookmarked(serverId: String, path: String): Boolean {
        return getBookmarks().any { it.serverId == serverId && it.path == path }
    }

    fun removeBookmarkByPath(serverId: String, path: String) {
        saveBookmarks(getBookmarks().filter { !(it.serverId == serverId && it.path == path) })
    }

    // ================= SMB 连接与浏览 =================

    private fun createContext(server: SmbServer): CIFSContext {
        return createContextForStream(server)
    }

    // CIFSContext 缓存 — 按 serverId 复用，避免每次请求创建新连接导致 SMB 服务器连接数超限
    private val contextCache = java.util.concurrent.ConcurrentHashMap<String, CIFSContext>()

    fun createContextForStream(server: SmbServer): CIFSContext {
        // 缓存命中：复用已有的 context，避免创建新 transport 连接
        contextCache[server.id]?.let { return it }
        
        return synchronized(contextCache) {
            contextCache[server.id] ?: createContextForStreamInternal(server).also {
                contextCache[server.id] = it
            }
        }
    }
    
    private fun createContextForStreamInternal(server: SmbServer): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.connTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
            // 针对高比特率视频优化：增大传输缓冲区
            setProperty("jcifs.smb.client.bufferSize", "1048576") // 1MB
            setProperty("jcifs.smb.client.readSize", "1048576")   // 1MB
            setProperty("jcifs.smb.client.writeSize", "1048576")  // 1MB
            // 提高并发请求性能
            setProperty("jcifs.smb.client.maxBuffers", "64")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            if (server.isGuest) {
                // SMB1 匿名/免密访问配置
                setProperty("jcifs.smb.client.minVersion", "SMB1")
                setProperty("jcifs.smb.client.maxVersion", "SMB1")  // 强制 SMB1 避免协商到 SMB2+
                setProperty("jcifs.smb.client.useExtendedSecurity", "false")
                setProperty("jcifs.smb.client.disableSpnegoIntegrity", "true")
                setProperty("jcifs.smb.lmCompatibility", "0")  // NTLMv1，SMB1 匿名访问只能用这个
                setProperty("jcifs.smb.client.guestUsername", "GUEST")
                setProperty("jcifs.smb.client.guestPassword", "")
            } else {
                setProperty("jcifs.smb.client.minVersion", "SMB1")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
            }
        }
        val config = PropertyConfiguration(props)
        val baseCtx = BaseContext(config)

        return if (server.isGuest) {
            baseCtx.withCredentials(NtlmPasswordAuthenticator("GUEST", ""))
        } else if (server.username.isNotEmpty()) {
            baseCtx.withCredentials(
                NtlmPasswordAuthenticator(server.username, server.password)
            )
        } else {
            baseCtx.withCredentials(NtlmPasswordAuthenticator(null, null, null))
        }
    }

    suspend fun testConnection(server: SmbServer): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ctx = createContext(server)
            val root = SmbFile(server.smbRoot, ctx)
            root.list() // 尝试列出文件，验证连接
            Result.success("连接成功")
        } catch (e: Exception) {
            Log.e(TAG, "SMB connection failed: ${e.message}", e)
            Result.failure(Exception("连接失败: ${e.message}"))
        }
    }

    /**
     * 清除 CIFSContext 缓存，释放所有 SMB 连接
     */
    fun clearContextCache() {
        // jcifs CIFSContext 没有显式的 close()，清空缓存让 GC 回收即可
        contextCache.clear()
    }

    suspend fun listEntries(
        server: SmbServer,
        path: String = ""
    ): Result<List<SmbEntry>> = withContext(Dispatchers.IO) {
        try {
            val ctx = createContext(server)
            var fullPath = if (path.isEmpty()) server.smbRoot else path
            // SMB 目录路径必须以 '/' 结尾
            if (!fullPath.endsWith("/")) fullPath += "/"
            Log.d(TAG, "Listing SMB: $fullPath")
            val dir = SmbFile(fullPath, ctx)

            if (!dir.isDirectory) {
                return@withContext Result.failure(Exception("不是文件夹"))
            }

            val files = dir.listFiles()
                ?.filter { it.name != "." && it.name != ".." && !it.isHidden }
                ?.sortedWith(compareByDescending<SmbFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map { file ->
                    SmbEntry(
                        name = file.name.trimEnd('/'),
                        path = file.path,
                        isDirectory = file.isDirectory,
                        size = if (file.isFile) file.length() else 0,
                        lastModified = file.lastModified()
                    )
                } ?: emptyList()

            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "SMB list failed: ${e.message}", e)
            Result.failure(
                                if (e.message?.contains("password", ignoreCase = true) == true ||
                                    e.message?.contains("logon", ignoreCase = true) == true ||
                                    e.message?.contains("unknown user", ignoreCase = true) == true)
                                    Exception("此服务器需要用户名密码，请长按编辑添加凭据")
                                else
                                    Exception("浏览失败: ${e.message}")
                            )
        }
    }

    /**
     * 获取带认证信息的 smb:// URL，用于 MPV 直接播放
     */
    fun getSmbPlaybackUrl(server: SmbServer, filePath: String): String {
        val encodedUser = java.net.URLEncoder.encode(server.username, "UTF-8")
        val encodedPass = java.net.URLEncoder.encode(server.password, "UTF-8")
        val host = server.host.trimEnd('/')
        // filePath 可能是完整 smb:// URL，提取纯路径部分
        val path = filePath
            .removePrefix("smb://")
            .removePrefix("$host/")
            .removePrefix("$host")
            .trimStart('/')
        return if (server.username.isNotEmpty()) {
            "smb://$encodedUser:$encodedPass@$host/$path"
        } else {
            "smb://$host/$path"
        }
    }

    // ================= LAN 自动扫描 =================

    /**
     * 获取本机局域网 IP 地址列表
     */
    fun getLocalIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    // 只取 IPv4 局域网地址
                    if (host.contains(".") && !host.startsWith("127.")) {
                        result.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IPs", e)
        }
        return result
    }

    /**
     * 扫描局域网内的 SMB 服务器（并行探测，8 倍加速）
     * 返回发现的服务器列表（免密连接）
     */
    suspend fun scanLan(
        onProgress: suspend (String) -> Unit = {},
        onFound: suspend (SmbServer) -> Unit = {}
    ): List<SmbServer> = withContext(Dispatchers.IO) {
        val localIps = getLocalIpAddresses()
        val selfIp = localIps.firstOrNull() ?: ""

        val subnets = localIps.mapNotNull { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
        }.distinct()

        if (subnets.isEmpty()) {
            onProgress("未检测到局域网 IP，请检查网络连接")
            return@withContext emptyList()
        }

        Log.d(TAG, "Scanning subnets: $subnets, self=$selfIp")

        val allIps = mutableListOf<String>()
        for (subnet in subnets) {
            for (i in 1..254) {
                val ip = "$subnet.$i"
                if (ip != selfIp) allIps.add(ip)
            }
        }

        val total = allIps.size
        var scanned = 0
        val found = mutableListOf<SmbServer>()

        onProgress("扫描 $total 个 IP (${subnets.joinToString()}) ...")

        // 并行扫描，每批 32 个 IP
        allIps.chunked(32).forEach { batch ->
            val results = coroutineScope {
                batch.map { ip ->
                    async {
                        val ok = tryConnectPort(ip, 445, 800) || tryConnectPort(ip, 139, 800)
                        if (ok) {
                            var resolvedName = ip
                            try {
                                // Try to resolve hostname (NetBIOS or DNS)
                                val addr = InetAddress.getByName(ip)
                                val name = addr.hostName
                                if (name != ip) {
                                    resolvedName = name.removeSuffix(".lan").removeSuffix(".local")
                                } else {
                                    // Fallback: Try JCIFS to get the real SMB server name
                                    val dummyServer = SmbServer(java.util.UUID.randomUUID().toString(), ip, isGuest = true)
                                    val ctx = createContext(dummyServer)
                                    // Netbios name resolution
                                    try {
                                        val nbt = ctx.nameServiceClient.getByName(ip)
                                        resolvedName = (nbt.hostName ?: ip).removeSuffix(".lan").removeSuffix(".local")
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Name resolution failed for $ip")
                            }

                            val server = SmbServer(
                                id = java.util.UUID.randomUUID().toString(),
                                host = ip,
                                isGuest = true,
                                label = resolvedName
                            )
                            onFound(server)
                            listOf(server)
                        } else null
                    }
                }
            }.awaitAll().filterNotNull().flatten()

            found.addAll(results)
            scanned += batch.size
            onProgress("扫描中 ${scanned * 100 / total}% (${scanned}/$total) 发现 ${found.size} 个")
        }

        onProgress("扫描完成，发现 ${found.size} 个服务器")
        found
    }

    /**
     * 列出服务器上的所有共享（匿名访问）
     */
    suspend fun listShares(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val server = SmbServer(id = "_", host = host, isGuest = true)
            val ctx = createContext(server)
            val root = SmbFile("smb://$host/", ctx)
            root.listFiles()
                ?.filter { it.isDirectory && it.name != "." && it.name != ".." && !it.name.endsWith("$") }
                ?.map { it.name.trimEnd('/') }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list shares on $host: ${e.message}")
            emptyList()
        }
    }

    /**
     * 尝试连接指定 IP 的端口
     */
    private fun tryConnectPort(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}