package com.pnam.mymp3.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Tag data pulled out of an audio file. */
data class AudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkPath: String?,
)

/**
 * Reads ID3 tags and embedded artwork for a file we have been handed a URI to.
 *
 * The framework [MediaMetadataRetriever] is deprecated on API 35+ but remains the one API that
 * covers every container ExoPlayer can play, so it stays for now. Missing tags are not fatal —
 * we fall back to the file name so the track still appears in the list and keeps playing.
 */
@Suppress("DEPRECATION")
object AudioMetadataReader {

    private const val ARTWORK_DIR = "artwork"
    private const val ARTWORK_MAX_PX = 480

    fun read(context: Context, uri: Uri): AudioMetadata {
        val appContext = context.applicationContext
        val fallbackTitle = displayName(appContext, uri)

        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSourceCompat(appContext, uri)
                AudioMetadata(
                    title = retriever.stringOrNull(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallbackTitle,
                    artist = retriever.stringOrNull(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                    album = retriever.stringOrNull(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                    durationMs = retriever.stringOrNull(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L,
                    artworkPath = cacheArtwork(appContext, uri, retriever.embeddedPicture),
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrElse { error ->
            // Tags are decoration. ExoPlayer opens the file its own way and raises a real error if
            // the file itself is unusable, so unreadable tags must never stop playback — the old
            // nullable contract did exactly that and the app looked dead on files that played fine.
            Log.w(TAG_READ, "Falling back to file name for $uri", error)
            AudioMetadata(fallbackTitle, "", "", 0L, null)
        }
    }

    private const val TAG_READ = "AudioMetadataReader"

    /**
     * setDataSource(Context, Uri) resolves content: grants; for a file: URI the framework wants the
     * plain path (passing the URI string back to it, or a path it will not open through
     * ContentResolver, fails with FileNotFoundException even though the file plays).
     */
    private fun MediaMetadataRetriever.setDataSourceCompat(context: Context, uri: Uri) {
        val filePath = uri.takeIf { it.scheme == "file" }?.path
        if (filePath != null) setDataSource(filePath) else setDataSource(context, uri)
    }

    private fun MediaMetadataRetriever.stringOrNull(key: Int): String? =
        runCatching { extractMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Resolves a human-readable name, then caches any embedded picture as a downscaled PNG. */
    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            val fromProvider = runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
            if (!fromProvider.isNullOrBlank()) return fromProvider
        }
        val lastSegment = uri.lastPathSegment ?: return uri.toString()
        return lastSegment
            .substringAfterLast(':') // "primary:Music/Song.mp3" from DocumentsProvider URIs
            .substringAfterLast('/')
            .ifBlank { uri.toString() }
    }

    private fun cacheArtwork(context: Context, uri: Uri, picture: ByteArray?): String? {
        if (picture == null || picture.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)

            val bitmap = BitmapFactory.decodeByteArray(
                picture,
                0,
                picture.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) },
            ) ?: return null

            val directory = File(context.cacheDir, ARTWORK_DIR).apply { mkdirs() }
            val file = File(directory, "${md5(uri.toString())}.png")
            if (!file.exists()) {
                FileOutputStream(file).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                }
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun sampleSizeFor(width: Int, height: Int, maxPx: Int = ARTWORK_MAX_PX): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxPx || height / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
