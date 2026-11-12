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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
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
 * Two details decide whether the result looks like a waveform or like a flat block:
 *
 *  - **What is measured per bar.** The loudest sample alone (the usual shortcut) reports transients
 *    and hides level changes, so a master limited near 0 dBFS renders as one tall rectangle. Each
 *    bar here is half peak and half RMS — the transient shape plus the loudness a listener actually
 *    hears — which measures ~1.8x more variation on the same audio.
 *  - **What it is normalised against.** Dividing by the single loudest sample lets one snare hit
 *    flatten every other bar, so the values are stretched across the track's own 2nd–98th
 *    percentile instead.
 *
 * A file that cannot be decoded (an expired content grant, a codec the device lacks) gets
 * [synthetic] instead, so the scrubber is never blank and never changes between two visits to the
 * same track.
 */
object WaveformExtractor {

    /** Bars across the width of the scrubber. */
    const val BARS = 240

    private const val DIRECTORY = "waveform"
    private const val CACHE_TAG = "v2"
    private const val TIMEOUT_US = 10_000L
    private const val FULL_SCALE = 32767f

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
     * file cannot be decoded at all. Two detuned harmonics plus per-bar jitter, so it reads as a
     * quiet envelope with texture rather than as flat static or a slow blob.
     */
    fun synthetic(uri: String): FloatArray {
        val seed = md5Bytes(uri).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val random = Random(seed)
        val first = 1 + random.nextInt(3)
        val second = 4 + random.nextInt(6)
        val phaseA = random.nextFloat() * 2f * PI.toFloat()
        val phaseB = random.nextFloat() * 2f * PI.toFloat()
        return FloatArray(BARS) { index ->
            val x = index.toFloat() / BARS
            val envelope = 0.52f +
                0.24f * sin(2f * PI.toFloat() * first * x + phaseA) +
                0.16f * sin(2f * PI.toFloat() * second * x + phaseB)
            (envelope + random.nextFloat() * 0.14f).coerceIn(0.08f, 1f)
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

            val peaks = IntArray(BARS)
            val energy = DoubleArray(BARS)
            val samples = IntArray(BARS)
            val codec = MediaCodec.createDecoderByType(mime)
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
                            fold(bytes, channels, durationUs, bufferInfo.presentationTimeUs, peaks, energy, samples)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            return profile(peaks, energy, samples)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** One decoded buffer: its loudest sample and its total energy, in the bucket it plays at. */
    private fun fold(
        bytes: ByteArray,
        channels: Int,
        durationUs: Long,
        presentationTimeUs: Long,
        peaks: IntArray,
        energy: DoubleArray,
        samples: IntArray,
    ) {
        val bucket = ((presentationTimeUs * peaks.size) / durationUs)
            .toInt()
            .coerceIn(0, peaks.size - 1)
        var loudest = 0
        var counted = 0
        var sum = 0.0
        var index = 0
        val frameBytes = channels * 2 // 16-bit samples, channels interleaved; the first one is enough.
        while (index + 1 < bytes.size) {
            val sample = (((bytes[index + 1].toInt() and 0xFF) shl 8) or (bytes[index].toInt() and 0xFF))
                .toShort()
            val amplitude = abs(sample.toInt()).coerceAtMost(FULL_SCALE.toInt())
            if (amplitude > loudest) loudest = amplitude
            sum += amplitude.toDouble() * amplitude
            counted++
            index += frameBytes
        }
        if (counted == 0) return
        if (loudest > peaks[bucket]) peaks[bucket] = loudest
        energy[bucket] += sum
        samples[bucket] += counted
    }

    /** Half peak, half RMS, stretched from the track's 2nd to its 98th percentile. */
    private fun profile(peaks: IntArray, energy: DoubleArray, samples: IntArray): FloatArray {
        val raw = FloatArray(peaks.size) { index ->
            val counted = samples[index].coerceAtLeast(1)
            val peak = peaks[index] / FULL_SCALE
            val rms = sqrt(energy[index] / counted).toFloat().coerceAtMost(FULL_SCALE) / FULL_SCALE
            0.5f * peak + 0.5f * rms
        }
        val sorted = raw.sortedArray()
        val floor = sorted[(sorted.size * 0.02).toInt().coerceAtMost(sorted.size - 1)]
        val ceiling = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        val span = (ceiling - floor).coerceAtLeast(0.05f)
        return FloatArray(raw.size) { ((raw[it] - floor) / span).coerceIn(0.08f, 1f) }
    }

    private fun read(file: File): FloatArray? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size % 4 != 0) return@runCatching null
        FloatArray(bytes.size / 4) { index ->
            ByteBuffer.wrap(bytes, index * 4, 4).order(ByteOrder.BIG_ENDIAN).float
        }
    }.getOrNull()?.takeIf { it.size == BARS }

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
