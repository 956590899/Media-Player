package com.mediaplayer.plus.ui

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.mediaplayer.plus.MainActivity
import com.mediaplayer.plus.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 悬浮歌词服务 — 在桌面 / 其他应用上方显示当前播放歌词
 */
class FloatingLyricsService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var lyricsTextView: TextView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "floating_lyrics"

        // 供外部观察当前歌词行
        private val _currentLyricLine = MutableStateFlow("")
        val currentLyricLine: StateFlow<String> = _currentLyricLine

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        var onLyricLineRequested: (() -> String)? = null

        // 控制悬浮歌词在主界面内是否可见：false = 隐藏, true = 显示
        @Volatile
        var visibleInApp = true

        fun updateLyricLine(line: String) {
            _currentLyricLine.value = line
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingLyricsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingLyricsService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("悬浮歌词")
                .setContentText("正在显示歌词")
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setOngoing(true)
                .build()
        )
        createFloatingView()
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        removeFloatingView()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "悬浮歌词",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮歌词通知"
                setShowBadge(false)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun createFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val textView = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4f, 0f, 2f, 0xAA000000.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            maxLines = 2
            // 默认完全透明，uiBackgroundReview 开启时显示背景
        }
        lyricsTextView = textView

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        // 拖动支持
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        textView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(textView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        floatingView = textView
        windowManager?.addView(textView, layoutParams)

        // 定期刷新歌词
        serviceScope.launch {
            while (isActive) {
                val line = _currentLyricLine.value
                val show = visibleInApp && line.isNotEmpty()
                if (!show) {
                    textView.visibility = View.GONE
                } else {
                    textView.visibility = View.VISIBLE
                    textView.text = line
                }
                // 同步 uiBackgroundReview 开关
                val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                val showBg = prefs.getBoolean("ui_background_review", false)
                textView.setBackgroundColor(if (showBg) 0x88000000.toInt() else 0x00000000.toInt())
                delay(300)
            }
        }
    }

    private fun removeFloatingView() {
        floatingView?.let {
            windowManager?.removeView(it)
        }
        floatingView = null
        lyricsTextView = null
    }
}