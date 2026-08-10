package com.mediaplayer.plus.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌词仓库接口
 * 支持优先内嵌歌词，次之本地搜索，方便后续扩展网络歌词 API
 */
interface LyricRepository {
    /**
     * 获取歌词（异步 IO）
     * @param mediaUrl 媒体文件路径（可以是本地路径或 HTTP URL）
     * @param rawEmbeddedLyrics 从标签提取的原始歌词文本（可为 null）
     * @param decodedFileName 解码后的文件名（不含扩展名），用于兜底搜索
     * @return 已解析的歌词条目列表
     */
    suspend fun fetchLyrics(mediaUrl: String, rawEmbeddedLyrics: String?, decodedFileName: String? = null): List<LyricEntry>
}

/**
 * 默认歌词仓库：内嵌优先 + 多路径本地搜索
 * 所有磁盘 IO 操作在 Dispatchers.IO 中执行，不阻塞主线程
 */
class DefaultLyricRepository : LyricRepository {
    override suspend fun fetchLyrics(mediaUrl: String, rawEmbeddedLyrics: String?, decodedFileName: String?): List<LyricEntry> =
        withContext(Dispatchers.IO) {
            // 1. 尝试解析内嵌歌词（已在 MediaPlayer 层提取）
            if (!rawEmbeddedLyrics.isNullOrBlank()) {
                val parsed = LyricParser.parse(rawEmbeddedLyrics)
                if (parsed.isNotEmpty()) {
                    Log.d("LyricRepo", "Loaded embedded lyrics (${parsed.size} lines)")
                    return@withContext parsed
                }
            }

            // 2. 尝试本地文件搜索
            val file = File(mediaUrl)
            if (file.exists()) {
                val dir = file.parentFile
                if (dir != null) {
                    val nameWithoutExt = file.nameWithoutExtension
                    val candidates = buildCandidates(dir, nameWithoutExt)
                    for (candidate in candidates) {
                        if (candidate.exists()) {
                            Log.d("LyricRepo", "Found external lyric file: ${candidate.absolutePath}")
                            val parsed = LyricParser.parseFile(candidate)
                            if (parsed.isNotEmpty()) {
                                Log.d("LyricRepo", "Loaded external lyrics (${parsed.size} lines) from ${candidate.name}")
                                return@withContext parsed
                            }
                            if (candidate.name.endsWith(".txt", ignoreCase = true)) {
                                val textParsed = LyricParser.parse(candidate.readText())
                                if (textParsed.isNotEmpty()) {
                                    Log.d("LyricRepo", "Loaded plain text lyrics (${textParsed.size} lines)")
                                    return@withContext textParsed
                                }
                            }
                        }
                    }
                }
            }

            // 3. SMB/HTTP 网络播放兜底：用解码后的文件名搜索常见目录
            if (!mediaUrl.startsWith("http") || !decodedFileName.isNullOrBlank()) {
                val searchName = decodedFileName ?: mediaUrl.substringAfterLast("/").substringBeforeLast(".")
                val searchDir = searchDirForLyrics(searchName)
                if (searchDir != null) {
                    val candidates = buildCandidates(searchDir, searchName)
                    for (candidate in candidates) {
                        if (candidate.exists()) {
                            Log.d("LyricRepo", "Found external lyric by searchName: ${candidate.absolutePath}")
                            val parsed = LyricParser.parseFile(candidate)
                            if (parsed.isNotEmpty()) {
                                Log.d("LyricRepo", "Loaded search lyrics (${parsed.size} lines)")
                                return@withContext parsed
                            }
                        }
                    }
                }
            }

            Log.d("LyricRepo", "No lyrics found for: $mediaUrl")
            emptyList()
        }

    private fun buildCandidates(dir: java.io.File, nameWithoutExt: String): List<java.io.File> {
        return listOf(
            dir.resolve("$nameWithoutExt.lrc"),
            dir.resolve("$nameWithoutExt.LRC"),
            dir.resolve("$nameWithoutExt.srt"),
            dir.resolve("$nameWithoutExt.ass"),
            dir.resolve("$nameWithoutExt.ssa"),
            dir.resolve("$nameWithoutExt.txt"),
            dir.resolve("lyrics/$nameWithoutExt.lrc"),
            dir.parentFile?.resolve("$nameWithoutExt.lrc"),
            dir.parentFile?.resolve("lyrics/$nameWithoutExt.lrc")
        ).filterNotNull().distinct()
    }

    private fun searchDirForLyrics(name: String): java.io.File? {
        val dirs: List<String> = listOf(
            "/sdcard/Music",
            "/sdcard/Downloads",
            "/storage/emulated/0/Music",
            "/storage/emulated/0/Downloads",
            "/sdcard/Music/lyrics",
            "/sdcard/Downloads/lyrics"
        )
        for (d in dirs) {
            val f = java.io.File(d)
            if (f.isDirectory) {
                val sub = f.listFiles { _, n ->
                    n.equals("$name.lrc", ignoreCase = true) ||
                    n.equals("$name.txt", ignoreCase = true)
                }
                if (sub != null && sub.isNotEmpty()) {
                    return f
                }
            }
        }
        return null
    }
}
