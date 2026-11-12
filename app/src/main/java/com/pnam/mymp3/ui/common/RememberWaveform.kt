package com.pnam.mymp3.ui.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.pnam.mymp3.data.WaveformExtractor

/**
 * The waveform for a track.
 *
 * Starts as the deterministic shape derived from the URI so the scrubber is never empty, then
 * becomes the decoded peaks as soon as [WaveformExtractor] has them (instant from the second visit
 * onwards, since they are cached on disk).
 */
@Composable
fun rememberWaveform(uri: String?): FloatArray {
    val context = LocalContext.current
    return produceState(
        initialValue = uri?.let(WaveformExtractor::synthetic) ?: FloatArray(0),
        key1 = uri,
    ) {
        value = uri?.let { WaveformExtractor.peaks(context, Uri.parse(it)) } ?: FloatArray(0)
    }.value
}
