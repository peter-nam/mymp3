package com.pnam.mymp3.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pnam.mymp3.R
import com.pnam.mymp3.data.Track
import com.pnam.mymp3.player.PlaybackUiState
import com.pnam.mymp3.ui.common.rememberImageBitmap
import com.pnam.mymp3.ui.common.rememberWaveform
import com.pnam.mymp3.util.formatClockTime

/**
 * The now-playing screen.
 *
 * Deliberately sparse: the top half of the screen is empty on purpose and everything the listener
 * needs — identity, position, transport — sits at the bottom, where it is also reachable one-handed.
 *
 * The scrubber is the track's own waveform instead of a plain bar: peaks decoded from the file, lit
 * in the accent colour up to the playhead, so progress reads at a glance and tapping anywhere on it
 * seeks. The transport row uses the standard skip glyphs; the -10s / +30s meaning they carry here is
 * set by [com.pnam.mymp3.player.SessionCallback], which is the same code the earbuds trigger.
 */
@Composable
fun PlayerScreen(
    state: PlaybackUiState,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onDismissError: () -> Unit,
) {
    val track = state.currentTrack ?: return
    val peaks = rememberWaveform(track.uri)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp),
    ) {
        TopBar(onBack = onBack, onStop = onStop)

        // All the whitespace, kept where nothing needs to be.
        Spacer(Modifier.weight(1f))

        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = onDismissError)
            Spacer(Modifier.height(16.dp))
        }

        Artwork(track)
        TitleAndArtist(track)

        Spacer(Modifier.height(28.dp))
        WaveformScrubber(
            peaks = peaks,
            fraction = state.progress,
            onSeek = { fraction -> onSeek((fraction * state.durationMs).toLong()) },
            enabled = state.durationMs > 0L,
        )
        Spacer(Modifier.height(10.dp))
        TimesRow(state)
        Spacer(Modifier.height(24.dp))
        TransportRow(
            isPlaying = state.isPlaying,
            onPlayPause = onPlayPause,
            onSkipForward = onSkipForward,
            onSkipBackward = onSkipBackward,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onStop: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.back),
                tint = tint,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onStop) {
            Icon(
                painter = painterResource(R.drawable.ic_stop),
                contentDescription = stringResource(R.string.stop),
                tint = tint,
            )
        }
    }
}

/** Shown only when the file carries artwork — an untagged mp3 gets text, not a grey placeholder. */
@Composable
private fun Artwork(track: Track) {
    val path = track.artworkPath ?: return
    val bitmap = rememberImageBitmap(path) ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(16.dp)),
        contentScale = ContentScale.Crop,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun TitleAndArtist(track: Track) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (track.artist.isBlank()) stringResource(R.string.unknown_artist) else track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The waveform, doubling as the progress bar.
 *
 * Every bar is drawn in the muted colour first and the played slice is drawn again clipped to the
 * exact position, so the bar under the playhead fills gradually instead of snapping on whole bars.
 */
@Composable
private fun WaveformScrubber(
    peaks: FloatArray,
    fraction: Float,
    onSeek: (Float) -> Unit,
    enabled: Boolean,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val remainingColor = MaterialTheme.colorScheme.outlineVariant

    val clickable = if (enabled) {
        Modifier.pointerInput(peaks.size) {
            detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(clickable),
    ) {
        if (peaks.isEmpty()) return@Canvas
        val step = size.width / peaks.size
        val barWidth = (step * 0.6f).coerceAtLeast(1f)
        val corner = CornerRadius(barWidth / 2f)

        bars(peaks, step, barWidth, corner, remainingColor)
        clipRect(right = size.width * fraction.coerceIn(0f, 1f)) {
            bars(peaks, step, barWidth, corner, playedColor)
        }
    }
}

private fun DrawScope.bars(
    peaks: FloatArray,
    step: Float,
    barWidth: Float,
    corner: CornerRadius,
    color: Color,
) {
    val shortestBar = 2.dp.toPx()
    peaks.forEachIndexed { index, peak ->
        val barHeight = (peak * size.height).coerceAtLeast(shortestBar)
        drawRoundRect(
            color = color,
            topLeft = Offset(index * step, (size.height - barHeight) / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = corner,
        )
    }
}

@Composable
private fun TimesRow(state: PlaybackUiState) {
    val style = MaterialTheme.typography.labelMedium
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatClockTime(state.positionMs), style = style, color = tint)
        Text(formatClockTime(state.durationMs), style = style, color = tint)
    }
}

/**
 * Standard glyphs, sized by role: the two skip buttons are quiet, play/pause is the only filled,
 * accent-coloured control on the screen.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipBackward, modifier = Modifier.size(52.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = stringResource(R.string.skip_back_10),
                modifier = Modifier.size(26.dp),
                tint = onSurface,
            )
        }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }

        IconButton(onClick = onSkipForward, modifier = Modifier.size(52.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.skip_forward_30),
                modifier = Modifier.size(26.dp),
                tint = onSurface,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}
