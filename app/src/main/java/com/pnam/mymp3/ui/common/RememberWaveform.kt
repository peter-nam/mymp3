package com.pnam.mymp3.ui.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pnam.mymp3.data.Waveform
import com.pnam.mymp3.data.WaveformExtractor

/**
 * The waveform for [uri]: a placeholder derived from the URI on the first frame, replaced by the
 * measured one once the file is decoded.
 *
 * The placeholder exists because the player screen is drawn the moment a file is opened while the
 * decode takes a moment longer. Showing a shape that never changes is better than showing nothing,
 * and worse files still get a scrubber.
 */
@Composable
fun rememberWaveform(uri: String): Waveform {
    val context = LocalContext.current
    var waveform by remember(uri) { mutableStateOf(WaveformExtractor.synthetic(uri)) }

    LaunchedEffect(uri) {
        waveform = WaveformExtractor.load(context.applicationContext, Uri.parse(uri))
    }
    return waveform
}
