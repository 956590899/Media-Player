package com.mediaplayer.plus

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DlnaCastService : Service() {

    companion object {
        const val CHANNEL_ID = "dlna_cast_channel"
        const val NOTIFICATION_ID = 9001

        fun startForeground(context: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "DLNA 投屏", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "DLNA 投屏保活通知"
                    setShowBadge(false)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
                context.startForegroundService(Intent(context, DlnaCastService::class.java))
            } else {
                context.startService(Intent(context, DlnaCastService::class.java))
            }
        }

        fun stopService(context: android.content.Context) {
            context.stopService(Intent(context, DlnaCastService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DLNA 投屏", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DLNA 投屏保活通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DLNA 投屏中")
            .setContentText("正在通过 DLNA 播放")
            .setSmallIcon(R.drawable.ic_music_note)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
