package com.mediaplayer.plus.player

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * 歌词条目：时间(毫秒) + 歌词文本
 */
data class LyricEntry(
    val timeMillis: Long,
    val text: String
)

/**
 * 修复与增强版歌词解析器
 */
object LyricParser {

    // LRC 时间戳: [mm:ss.xx] 或 【mm:ss.xx】
    private val LRC_TIME_PATTERN = Pattern.compile("[\\[\uff3b\u3010](\\d+)[:\uff1a](\\d+)(?:[.:\uff1a](\\d+))?[\\]\uff3d\u3011]")
    // 偏移量: [offset:+1234]
    private val OFFSET_PATTERN = Pattern.compile("[\\[\uff3b\u3010]offset[:\uff1a]?\\s*([+-]?\\d+)[\\]\uff3d\u3011]", Pattern.CASE_INSENSITIVE)
    // SRT 时间轴: 00:01:23,456 --> 00:02:34,567
    private val SRT_TIME_PATTERN = Pattern.compile("(\\d{1,2})[:\uff1a](\\d{2})[:\uff1a](\\d{2})[,.:\uff1a](\\d{3})\\s*-->")
    // SRT 纯数字序号
    private val SRT_INDEX_PATTERN = Pattern.compile("^\\d+$")

    // 元数据标签: [ti:歌名], [ar:歌手]
    private val META_LABELS = setOf("ti", "ar", "al", "au", "by", "la", "ve", "re", "di", "ma")

    // ==================== 歌词过滤 (提取自 V.py) ====================

    // 前10秒内要删除的关键词
    private val FIRST_10S_KEYWORDS = listOf("/", "-", "本翻译作品的著作权", "翻译", "歌词")
    private const val FIRST_10S_MS = 10_000L

    /**
     * 对歌词条目应用过滤规则，删除多余/广告/版权信息行
     * 规则：
     *   1. 全局删除包含 ':' 或 '：' 的行（标签/翻译前缀等）
     *   2. 前10秒内删除包含指定关键词的行（斜杠、横线、版权声明、翻译、歌词字样）
     */
    fun filterLyrics(entries: List<LyricEntry>): List<LyricEntry> {
        return entries.filter { entry ->
            val text = entry.text.trim()

            // 规则1: 全局删除包含冒号的行
            if (text.contains(':') || text.contains('：')) {
                return@filter false
            }

            // 规则2: 前10秒内删除包含指定关键词的行
            if (entry.timeMillis <= FIRST_10S_MS) {
                for (keyword in FIRST_10S_KEYWORDS) {
                    if (text.contains(keyword)) {
                        return@filter false
                    }
                }
            }

            true
        }
    }

    fun parse(lrcContent: String?): List<LyricEntry> {
        if (lrcContent.isNullOrBlank()) return emptyList()
        // 1. 先做文本预清洗，解决单行斜杠 ' / [' 分隔问题
        val normalized = normalizeLyricContent(lrcContent)
        // 2. 核心解析
        val rawEntries = parseText(normalized)
        // 3. 合并同一时间戳的双语翻译 (如日文+中文)
        return mergeDuplicateTimestamps(rawEntries)
    }

    fun parseFile(lrcFile: File): List<LyricEntry> {
        if (!lrcFile.exists()) return emptyList()
        return try {
            val content = readFileWithEncoding(lrcFile)
            parse(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseGzippedLrc(lrcFile: File): List<LyricEntry> {
        if (!lrcFile.exists()) return emptyList()
        return try {
            val bytes = lrcFile.readBytes()
            val decompressedBytes = try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (_: Exception) {
                bytes
            }
            val content = decodeBytes(decompressedBytes)
            parse(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseBytes(lrcBytes: ByteArray?): List<LyricEntry> {
        if (lrcBytes == null || lrcBytes.isEmpty()) return emptyList()
        return try {
            val content = decodeBytes(lrcBytes)
            parse(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 文本规范化清洗 ====================

    /**
     * 解决某些 ID3 标签将歌词用 ' / [' 或 ' / ' 压缩成单行的非标问题
     */
    private fun normalizeLyricContent(raw: String): String {
        return raw
            .removePrefix("\uFEFF")
            // 1. 将 ' / [' 替换为换行 '\n['
            .replace(Regex("\\s*/\\s*\\["), "\n[")
            // 2. 将剩余的 ' / ' 替换为换行符 '\n'（解决斜杠隔开的双语文本）
            .replace(" / ", "\n")
            .replace(" /", "\n")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }

    // ==================== 核心解析 ====================

    private fun parseText(content: String): List<LyricEntry> {
        val entries = mutableListOf<LyricEntry>()
        var globalOffset = 0L
        val plainLines = mutableListOf<String>()
        var hasTimestamp = false
        var pendingSrtTime: Long? = null

        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                pendingSrtTime = null
                return@forEach
            }

            // 1. 解析全局偏移量 [offset:+1234]
            val offsetMatcher = OFFSET_PATTERN.matcher(line)
            if (offsetMatcher.find()) {
                globalOffset = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                return@forEach
            }

            // 2. 解析 SRT 时间轴
            val srtMatcher = SRT_TIME_PATTERN.matcher(line)
            if (srtMatcher.find()) {
                hasTimestamp = true
                val h = srtMatcher.group(1)?.toLongOrNull() ?: 0L
                val m = srtMatcher.group(2)?.toLongOrNull() ?: 0L
                val s = srtMatcher.group(3)?.toLongOrNull() ?: 0L
                val ms = srtMatcher.group(4)?.toLongOrNull() ?: 0L
                val srtTime = h * 3600000L + m * 60000L + s * 1000L + ms
                pendingSrtTime = (srtTime + globalOffset).coerceAtLeast(0L)
                return@forEach
            }

            if (pendingSrtTime != null) {
                entries.add(LyricEntry(pendingSrtTime, line))
                pendingSrtTime = null
                return@forEach
            }

            if (SRT_INDEX_PATTERN.matcher(line).matches()) {
                return@forEach
            }

            // 3. 解析标准 LRC 时间戳
            val times = mutableListOf<Long>()
            val lrcMatcher = LRC_TIME_PATTERN.matcher(line)
            var lastIndex = 0

            while (lrcMatcher.find()) {
                val min = lrcMatcher.group(1)?.toLongOrNull() ?: 0L
                val sec = lrcMatcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = lrcMatcher.group(3)

                var ms = 0L
                if (msStr != null) {
                    ms = msStr.toLongOrNull() ?: 0L
                    when (msStr.length) {
                        1 -> ms *= 100
                        2 -> ms *= 10
                    }
                }

                val timeMillis = min * 60 * 1000 + sec * 1000 + ms
                times.add(timeMillis)
                lastIndex = lrcMatcher.end()
            }

            if (times.isNotEmpty()) {
                hasTimestamp = true
                // 清理多余的末尾斜杠
                val lyricText = line.substring(lastIndex).trim().removeSuffix("/").trim()

                if (lyricText.isNotEmpty() && !isMetaLine(lyricText)) {
                    times.forEach { time ->
                        val finalTime = (time + globalOffset).coerceAtLeast(0L)
                        entries.add(LyricEntry(finalTime, lyricText))
                    }
                }
            } else {
                if (!isMetaLine(line)) {
                    plainLines.add(line.removeSuffix("/").trim())
                }
            }
        }

        if (!hasTimestamp && plainLines.isNotEmpty()) {
            plainLines.forEachIndexed { index, text ->
                entries.add(LyricEntry(index * 5000L, text))
            }
        }

        return entries.sortedBy { it.timeMillis }
    }

    /**
     * 合并同一时间戳的歌词（如原文 + 中文翻译）
     */
    private fun mergeDuplicateTimestamps(entries: List<LyricEntry>): List<LyricEntry> {
        if (entries.isEmpty()) return emptyList()

        val mergedMap = LinkedHashMap<Long, StringBuilder>()

        for (entry in entries) {
            val existing = mergedMap[entry.timeMillis]
            if (existing == null) {
                mergedMap[entry.timeMillis] = StringBuilder(entry.text)
            } else {
                if (entry.text.isNotBlank() && !existing.toString().contains(entry.text)) {
                    if (existing.isNotEmpty()) existing.append("\n")
                    existing.append(entry.text)
                }
            }
        }

        return mergedMap.map { (time, textBuilder) ->
            LyricEntry(time, textBuilder.toString())
        }.sortedBy { it.timeMillis }
    }

    private fun isMetaLine(line: String): Boolean {
        // 匹配 [ti:xxx], [ar:xxx] 等格式
        if (line.startsWith("[") && line.contains(":")) {
            val label = line.substringAfter("[").substringBefore(":").lowercase().trim()
            return META_LABELS.contains(label)
        }
        return false
    }

    // ==================== 编码检测 ====================

    private val COMMON_ENCODINGS = listOf(
        "UTF-8", "GBK", "GB2312", "GB18030",
        "Shift_JIS", "EUC-JP", "EUC-KR", "Big5"
    )

    private fun readFileWithEncoding(file: File): String {
        return decodeBytes(file.readBytes())
    }

    private fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. UTF-8 BOM 检查
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charset.forName("UTF-8"))
        }

        // 2. 尝试 UTF-8 解码
        try {
            val utf8Str = String(bytes, Charset.forName("UTF-8"))
            if (!utf8Str.contains("\uFFFD")) return utf8Str
        } catch (_: Exception) {}

        // 3. 尝试常用字符集
        for (encoding in COMMON_ENCODINGS) {
            try {
                val str = String(bytes, Charset.forName(encoding))
                if (!str.contains("\uFFFD") && str.lines().any { it.trim().isNotBlank() }) {
                    return str
                }
            } catch (_: Exception) {
                continue
            }
        }

        // 4. 兜底返回
        return String(bytes, Charset.forName("UTF-8"))
    }
}