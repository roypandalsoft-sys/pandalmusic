package com.pandal.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object LocalAudioRepository {
    fun load(context: Context): List<MediaItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val albumArtBase = Uri.parse("content://media/external/audio/albumart")

        return buildList {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val artworkUri = ContentUris.withAppendedId(albumArtBase, albumId)
                    val title = cursor.getString(titleCol) ?: "Sin título"
                    val artist = cursor.getString(artistCol) ?: "Desconocido"

                    add(
                        MediaItem.Builder()
                            .setMediaId(id.toString())
                            .setUri(uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setArtworkUri(artworkUri)
                                    .build()
                            )
                            .build()
                    )
                }
            }
        }
    }
}
