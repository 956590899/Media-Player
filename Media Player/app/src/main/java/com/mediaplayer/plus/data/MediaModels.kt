package com.mediaplayer.plus.data

import android.net.Uri

sealed class MediaItem {
    abstract val id: Long
    abstract val title: String
    abstract val uri: Uri
    abstract val path: String
    abstract val duration: Long
    
    open fun getPlaybackUrl(proxy: SmbHttpProxy?): String = path
    open fun getIdentificationPath(): String = path
    open fun isVideo(): Boolean = this is Video
}

data class Song(
    override val id: Long,
    override val title: String,
    override val uri: Uri,
    override val path: String,
    override val duration: Long,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtUrl: String? = null,
    val albumArtBytes: ByteArray? = null
) : MediaItem() {
    override fun isVideo(): Boolean = false
}

data class Video(
    override val id: Long,
    override val title: String,
    override val uri: Uri,
    override val path: String,
    override val duration: Long,
    val size: Long
) : MediaItem() {
    override fun isVideo(): Boolean = true
}

/**
 * SMB 网络文件播放项
 */
data class SmbMediaItem(
    val serverId: String,
    val smbPath: String,
    val fileName: String,
    val fileSize: Long,
    val isVideoFile: Boolean,
    val host: String,
    val share: String,
    val isGuest: Boolean,
    val username: String = "",
    val password: String = "",
    // 后台预取数据
    val albumArtBytes: ByteArray? = null,
    val artistName: String? = null,
    val realTitle: String? = null
) : MediaItem() {
    override val id: Long get() = (smbPath.hashCode()).toLong()
    override val title: String get() = realTitle ?: fileName
    override val uri: Uri get() = Uri.parse("smb://$host/${if (share.isNotEmpty()) "$share/" else ""}$fileName")
    override val path: String get() = smbPath
    override val duration: Long get() = 0
    
    override fun isVideo(): Boolean = isVideoFile
    override fun getIdentificationPath(): String = smbPath
    
    override fun getPlaybackUrl(proxy: SmbHttpProxy?): String {
        if (proxy == null) return path
        val server = SmbServer(serverId, host, share, username, password, "", isGuest)
        return proxy.registerStream(server, smbPath, fileName, fileSize)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmbMediaItem) return false
        return smbPath == other.smbPath
    }

    override fun hashCode(): Int = smbPath.hashCode()
}
