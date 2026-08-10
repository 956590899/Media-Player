package com.mediaplayer.plus.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 视频缩略图磁盘缓存管理器
 *
 * 与音乐专辑图采用相同的缓存策略：
 * 1. 首次加载：后台抓取视频第 1 秒画面，压缩为 JPEG 保存到 cacheDir
 * 2. 后续加载：直接读取本地 JPEG 文件（毫秒级返回，和音乐专辑图一样快）
 * 3. 缓存键：使用"视频路径 + 修改时间"的 MD5，文件变更自动重新生成
 * 4. 缓存路径：context.cacheDir/video_thumbnails/，不污染用户 SD 卡
 */
class VideoThumbnailManager(private val context: Context) {

    // 缩略图存储路径：/data/data/com.mediaplayer.plus/cache/video_thumbnails/
    private val cacheDir: File by lazy {
        File(context.cacheDir, "video_thumbnails").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * 获取视频缩略图路径（极速缓存版）：
     * 1. 优先查找磁盘中已存在的 JPEG 缓存文件（毫秒级返回）
     * 2. 若不存在，后台抽取 1 秒处的视频帧，压缩保存为 JPEG 缓存文件后返回
     */
    suspend fun getThumbnailPath(videoPath: String): String? = withContext(Dispatchers.IO) {
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return@withContext null

        // 依据"视频路径 + 修改时间"生成唯一 MD5 缓存文件名
        val cacheKey = md5("${videoPath}_${videoFile.lastModified()}")
        val targetCacheFile = File(cacheDir, "$cacheKey.jpg")

        // 【步骤 1】：如果本地 JPEG 缓存文件已存在且有效，直接返回路径
        // 加载速度与音乐专辑封面完全一致，毫秒级
        if (targetCacheFile.exists() && targetCacheFile.length() > 0) {
            return@withContext targetCacheFile.absolutePath
        }

        // 【步骤 2】：缓存不存在，抽取一帧画面并保存到 cacheDir
        // 目标尺寸：标准 480p (854x480)，单张仅 ~30KB，10,000 个视频总缓存 < 300MB
        val targetWidth = 854
        val targetHeight = 480

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)

            // 硬件级直接提取 480p 缩略图，零 CPU 缩放开销
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                retriever.getScaledFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                )
            } else {
                // Android 8.0 以下回退：原尺寸抓帧 + 手动缩放
                val raw = retriever.getFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.frameAtTime
                raw?.let {
                    Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true).also { scaled ->
                        if (scaled != it) it.recycle()
                    }
                }
            }
            retriever.release()

            if (bitmap != null) {
                // 75% JPEG 质量，单张 ~30KB，兼顾清晰度与极低存储开销
                FileOutputStream(targetCacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                }
                bitmap.recycle()
                return@withContext targetCacheFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        null
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 将视频的 contentUri 加载为 480p 尺寸的系统媒体通知栏/锁屏专用的 Bitmap 封面
 *
 * 两个关键安全措施：
 * 1. allowHardware(false) — 禁用硬件位图，防止跨进程 Binder 渲染崩溃
 * 2. size(854, 480) — 标准 480p 尺寸，仅 1.6MB 内存，Binder 传输安全
 */
suspend fun loadVideoThumbnailBitmap(context: Context, videoUri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val imageLoader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(videoUri)
            .size(854, 480)        // 标准 480p，仅 1.6MB 内存，Binder 传输安全
            .allowHardware(false)   // 必须禁用硬件位图，通知栏跨进程渲染仅支持软件位图
            .build()
        val result = imageLoader.execute(request)
        (result.drawable as? BitmapDrawable)?.bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}