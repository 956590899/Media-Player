package com.mediaplayer.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ART_SIZE = 400  // 通知栏专辑图最大边长（像素），杜绝 800x800 大图

/**
 * 原生音乐播放前台服务
 * 使用 MediaSession + MediaStyle 创建系统级音乐控制通知
 * 无需通知权限（Android 13+ 的 MediaStyle 前台服务媒体通知被系统豁免）
 */
class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE = "com.mediaplayer.plus.UPDATE_NOTIFICATION"
        const val ACTION_POSITION_ONLY = "com.mediaplayer.plus.POSITION_ONLY"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_ALBUM_ART = "album_art"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_SPEED = "speed"
    }

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // 缓存解码后的 Bitmap —— 避免重复 decode
    private var cachedAlbumArtBitmap: Bitmap? = null
    // 使用内容哈希判断是否需要重新解码
    private var cachedAlbumArtHash: Int = -1

    override fun onCreate() {
        super.onCreate()
        initMediaSession()
        createNotificationChannel()
        BluetoothLyricsManager.init(this)
        BluetoothLyricsManager.setMediaSession(mediaSession)
        BluetoothLyricsManager.setLyricsCallback { lyricText ->
            if (lyricText.isNotBlank()) {
                BluetoothLyricsManager.sendLyricsViaAvrcp(this, lyricText)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. 轻量路径：仅更新 PlaybackState，不重建通知、不重复解码 Bitmap
        if (ACTION_POSITION_ONLY == intent?.action) {
            val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
            val position = intent.getLongExtra(EXTRA_POSITION, -1L)
            val duration = intent.getLongExtra(EXTRA_DURATION, -1L)
            val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
            updatePlaybackState(isPlaying, position, duration, speed)
            return START_STICKY
        }

        // 2. 完整路径：更新通知 + MediaSession
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: ""
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) ?: false
        val albumArt = intent?.getStringExtra(EXTRA_ALBUM_ART)
        val position = intent?.getLongExtra(EXTRA_POSITION, -1L) ?: -1L
        val duration = intent?.getLongExtra(EXTRA_DURATION, -1L) ?: -1L
        val speed = intent?.getFloatExtra(EXTRA_SPEED, 1.0f) ?: 1.0f

        updatePlaybackState(isPlaying, position, duration, speed)

        // 优先用同进程共享的 albumArtBytes（ID3 内嵌封面），否则用 URL
        val sharedBytes = MusicServiceManager.sharedAlbumArtBytes
        if (sharedBytes != null && sharedBytes.isNotEmpty()) {
            val currentHash = MusicServiceManager.sharedAlbumArtBytesHash
            // 仅当内容哈希变化时才重新解码（切歌时才会变）
            if (currentHash != cachedAlbumArtHash) {
                cachedAlbumArtHash = currentHash
                // 解码必须在子线程执行，禁止主线程 Skia 解码
                serviceScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeArtBytesToBitmap(sharedBytes)
                    }
                    // 不再手动回收 Bitmap，由 GC 处理，防止跨进程传输时出现 "Can't parcel a recycled bitmap"
                    cachedAlbumArtBitmap = bitmap
                    val art = cachedAlbumArtBitmap
                    updateMetadata(title, subtitle, duration, art)
                    val notification = buildNotification(title, subtitle, isPlaying, art)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                val art = cachedAlbumArtBitmap
                updateMetadata(title, subtitle, duration, art)
                val notification = buildNotification(title, subtitle, isPlaying, art)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else if (albumArt != null) {
            loadArtAndNotify(title, subtitle, isPlaying, duration, albumArt)
        } else {
            updateMetadata(title, subtitle, duration, null)
            val notification = buildNotification(title, subtitle, isPlaying, null)
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    /**
     * 将专辑图字节解码并缩放到 MAX_ART_SIZE 以内，生成软件位图供通知栏使用
     * 使用 ceil 取整确保解码结果尺寸不会超出上限
     */
    private fun decodeArtBytesToBitmap(bytes: ByteArray): android.graphics.Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val maxDim = opts.outWidth.coerceAtLeast(opts.outHeight)
            // ceil(maxDim / MAX_ART_SIZE) 确保结果尺寸 ≤ MAX_ART_SIZE
            val scale = ((maxDim + MAX_ART_SIZE - 1) / MAX_ART_SIZE).coerceAtLeast(1)
            val scaledOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, scaledOpts)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadArtAndNotify(title: String, subtitle: String, isPlaying: Boolean, duration: Long, url: String) {
        serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(this@MusicService)
                        .data(url)
                        .size(MAX_ART_SIZE, MAX_ART_SIZE)
                        .allowHardware(false)   // 必须禁用硬件位图，通知栏跨进程仅支持软件位图
                        .build()
                    val result = imageLoader.execute(request)
                    (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                } catch (e: Exception) {
                    null
                }
            }
            // 不再手动回收 Bitmap，防止跨进程传输异常
            cachedAlbumArtBitmap = bitmap
            cachedAlbumArtHash = -1
            updateMetadata(title, subtitle, duration, bitmap)
            val notification = buildNotification(title, subtitle, isPlaying, bitmap)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession.release()
        BluetoothLyricsManager.release(this)
        cachedAlbumArtBitmap = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setFlags(
                0x2 or  // FLAG_HANDLES_MEDIA_BUTTONS (deprecated, value=2)
                0x4     // FLAG_HANDLES_TRANSPORT_CONTROLS (deprecated, value=4)
            )
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    MusicServiceManager.onPlay?.invoke()
                }

                override fun onPause() {
                    MusicServiceManager.onPause?.invoke()
                }

                override fun onSkipToNext() {
                    MusicServiceManager.onNext?.invoke()
                }

                override fun onSkipToPrevious() {
                    MusicServiceManager.onPrevious?.invoke()
                }

                // 允许用户在通知栏/锁屏直接拖动进度条
                override fun onSeekTo(pos: Long) {
                    MusicServiceManager.onSeekTo?.invoke(pos)
                }
            })
        }
    }

    /**
     * 更新 MediaSession 播放状态，支持通知栏进度条与拖动
     * - position/duration：通知栏进度条显示
     * - SystemClock.elapsedRealtime()：Android 系统靠此时间戳自动平滑推算进度条流动
     * - ACTION_SEEK_TO：允许用户在通知栏直接拖动进度条
     */
    private fun updatePlaybackState(
        isPlaying: Boolean,
        position: Long = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
        duration: Long = -1L,
        speed: Float = 1.0f
    ) {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO   // 【关键】允许通知栏拖动进度条
            )
            .setState(
                state,
                position.coerceAtLeast(0L),         // 当前播放进度(ms)
                if (isPlaying) speed else 0f,        // 播放倍速
                SystemClock.elapsedRealtime()         // 系统基准时间戳，用于自动平滑推算进度
            )

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    /**
     * 更新 MediaSession 元数据（标题、歌手、总时长、专辑图）
     * METADATA_KEY_ALBUM_ART 是 MIUI 胶囊/实况窗正常渲染的必要条件
     * 缺失该字段会导致 DynamicIslandBackgroundView 高度=0 并触发 checkError
     */
    private fun updateMetadata(title: String, subtitle: String, duration: Long, albumArt: Bitmap?) {
        // 蓝牙歌词启用时，歌名加《》，蓝牙 AVRCP 设备/锁屏读取时显示带书名号
        val displayTitle = if (BluetoothLyricsManager.isEnabled(this) && title.isNotBlank()) "《$title》" else title
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration.coerceAtLeast(0L))
            
        // 关键：增加 isRecycled 检查，防止跨进程传输时崩溃
        if (albumArt != null && !albumArt.isRecycled) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, albumArt)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean, largeIcon: Bitmap?): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            this, 101,
            Intent(MusicBroadcastReceiver.ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = PendingIntent.getBroadcast(
            this, 102,
            Intent(MusicBroadcastReceiver.ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getBroadcast(
            this, 103,
            Intent(MusicBroadcastReceiver.ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getBroadcast(
            this, 104,
            Intent(MusicBroadcastReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(activityPendingIntent)
            .setDeleteIntent(dismissIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_music_note, "Previous", previousIntent)
            .addAction(
                R.drawable.ic_music_note,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(R.drawable.ic_music_note, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (largeIcon != null && !largeIcon.isRecycled) {
            builder.setLargeIcon(largeIcon)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于显示锁屏和下拉菜单的原生音乐播放控制"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
