package com.pnam.mymp3.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pnam.mymp3.R
import com.pnam.mymp3.data.Track
import com.pnam.mymp3.data.Waveform
import com.pnam.mymp3.player.PlaybackUiState
import com.pnam.mymp3.ui.common.rememberImageBitmap
import com.pnam.mymp3.ui.common.rememberWaveform
import com.pnam.mymp3.util.formatClockTime
import kotlin.math.roundToInt

/** The now-playing screen. */
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
    val waveform = rememberWaveform(track.uri)

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
            waveform = waveform,
            fraction = state.progress,
            isPlaying = state.isPlaying,
            canSeek = state.durationMs > 0L,
            onSeek = { fraction -> onSeek((fraction * state.durationMs).toLong()) },
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
 * It breathes on the beat: every bar grows by [PulseAmount] on each beat the track's estimated tempo
 * puts at that moment, with a fast attack and a slower fall back down, so the shape moves with the
 * music instead of sitting still. The pulse stops when playback stops.
 *
 * The colour drifts slowly around [EtherealRamp] — violet through sky, mint, the brand amber and
 * rose — offset along the bar axis so the gradient runs across the waveform rather than flashing
 * as one flat colour. The part already heard is drawn at full strength and the part still to come
 * at [UnplayedAlpha], so progress stays readable while the whole thing shifts.
 */
@Composable
private fun WaveformScrubber(
    waveform: Waveform,
    fraction: Float,
    isPlaying: Boolean,
    canSeek: Boolean,
    onSeek: (Float) -> Unit,
) {
    val bars = waveform.bars
    if (bars.isEmpty()) return

    val beatMs = (60_000f / waveform.bpm).roundToInt().coerceIn(MinBeatMs, MaxBeatMs)
    val animations = rememberInfiniteTransition(label = "waveform-motion")

    // One transition drives both animations, so the scrubber is redrawn from a single frame clock.
    val beat by animations.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = beatMs
                0f at 0 using LinearEasing
                1f at (beatMs * 0.14f).roundToInt() using LinearEasing // quick attack
                0f at beatMs using LinearEasing                        // slow release
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "beat",
    )
    val hue by animations.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HueCycleMs, easing = LinearEasing), RepeatMode.Restart),
        label = "hue",
    )

    val clickable = if (canSeek) {
        Modifier.pointerInput(bars.size) {
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
        val step = size.width / bars.size
        val barWidth = (step * 0.55f).coerceAtLeast(1f)
        val corner = CornerRadius(barWidth / 2f)
        val tallestBar = size.height * 0.92f // headroom, so a beat peak still fits
        val pulse = if (isPlaying) beat else 0f

        // Everything still to come, dimmed, so the shape reads past the playhead.
        bars.forEachIndexed { index, amplitude ->
            val height = (amplitude * tallestBar * (1f + PulseAmount * pulse)).coerceAtMost(size.height)
            drawRoundRect(
                color = rampColour(hue, index, bars.size).copy(alpha = UnplayedAlpha),
                topLeft = Offset(index * step, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = corner,
            )
        }

        // The part already heard, drawn again at full strength and clipped to the exact position so
        // the bar under the playhead fills gradually instead of snapping to whole bars.
        clipRect(right = size.width * fraction.coerceIn(0f, 1f)) {
            bars.forEachIndexed { index, amplitude ->
                val height = (amplitude * tallestBar * (1f + PulseAmount * pulse)).coerceAtMost(size.height)
                drawRoundRect(
                    color = rampColour(hue, index, bars.size).copy(alpha = 0.82f + (1f - 0.82f) * pulse),
                    topLeft = Offset(index * step, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = corner,
                )
            }
        }
    }
}

private const val PulseAmount = 0.06f
private const val UnplayedAlpha = 0.22f
private const val HueCycleMs = 16_000
private const val MinBeatMs = 300
private const val MaxBeatMs = 1_000

/** Violet → sky → mint → the brand amber → rose, and back to violet. */
private val EtherealRamp: List<Color> = buildRamp()

/**
 * The closed gradient sampled once at class load. Sampling it up front means a frame picks a colour
 * per bar with an index instead of building a 240-element array of lerps 60 times a second.
 */
private fun buildRamp(): List<Color> {
    val stops = listOf(
        Color(0xFF6D5BFF), // violet
        Color(0xFF3FC1F0), // sky
        Color(0xFF39E6B4), // mint
        Color(0xFFFFC15C), // the brand amber
        Color(0xFFFF6FA0), // rose
    )
    val perStop = 24
    return buildList(stops.size * perStop) {
        stops.forEachIndexed { index, from ->
            val to = stops[(index + 1) % stops.size]
            for (step in 0 until perStop) {
                add(lerp(from, to, step.toFloat() / perStop))
            }
        }
    }
}

/** The ramp colour for [index] of [count] bars, at [phase] of the drift cycle. */
private fun rampColour(phase: Float, index: Int, count: Int): Color {
    val slots = EtherealRamp.size
    return EtherealRamp[(((phase + index.toFloat() / count) % 1f) * slots).toInt() % slots]
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
