package com.pnam.mymp3.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The shape of a track: [BARS] normalised amplitudes in 0f..1f, which the player draws as its
 * scrubber.
 *
 * The peaks come from decoding the file to PCM with [MediaCodec] — the only way to get a track's
 * real waveform without shipping a native library. Results are cached in a file beside the artwork
 * cache, so a track is decoded once and the scrubber is instant afterwards.
 *
 * A file that cannot be decoded (an expired content grant, a codec the device lacks) gets
 * [synthetic] instead, so the scrubber is never blank and never changes between two visits to the
 * same track.
 */
object WaveformExtractor {

    /** Bars across the width of the scrubber. */
    const val BARS = 96

    private const val DIRECTORY = "waveform"
    private const val CACHE_TAG = "v1"
    private const val TIMEOUT_US = 10_000L

    /** Peaks for [uri]: the cache if it has them, otherwise the decoded file, otherwise [synthetic]. */
    suspend fun peaks(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cacheFile = File(File(appContext.cacheDir, DIRECTORY), "$CACHE_TAG-${md5(uri.toString())}.bin")
        read(cacheFile)?.let { return@withContext it }

        val decoded = runCatching { decode(appContext, uri) }.getOrNull() ?: synthetic(uri.toString())
        write(cacheFile, decoded)
        decoded
    }

    /**
     * A waveform that depends only on the URI: shown while the real one decodes, and kept when a
     * file cannot be decoded at all. A random walk seeded by the hash, so it reads like audio
     * rather than the flat static of independent samples.
     */
    fun synthetic(uri: String): FloatArray {
        val seed = md5Bytes(uri).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val random = Random(seed)
        var previous = 0.55f
        return FloatArray(BARS) {
            previous = (previous + (random.nextFloat() - 0.5f) * 0.4f).coerceIn(0.15f, 1f)
            previous
        }
    }

    /** Decodes to 16-bit PCM and folds the samples into [BARS] buckets by playback time. */
    private fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        // Same rule as AudioMetadataReader: a file: URI has to be handed over as a plain path.
        when (uri.scheme) {
            null, "file" -> extractor.setDataSource(requireNotNull(uri.path) { "no path in $uri" })
            else -> extractor.setDataSource(context, uri, null)
        }

        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track in $uri")
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) { "no mime in $uri" }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } else {
                1
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (durationUs <= 0L) throw IOException("no duration for $uri")

            val codec = MediaCodec.createDecoderByType(mime)
            val peaks = FloatArray(BARS)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var inputDone = false
                var outputDone = false
                val bufferInfo = MediaCodec.BufferInfo()
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val size = codec.getInputBuffer(inputIndex)
                                ?.let { extractor.readSampleData(it, 0) }
                                ?: -1
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && bufferInfo.size > 0) {
                            val bytes = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            buffer.get(bytes)
                            fold(bytes, channels, durationUs, bufferInfo.presentationTimeUs, peaks)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val loudest = peaks.maxOrNull() ?: 0f
            if (loudest <= 0f) return FloatArray(BARS) { 0.15f }
            return FloatArray(peaks.size) { (peaks[it] / loudest).coerceIn(0f, 1f) }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** The loudest sample of one decoded buffer, dropped into the bucket that buffer plays at. */
    private fun fold(
        bytes: ByteArray,
        channels: Int,
        durationUs: Long,
        presentationTimeUs: Long,
        peaks: FloatArray,
    ) {
        val bucket = ((presentationTimeUs * peaks.size) / durationUs).toInt().coerceIn(0, peaks.size - 1)
        var loudest = 0
        var index = 0
        val frameBytes = channels * 2 // 16-bit samples, channels interleaved; the first one is enough.
        while (index + 1 < bytes.size) {
            val sample = ((bytes[index + 1].toInt() and 0xFF) shl 8) or (bytes[index].toInt() and 0xFF)
            loudest = max(loudest, abs(sample.toShort().toInt()))
            index += frameBytes
        }
        val value = (loudest.coerceAtMost(32767)) / 32767f
        if (value > peaks[bucket]) peaks[bucket] = value
    }

    private fun read(file: File): FloatArray? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size % 4 != 0) return@runCatching null
        FloatArray(bytes.size / 4) { index ->
            ByteBuffer.wrap(bytes, index * 4, 4).order(ByteOrder.BIG_ENDIAN).float
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun write(file: File, peaks: FloatArray) = runCatching {
        file.parentFile?.mkdirs()
        val buffer = ByteBuffer.allocate(peaks.size * 4).order(ByteOrder.BIG_ENDIAN)
        peaks.forEach { buffer.putFloat(it) }
        file.writeBytes(buffer.array())
    }

    private fun md5(value: String): String = md5Bytes(value).joinToString("") { "%02x".format(it) }

    private fun md5Bytes(value: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
}
