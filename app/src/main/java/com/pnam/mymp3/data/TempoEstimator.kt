package com.pnam.mymp3.data

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Estimates a track's tempo from a loudness envelope.
 *
 * The envelope is a sequence of short-window RMS values (PcmAnalysis builds one while decoding).
 * Its DC offset is removed and it is autocorrelated over every lag that could be a beat between
 * [MIN_BPM] and [MAX_BPM] — and the multiples of those lags. Candidate tempos are then scored by
 * summing the autocorrelation at a tempo's period *and its harmonics*, the tempoogram of
 * Ellis (2007) in a few lines.
 *
 * Summing harmonics is what keeps a 120 BPM track from being reported as 60 BPM. A plain
 * autocorrelation peaks at the true period *and* at every multiple of it, and the longest lag
 * often wins; a genuine 120 BPM tempo scores at 43, 86, 129 and 172 frames while a 60 BPM
 * candidate only explains 86, 172 and 258 — so the real tempo comes out on top.
 */
internal object TempoEstimator {

    private const val MIN_BPM = 60f
    private const val MAX_BPM = 180f
    private const val HARMONICS = 4
    private const val MIN_BPM_FOR_HARMONICS = 3

    /** Tempo in BPM, or null when the envelope is too short or too flat to be confident. */
    fun estimate(envelope: FloatArray, frameRate: Double): Float? {
        val n = envelope.size
        if (n < 32) return null

        val mean = envelope.average().toFloat()
        val x = FloatArray(n) { envelope[it] - mean }
        val totalEnergy = x.fold(0.0) { acc, v -> acc + v.toDouble() * v }
        if (totalEnergy <= 1e-9) return null

        val maxLag = ((60.0 / MIN_BPM * frameRate) * HARMONICS)
            .roundToInt()
            .coerceAtMost(n - 1)
        if (maxLag < 2) return null

        // Normalised autocorrelation: dividing by the energy of both windows stops long lags from
        // winning just because they average more samples.
        val acf = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var cross = 0.0
            var head = 0.0
            var tail = 0.0
            for (i in lag until n) {
                val a = x[i].toDouble()
                val b = x[i - lag].toDouble()
                cross += a * b
                head += a * a
                tail += b * b
            }
            val denominator = sqrt(head * tail)
            acf[lag] = if (denominator > 1e-9) cross / denominator else 0.0
        }

        var bestBpm = 0f
        var bestScore = Double.NEGATIVE_INFINITY
        var bpm = MIN_BPM.toDouble()
        while (bpm <= MAX_BPM) {
            val period = 60.0 / bpm * frameRate
            var score = 0.0
            var harmonics = 0
            for (k in 1..HARMONICS) {
                val lag = (period * k).roundToInt()
                if (lag > maxLag) break
                score += acf[lag] / k
                harmonics++
            }
            if (harmonics >= MIN_BPM_FOR_HARMONICS && score > bestScore) {
                bestScore = score
                bestBpm = bpm.toFloat()
            }
            bpm *= 1.01 // ~1 BPM resolution on a logarithmic grid
        }

        return bestBpm.takeIf { it > 0f }?.coerceIn(MIN_BPM, MAX_BPM)
    }
}
