package com.pnam.mymp3.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
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
 * we fall back to the file name so the track still appears in the list. A file we cannot open at
 * all returns null, which is how the UI detects an expired content-URI grant.
 */
@Suppress("DEPRECATION")
object AudioMetadataReader {

    private const val ARTWORK_DIR = "artwork"
    private const val ARTWORK_MAX_PX = 480

    fun read(context: Context, uri: Uri): AudioMetadata? {
        val appContext = context.applicationContext
        val fallbackTitle = displayName(appContext, uri)

        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
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
            val goneLocally = uri.scheme == "file" && !File(uri.path.orEmpty()).exists()
            when {
                goneLocally -> null
                else -> AudioMetadata(fallbackTitle, "", "", 0L, null).also {
                    android.util.Log.w(TAG_READ, "Falling back to file name for $uri", error)
                }
            }
        }
    }

    private const val TAG_READ = "AudioMetadataReader"

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
