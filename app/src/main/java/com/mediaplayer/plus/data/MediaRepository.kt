package com.mediaplayer.plus.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        scanSongs(null)
    }

    suspend fun getSongsInFolders(folders: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (folders.isEmpty()) {
            emptyList()
        } else {
            val selection = folders.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            val selectionArgs = folders.map { "$it/%" }.toTypedArray()
            scanSongs(selection to selectionArgs)
        }
    }

    private fun scanSongs(filter: Pair<String, Array<String>>? = null): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
            val (selection, selectionArgs) = filter ?: (null to null)
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val albumArtUriBase = Uri.parse("content://media/external/audio/albumart")
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: ""
                        val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() } ?: ""
                        val album = cursor.getString(albumColumn) ?: "Unknown"
                        val albumId = cursor.getLong(albumIdColumn)
                        val duration = cursor.getLong(durationColumn)
                        val path = cursor.getString(dataColumn) ?: ""
                        if (!File(path).exists()) continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val albumArtUri = ContentUris.withAppendedId(albumArtUriBase, albumId).toString()
                        songs.add(Song(id, title, contentUri, path, duration, artist, album, albumId, albumArtUri))
                    }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Error scanning songs: ${e.message}")
        }
        return songs
    }

    suspend fun getAllVideos(): List<Video> = withContext(Dispatchers.IO) {
        scanVideos(null)
    }

    suspend fun getVideosInFolders(folders: List<String>): List<Video> = withContext(Dispatchers.IO) {
        if (folders.isEmpty()) {
            emptyList()
        } else {
            val selection = folders.joinToString(" OR ") { "${MediaStore.Video.Media.DATA} LIKE ?" }
            val selectionArgs = folders.map { "$it/%" }.toTypedArray()
            scanVideos(selection to selectionArgs)
        }
    }

    private fun scanVideos(filter: Pair<String, Array<String>>? = null): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA
            )
            val (selection, selectionArgs) = filter ?: (null to null)
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val title = cursor.getString(titleColumn) ?: "Unknown"
                        val duration = cursor.getLong(durationColumn)
                        val size = cursor.getLong(sizeColumn)
                        val path = cursor.getString(dataColumn) ?: ""
                        if (!File(path).exists()) continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        videos.add(Video(id, title, contentUri, path, duration, size))
                    }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Error scanning videos: ${e.message}")
        }
        return videos
    }
}
