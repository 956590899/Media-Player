package com.mediaplayer.plus

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import java.lang.reflect.Method

/**
 * 蓝牙歌词管理
 *
 * 工作方式：
 * 1. 通过 AVRCP PassThrough (API 28+) 向蓝牙 AVRCP 设备（耳机、车载屏幕）推送歌词
 * 2. 兼容方案：更新 MediaSession 元数据，让锁屏 / Android Auto UI 读取当前歌词
 *
 * AVRCP PassThrough 协议（BT-SEP + AVRCP 1.6）：
 * - SUBGROUP_ID = 0x8F (Displayable Group)
 * - OPERATION_ID = 0x48 (Display Control)
 * - PARAM_ID    = 0x00
 */
object BluetoothLyricsManager {

    private const val TAG = "BluetoothLyrics"
    private const val PREF_KEY = "bluetooth_lyrics_enabled"
    private const val PREF_NAME = "player_prefs"
    private const val MAX_LYRIC_BYTES = 400

    // AVRCP PassThrough 常量（API 28+）
    private const val AVRCP_SUBGROUP_ID = 0x8F
    private const val AVRCP_OPERATION_ID = 0x48
    private const val AVRCP_PARAM_ID = 0x00

    private var lyricsCallback: ((String) -> Unit)? = null
    private var audioManager: AudioManager? = null
    private var sessionCompat: MediaSessionCompat? = null
    private var lastSentText: String = ""

    // 缓存反射方法，防止在不支持的设备上高频解析抛出 NoSuchMethodException 导致日志暴涨
    @Volatile
    private var isAvrcpMethodResolved = false
    private var avrcpMethod: Method? = null

    fun setMediaSession(session: MediaSessionCompat) {
        sessionCompat = session
    }

    fun init(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        resolveAvrcpMethod()
    }

    fun release(context: Context) {
        sessionCompat = null
        lastSentText = ""
        isAvrcpMethodResolved = false
        avrcpMethod = null
    }

    /**
     * 一次性解析厂商私有 AVRCP 歌词反射方法。
     * 如果系统不支持，则标记为 null 并不再重复尝试反射。
     */
    private fun resolveAvrcpMethod() {
        if (isAvrcpMethodResolved) return
        val am = audioManager ?: return
        try {
            avrcpMethod = am.javaClass.getMethod(
                "sendBluetoothAvrcpPassThrough",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                ByteArray::class.java
            )
            Log.d(TAG, "Successfully resolved proprietary sendBluetoothAvrcpPassThrough method.")
        } catch (e: NoSuchMethodException) {
            Log.i(TAG, "sendBluetoothAvrcpPassThrough not supported on this device. Fallback mode will be used.")
            avrcpMethod = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve AVRCP reflection method", e)
            avrcpMethod = null
        } finally {
            isAvrcpMethodResolved = true
        }
    }

    fun isBluetoothAvailable(): Boolean {
        val bt = BluetoothAdapter.getDefaultAdapter()
        return bt != null && bt.isEnabled
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, enabled).apply()
        if (!enabled) {
            lastSentText = ""
            lyricsCallback?.invoke("")
        }
    }

    fun setLyricsCallback(callback: (String) -> Unit) {
        lyricsCallback = callback
    }

    /**
     * 更新歌词：
     * - 通过回调触发 AVRCP PassThrough 发送
     * - 同时更新 MediaSession metadata，锁屏/Android Auto 可读取
     */
    fun updateLyrics(context: Context, lyrics: String?, title: String?, artist: String?) {
        if (audioManager == null) init(context)
        val isEnabled = isEnabled(context)
        val useMediaSessionFallback = isEnabled && avrcpMethod == null
        updateMediaSessionLyrics(lyrics, title, artist, isEnabled, useMediaSessionFallback)
        
        if (!isEnabled) return
        if (lyrics == null || lyrics.isBlank()) {
            lyricsCallback?.invoke("")
            return
        }
        val trimmed = lyrics.take(MAX_LYRIC_BYTES)
        if (trimmed != lastSentText) {
            lastSentText = trimmed
            lyricsCallback?.invoke(trimmed)
        }
    }

    fun clearLyrics() {
        lastSentText = ""
        lyricsCallback?.invoke("")
        updateMediaSessionLyrics(null, null, null, false, false)
    }

    /**
     * 拆分双语歌词
     * 支持常见的换行符、斜杠、竖线等双语歌词连接方式
     * 
     * @return Pair(主歌词, 翻译歌词)
     */
    private fun splitBilingualLyric(lyric: String?): Pair<String?, String?> {
        if (lyric == null) return Pair(null, null)
        
        val delimiters = listOf("\n", "\r\n", " / ", " | ", " // ")
        for (delim in delimiters) {
            if (lyric.contains(delim)) {
                val parts = lyric.split(delim, limit = 2)
                if (parts.size == 2 && parts[0].trim().isNotEmpty() && parts[1].trim().isNotEmpty()) {
                    return Pair(parts[0].trim(), parts[1].trim())
                }
            }
        }
        return Pair(lyric, null)
    }

    /** 
     * 通过 MediaSession 注入歌词元数据（锁屏 / Android Auto 兼容方案）
     * 注意：使用 session.metadata 作为基底，避免覆盖 DURATION 等由 MusicService 维护的字段
     */
    private fun updateMediaSessionLyrics(
        lyrics: String?, 
        title: String?, 
        artist: String?,
        enabled: Boolean,
        useFallback: Boolean
    ) {
        val session = sessionCompat ?: return
        try {
            val existing = session.controller.metadata
            // 不再给歌名加《》，改为在歌手区展示《歌手 - 歌名》
            val displayTitle = title ?: ""
            val builder = MediaMetadataCompat.Builder(existing ?: MediaMetadataCompat.Builder().build()).apply {
                val originalArtist = artist ?: ""

                if (useFallback && !lyrics.isNullOrBlank()) {
                    val (lyric1, lyric2) = splitBilingualLyric(lyrics)
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, lyric1 ?: displayTitle)
                    val artistDisplay = lyric2 ?: when {
                        originalArtist.isNotEmpty() && displayTitle.isNotEmpty() -> "《$originalArtist - $displayTitle》"
                        originalArtist.isNotEmpty() -> "《$originalArtist》"
                        displayTitle.isNotEmpty() -> "《$displayTitle》"
                        else -> " "
                    }
                    putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistDisplay)
                } else {
                    if (title != null) putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
                    if (artist != null) putString(MediaMetadataCompat.METADATA_KEY_ARTIST, originalArtist)
                }

                if (lyrics != null) putString("com.mediaplayer.plus.KEY_LYRICS", lyrics)
            }
            session.setMetadata(builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "updateMediaSessionLyrics failed", e)
        }
    }

    /**
     * AVRCP PassThrough 发送歌词（针对自带私有协议支持的定制系统）
     */
    fun sendLyricsViaAvrcp(context: Context, lyrics: String?): Boolean {
        if (lyrics == null || lyrics.isBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        
        resolveAvrcpMethod()
        
        val am = audioManager ?: return false
        val method = avrcpMethod ?: return false // 若不支持，直接返回，不再引发反射异常
        
        return try {
            val data = lyrics.toByteArray(Charsets.US_ASCII)
            val btsinkProfile = 8
            val result = method.invoke(am, btsinkProfile, AVRCP_SUBGROUP_ID, AVRCP_OPERATION_ID, AVRCP_PARAM_ID, data) as Int
            result == 1
        } catch (e: SecurityException) {
            Log.w(TAG, "AVRCP PassThrough permission denied (BLUETOOTH_CONNECT missing on Android 12+)", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "AVRCP PassThrough error", e)
            false
        }
    }
}