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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the player needs to draw a track: its shape, and the tempo it should pulse to. */
@Serializable
data class Waveform(val bars: FloatArray, val bpm: Float)

/**
 * The shape and tempo of a track, from one decode of the file.
 *
 * The bars come from decoding the file to PCM with [MediaCodec] — the only way to get a track's
 * real waveform without shipping a native library — and the tempo from that same PCM, so nothing
 * is read twice. Results are cached in a file beside the artwork cache, so a track is decoded once
 * and the scrubber is instant afterwards.
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
 *  - **How the finished profile is shaped.** Each bar covers 0.68 s of audio, so neighbouring bars
 *    measure genuinely different moments and the raw profile steps between them. A Gaussian pass
 *    ([SMOOTH_SIGMA_BARS]) turns those steps into peaks and valleys without touching the spread.
 *    See [smooth].
 *
 * A file that cannot be decoded (an expired content grant, a codec the device lacks) gets
 * [synthetic] instead, so the scrubber is never blank and never changes between two visits to the
 * same track.
 */
object WaveformExtractor {

    /** Bars across the width of the scrubber. */
    const val BARS = 240

    /** Tempo used when a file cannot be decoded: mid-tempo, the safest guess there is. */
    const val FALLBACK_BPM = 120f

    private const val DIRECTORY = "waveform"
    private const val CACHE_TAG = "v4"
    private const val TIMEOUT_US = 10_000L
    private const val FULL_SCALE = 32767f
    private const val FULL_SCALE_INT = 32767

    /** PCM samples per loudness frame, giving ~86 frames per second at 44.1 kHz. */
    private const val FRAME_SAMPLES = 512

    /**
     * Smoothing radius for the finished profile, in bars.
     *
     * A bar is a 0.68 s slice of the track, so two neighbouring bars measure genuinely different
     * moments and the raw profile steps by ~0.12 between them — which is what makes the drawn
     * waveform read as a row of blocks rather than as a shape. A Gaussian pass smooths the steps
     * away without flattening the track: on the reference file the neighbour-to-neighbour step
     * drops from 0.121 to 0.028 while the spread is untouched (stdev 0.193 → 0.203), so the
     * dynamics the previous fix added are all still there.
     */
    private const val SMOOTH_SIGMA_BARS = 2f

    private val json = Json { explicitNulls = false }

    /** The track's waveform: the cache if it has one, otherwise the decoded file, otherwise [synthetic]. */
    suspend fun load(context: Context, uri: Uri): Waveform = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cacheFile = File(File(appContext.cacheDir, DIRECTORY), "$CACHE_TAG-${md5(uri.toString())}.json")
        read(cacheFile)?.let { return@withContext it }

        val decoded = runCatching { decode(appContext, uri) }.getOrNull() ?: synthetic(uri.toString())
        write(cacheFile, decoded)
        decoded
    }

    /**
     * A waveform that depends only on the URI: shown while the real one decodes, and kept when a
     * file cannot be decoded at all. Two detuned harmonics plus smoothed jitter, so it reads as a
     * quiet envelope with texture rather than as flat static or a slow blob.
     */
    fun synthetic(uri: String): Waveform {
        val random = Random(seedOf(uri))
        val first = 1 + random.nextInt(3)
        val second = 4 + random.nextInt(6)
        val phaseA = random.nextFloat() * 2f * PI.toFloat()
        val phaseB = random.nextFloat() * 2f * PI.toFloat()
        val jitter = FloatArray(BARS) { random.nextFloat() }
        val shaped = smooth(
            FloatArray(BARS) { index ->
                val x = index.toFloat() / BARS
                0.52f +
                    0.24f * sin(2f * PI.toFloat() * first * x + phaseA) +
                    0.16f * sin(2f * PI.toFloat() * second * x + phaseB) +
                    jitter[index] * 0.14f
            },
            sigma = 1.5f,
        )
        val bars = FloatArray(shaped.size) { shaped[it].coerceIn(0.08f, 1f) }
        // 84–138 BPM: the range a wrong guess still looks plausible in.
        return Waveform(bars, 84f + random.nextInt(55))
    }

    /** Decodes to 16-bit PCM once and takes both the bar profile and the tempo envelope from it. */
    private fun decode(context: Context, uri: Uri): Waveform {
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
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
            } else {
                44_100
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (durationUs <= 0L) throw IOException("no duration for $uri")

            val analysis = PcmAnalysis(BARS, FRAME_SAMPLES)
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
                            analysis.addBuffer(bytes, channels, durationUs, bufferInfo.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val bpm = TempoEstimator.estimate(analysis.envelope(), sampleRate.toDouble() / FRAME_SAMPLES)
                ?: FALLBACK_BPM
            return Waveform(bars = analysis.profile(), bpm = bpm)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * One pass over the decoded PCM, filling two things at once: the [BARS] buckets the scrubber
     * draws, and the loudness envelope the tempo is derived from.
     */
    private class PcmAnalysis(private val bars: Int, private val frameSize: Int) {

        private val peaks = IntArray(bars)
        private val bucketEnergy = DoubleArray(bars)
        private val bucketSamples = IntArray(bars)
        private val frames = ArrayList<Float>(1 shl 14)
        private var frameEnergy = 0.0
        private var frameCount = 0

        /** One decoder output buffer: its samples, and where in the track and time it plays. */
        fun addBuffer(bytes: ByteArray, channels: Int, durationUs: Long, presentationTimeUs: Long) {
            val bucket = ((presentationTimeUs * bars) / durationUs).toInt().coerceIn(0, bars - 1)
            var loudest = 0
            var counted = 0
            var sum = 0.0
            var index = 0
            val frameBytes = channels * 2 // 16-bit samples, channels interleaved; the first one is enough.
            while (index + 1 < bytes.size) {
                val sample = (((bytes[index + 1].toInt() and 0xFF) shl 8) or (bytes[index].toInt() and 0xFF))
                    .toShort()
                val amplitude = abs(sample.toInt()).coerceAtMost(FULL_SCALE_INT)
                if (amplitude > loudest) loudest = amplitude
                val energy = amplitude.toDouble() * amplitude
                sum += energy
                counted++
                frameEnergy += energy
                frameCount++
                if (frameCount == frameSize) {
                    frames.add(sqrt(frameEnergy / frameCount).toFloat())
                    frameEnergy = 0.0
                    frameCount = 0
                }
                index += frameBytes
            }
            if (counted == 0) return
            if (loudest > peaks[bucket]) peaks[bucket] = loudest
            bucketEnergy[bucket] += sum
            bucketSamples[bucket] += counted
        }

        /** The loudness envelope, one value per [frameSize] samples. */
        fun envelope(): FloatArray {
            if (frameCount > 0) {
                frames.add(sqrt(frameEnergy / frameCount).toFloat())
                frameEnergy = 0.0
                frameCount = 0
            }
            return FloatArray(frames.size) { frames[it] }
        }

        /** Half peak, half RMS, smoothed, then stretched from the track's 2nd to its 98th percentile. */
        fun profile(): FloatArray {
            val raw = FloatArray(bars) { index ->
                val counted = bucketSamples[index].coerceAtLeast(1)
                val peak = peaks[index] / FULL_SCALE
                val rms = sqrt(bucketEnergy[index] / counted).toFloat().coerceAtMost(FULL_SCALE) / FULL_SCALE
                0.5f * peak + 0.5f * rms
            }
            val smoothed = smooth(raw, SMOOTH_SIGMA_BARS)
            val sorted = smoothed.sortedArray()
            val floor = sorted[(sorted.size * 0.02).toInt().coerceAtMost(sorted.size - 1)]
            val ceiling = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
            val span = (ceiling - floor).coerceAtLeast(0.05f)
            return FloatArray(smoothed.size) { ((smoothed[it] - floor) / span).coerceIn(0.08f, 1f) }
        }
    }

    private fun read(file: File): Waveform? = runCatching {
        json.decodeFromString(Waveform.serializer(), file.readText())
    }.getOrNull()?.takeIf { it.bars.isNotEmpty() }

    private fun write(file: File, waveform: Waveform) = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Waveform.serializer(), waveform))
    }

    /**
     * A Gaussian pass over [values], renormalised at the ends so the first and last bars do not
     * sag towards nothing.
     *
     * It runs on the finished profile rather than on the PCM: the profile is what gets drawn, so
     * it is what decides whether the scrubber reads as a shape or as a row of blocks. Widening the
     * buckets instead would smooth the same way, but it would also average the track's dynamics
     * away — this softens the outline and keeps the spread.
     */
    private fun smooth(values: FloatArray, sigma: Float): FloatArray {
        val n = values.size
        if (n < 3) return values
        val radius = max(1, (sigma * 3f).toInt())
        val kernel = FloatArray(radius * 2 + 1) { index ->
            val offset = (index - radius).toFloat()
            exp(-(offset * offset) / (2f * sigma * sigma))
        }
        return FloatArray(n) { index ->
            var sum = 0.0
            var weight = 0.0
            for (k in kernel.indices) {
                val source = index + k - radius
                if (source in 0 until n) {
                    sum += values[source] * kernel[k]
                    weight += kernel[k]
                }
            }
            (sum / weight).toFloat()
        }
    }

    private fun seedOf(value: String): Long =
        md5Bytes(value).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }

    private fun md5(value: String): String =
        md5Bytes(value).joinToString("") { "%02x".format(it) }

    private fun md5Bytes(value: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
}
