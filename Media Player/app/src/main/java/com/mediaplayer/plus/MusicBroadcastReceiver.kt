package com.mediaplayer.plus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收音乐通知栏按钮点击的广播
 */
class MusicBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.mediaplayer.plus.ACTION_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.mediaplayer.plus.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.mediaplayer.plus.ACTION_NEXT"
        const val ACTION_DISMISS = "com.mediaplayer.plus.ACTION_DISMISS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE -> MusicServiceManager.onPlayPause?.invoke()
            ACTION_PREVIOUS -> MusicServiceManager.onPrevious?.invoke()
            ACTION_NEXT -> MusicServiceManager.onNext?.invoke()
            ACTION_DISMISS -> MusicServiceManager.onDismiss?.invoke()
            MusicService.ACTION_UPDATE -> {
                // 处理通知栏进度条拖动：传递 seek 位置
                val pos = intent.getLongExtra(MusicService.EXTRA_POSITION, -1L)
                if (pos >= 0) {
                    MusicServiceManager.onSeekTo?.invoke(pos)
                }
            }
        }
    }
}
