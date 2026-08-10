package com.mediaplayer.plus.data

import android.util.Log
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 极简 ID3v2 标签解析器 — 专门用于 SMB 后台预取
 */
object Id3Metadata {
    private const val TAG = "Id3Metadata"

    data class Result(
        val title: String? = null,
        val artist: String? = null,
        val albumArt: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            if (title != other.title) return false
            if (artist != other.artist) return false
            if (albumArt != null) {
                if (other.albumArt == null) return false
                if (!albumArt.contentEquals(other.albumArt)) return false
            } else if (other.albumArt != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = title?.hashCode() ?: 0
            result = 31 * result + (artist?.hashCode() ?: 0)
            result = 31 * result + (albumArt?.contentHashCode() ?: 0)
            return result
        }
    }

    fun parse(stream: InputStream): Result? {
        try {
            val header = ByteArray(10)
            if (stream.read(header) != 10) return null
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null

            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                    ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or
                    (header[9].toInt() and 0x7F)

            if (tagSize <= 0) return null

            var title: String? = null
            var artist: String? = null
            var albumArt: ByteArray? = null

            var pos = 0
            while (pos < tagSize) {
                val frameHeader = ByteArray(10)
                if (stream.read(frameHeader) != 10) break
                pos += 10

                val id = String(frameHeader, 0, 4, StandardCharsets.ISO_8859_1)
                val size = ((frameHeader[4].toInt() and 0xFF) shl 24) or
                        ((frameHeader[5].toInt() and 0xFF) shl 16) or
                        ((frameHeader[6].toInt() and 0xFF) shl 8) or
                        (frameHeader[7].toInt() and 0xFF)

                if (size <= 0 || pos + size > tagSize) break

                when (id) {
                    "TIT2" -> title = readString(stream, size)
                    "TPE1" -> artist = readString(stream, size)
                    "APIC" -> albumArt = readApic(stream, size)
                    else -> stream.skip(size.toLong())
                }
                pos += size
                
                // 如果三个都拿到了，可以提前结束
                if (title != null && artist != null && albumArt != null) break
            }
            return Result(title, artist, albumArt)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ID3", e)
            return null
        }
    }

    private fun readString(stream: InputStream, size: Int): String? {
        val data = ByteArray(size)
        if (stream.read(data) != size) return null
        val encoding = data[0].toInt()
        val charset = when (encoding) {
            1 -> "UTF-16"
            2 -> "UTF-16BE"
            3 -> "UTF-8"
            else -> "ISO-8859-1"
        }
        return try {
            String(data, 1, size - 1, java.nio.charset.Charset.forName(charset)).trim().replace("\u0000", "")
        } catch (_: Exception) {
            null
        }
    }

    private fun readApic(stream: InputStream, size: Int): ByteArray? {
        val data = ByteArray(size)
        if (stream.read(data) != size) return null
        try {
            val encoding = data[0].toInt()
            var offset = 1
            // Skip mime type
            while (offset < size && data[offset] != 0.toByte()) offset++
            offset++ // skip null
            // Skip picture type
            offset++
            // Skip description
            if (encoding == 1 || encoding == 2) {
                while (offset + 1 < size && (data[offset] != 0.toByte() || data[offset+1] != 0.toByte())) offset += 2
                offset += 2
            } else {
                while (offset < size && data[offset] != 0.toByte()) offset++
                offset++
            }
            
            if (offset < size) {
                return data.copyOfRange(offset, size)
            }
        } catch (_: Exception) {}
        return null
    }
}
