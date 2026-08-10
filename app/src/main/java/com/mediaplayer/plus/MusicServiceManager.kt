package com.mediaplayer.plus

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 管理 MusicService 的启动和回调注册
 */
object MusicServiceManager {

    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    private var isRunning = false

    // 同进程共享的专辑图字节缓存（PlayerViewModel 写入，MusicService 读取）
    @Volatile
    var sharedAlbumArtBytes: ByteArray? = null
    // 使用内容哈希判断 bytes 是否实质变化（解决引用比较被绕过的问题）
    @Volatile
    var sharedAlbumArtBytesHash: Int = 0

    // 当前通知栏使用的缓存值，用于在 updateNotification() 中节流
    @Volatile
    var lastNotifiedAlbumArtUrl: String? = null
    @Volatile
    var lastNotifiedTitle: String = ""
    @Volatile
    var lastNotifiedSubtitle: String = ""
    @Volatile
    var lastNotifiedDuration: Long = -1L
    @Volatile
    var lastNotifiedSpeed: Float = 1.0f
    @Volatile
    var lastKnownPlaying: Boolean = false
    @Volatile
    var lastNotifiedAlbumArtHash: Int = -1  // 内容哈希，判断专辑图是否变化

    // 轻量更新间隔（毫秒），超过此间隔才刷新一次进度到 MediaSession
    private const val POSITION_UPDATE_INTERVAL_MS = 1000L
    @Volatile
    private var lastPositionUpdateTime: Long = 0L

    /**
     * 轻量位置更新：只向 MediaSession 更新 PlaybackState 进度，
     * 不重新构建 Notification、不重复解码 Bitmap。
     * 内部节流：1 秒内最多调用一次。
     */
    fun updatePosition(
        context: Context,
        isPlaying: Boolean,
        position: Long,
        duration: Long,
        speed: Float
    ) {
        if (!isRunning) return

        val now = System.currentTimeMillis()
        if (now - lastPositionUpdateTime < POSITION_UPDATE_INTERVAL_MS) return
        lastPositionUpdateTime = now

        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_POSITION_ONLY
            putExtra(MusicService.EXTRA_IS_PLAYING, isPlaying)
            putExtra(MusicService.EXTRA_POSITION, position)
            putExtra(MusicService.EXTRA_DURATION, duration)
            putExtra(MusicService.EXTRA_SPEED, speed)
        }
        context.startService(intent)
    }

    fun start(
        context: Context,
        title: String,
        subtitle: String,
        isPlaying: Boolean,
        albumArtUrl: String? = null,
        position: Long = -1L,
        duration: Long = -1L,
        speed: Float = 1.0f
    ) {
        lastNotifiedTitle = title
        lastNotifiedSubtitle = subtitle
        lastNotifiedAlbumArtUrl = albumArtUrl
        lastNotifiedDuration = duration
        lastNotifiedSpeed = speed
        lastKnownPlaying = isPlaying
        lastNotifiedAlbumArtHash = sharedAlbumArtBytesHash
        lastPositionUpdateTime = System.currentTimeMillis()

        val intent = Intent(context, MusicService::class.java).apply {
            putExtra(MusicService.EXTRA_TITLE, title)
            putExtra(MusicService.EXTRA_SUBTITLE, subtitle)
            putExtra(MusicService.EXTRA_IS_PLAYING, isPlaying)
            putExtra(MusicService.EXTRA_ALBUM_ART, albumArtUrl)
            putExtra(MusicService.EXTRA_POSITION, position)
            putExtra(MusicService.EXTRA_DURATION, duration)
            putExtra(MusicService.EXTRA_SPEED, speed)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        isRunning = true
    }

    fun update(
        context: Context,
        title: String,
        subtitle: String,
        isPlaying: Boolean,
        albumArtUrl: String? = null,
        position: Long = -1L,
        duration: Long = -1L,
        speed: Float = 1.0f
    ) {
        val currentArtHash = sharedAlbumArtBytesHash

        // 用内容哈希判断专辑图是否变化（解决引用比较被绕过的问题）
        val artContentChanged = currentArtHash != lastNotifiedAlbumArtHash
        val titleChanged = title != lastNotifiedTitle
        val subtitleChanged = subtitle != lastNotifiedSubtitle
        val artUrlChanged = albumArtUrl != lastNotifiedAlbumArtUrl
        val durationChanged = duration != lastNotifiedDuration
        val speedChanged = speed != lastNotifiedSpeed
        val playingChanged = isPlaying != lastKnownPlaying

        lastNotifiedTitle = title
        lastNotifiedSubtitle = subtitle
        lastNotifiedAlbumArtUrl = albumArtUrl
        lastNotifiedDuration = duration
        lastNotifiedSpeed = speed
        lastNotifiedAlbumArtHash = currentArtHash
        lastKnownPlaying = isPlaying

        if (!isRunning) {
            start(context, title, subtitle, isPlaying, albumArtUrl, position, duration, speed)
            return
        }

        // 有实质性变化 → 发完整更新（重建通知 + 可能触发解码）
        if (titleChanged || subtitleChanged || artContentChanged || artUrlChanged
            || durationChanged || speedChanged || playingChanged) {
            val intent = Intent(context, MusicService::class.java).apply {
                putExtra(MusicService.EXTRA_TITLE, title)
                putExtra(MusicService.EXTRA_SUBTITLE, subtitle)
                putExtra(MusicService.EXTRA_IS_PLAYING, isPlaying)
                putExtra(MusicService.EXTRA_ALBUM_ART, albumArtUrl)
                putExtra(MusicService.EXTRA_POSITION, position)
                putExtra(MusicService.EXTRA_DURATION, duration)
                putExtra(MusicService.EXTRA_SPEED, speed)
            }
            context.startService(intent)
        } else {
            // 仅位置变化 → 轻量路径，只更新 PlaybackState
            updatePosition(context, isPlaying, position, duration, speed)
        }
    }

    fun stop(context: Context) {
        sharedAlbumArtBytes = null
        sharedAlbumArtBytesHash = 0
        lastNotifiedAlbumArtUrl = null
        lastNotifiedTitle = ""
        lastNotifiedSubtitle = ""
        lastNotifiedDuration = -1L
        lastNotifiedSpeed = 1.0f
        lastNotifiedAlbumArtHash = -1
        lastKnownPlaying = false
        val intent = Intent(context, MusicService::class.java)
        context.stopService(intent)
        isRunning = false
    }

    /**
     * 更新蓝牙歌词：
     * 1. 通过 AVRCP PassThrough 广播到蓝牙 AVRCP 耳机/车载屏幕
     * 2. 通过 MediaSession metadata 兼容锁屏 / Android Auto
     */
    fun updateLyric(context: Context, lyricText: String?, title: String? = null, artist: String? = null) {
        BluetoothLyricsManager.updateLyrics(context, lyricText, title, artist)
    }

    /** 清理蓝牙歌词缓存 */
    fun clearLyric() {
        BluetoothLyricsManager.clearLyrics()
    }
}