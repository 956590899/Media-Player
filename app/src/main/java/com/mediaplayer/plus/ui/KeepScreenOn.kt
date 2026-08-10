package com.mediaplayer.plus.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 保持屏幕常亮（防息屏）组件
 *
 * 播放视频时自动开启 FLAG_KEEP_SCREEN_ON，横屏/竖屏均生效；
 * 暂停或切到纯音频时自动清除标志，恢复系统默认息屏逻辑，防止无辜耗电。
 *
 * @param keepOn 是否保持常亮（当正在播放视频时传入 true）
 */
@Composable
fun KeepScreenOn(keepOn: Boolean) {
    val context = LocalContext.current

    DisposableEffect(keepOn) {
        val activity = context.findActivity()
        if (activity != null && keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}