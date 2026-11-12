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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The now-playing screen.
 *
 * Everything that moves on it — the waveform's thump, the play button's thump, the colour that
 * flows across both — reads from one clock ([rememberEtherealMotion]), so the screen animates as
 * one thing rather than as several elements ticking out of step with each other.
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
    val waveform = rememberWaveform(track.uri)
    val motion = rememberEtherealMotion(waveform.bpm)

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
            motion = motion,
            onSeek = { fraction -> onSeek((fraction * state.durationMs).toLong()) },
        )
        Spacer(Modifier.height(10.dp))
        TimesRow(state)
        Spacer(Modifier.height(24.dp))
        TransportRow(
            isPlaying = state.isPlaying,
            motion = motion,
            onPlayPause = onPlayPause,
            onSkipForward = onSkipForward,
            onSkipBackward = onSkipBackward,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The screen's animation clock: one beat pulse and one colour flow.
 *
 * The beat period comes from the track's estimated tempo, so the pulse is the song's tempo rather
 * than a generic breathing rate. The flow is a slow 0..1 sweep that drives the colour mask.
 *
 * Both are handed out as [State] objects and never read here. If the screen read them, every frame
 * would recompose the title, the times and the buttons; as written, only the two elements that
 * actually read them recompose.
 */
@Stable
private class EtherealMotion(val beatMs: Int, val beat: State<Float>, val flow: State<Float>)

@Composable
private fun rememberEtherealMotion(bpm: Float): EtherealMotion {
    val beatMs = (60_000f / bpm).roundToInt().coerceIn(MinBeatMs, MaxBeatMs)
    val clock = rememberInfiniteTransition(label = "ethereal")
    return EtherealMotion(
        beatMs = beatMs,
        // Fast attack, slow release: a thump, not a fade.
        beat = clock.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = beatMs
                    0f at 0 using LinearEasing
                    1f at (beatMs * AttackFraction).roundToInt() using LinearEasing
                    0f at beatMs using LinearEasing
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "beat",
        ),
        flow = clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(FlowCycleMs, easing = LinearEasing), RepeatMode.Restart),
            label = "flow",
        ),
    )
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
 * One closed shape rather than a row of bars: the amplitudes are drawn as a curve above the centre
 * line and mirrored below it, so the track's shape reads as peaks and valleys. It swells on every
 * beat ([EtherealMotion.beat]) while playing, and the colour mask flows through it whether playing
 * or not.
 *
 * The part already heard is drawn at full strength and the part still to come at [UnplayedAlpha],
 * clipped to the exact position so the shape fills continuously instead of snapping to whole bars.
 */
@Composable
private fun WaveformScrubber(
    waveform: Waveform,
    fraction: Float,
    isPlaying: Boolean,
    canSeek: Boolean,
    motion: EtherealMotion,
    onSeek: (Float) -> Unit,
) {
    val bars = waveform.bars
    if (bars.size < 2) return
    val pulse = if (isPlaying) motion.beat.value else 0f
    val flow = motion.flow.value

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
        val path = waveformPath(bars, size.width, size.height, 1f + WaveformPulse * pulse)
        val brush = flowBrush(flow, size.width, size.height, WaveformStops)

        drawPath(path, brush, alpha = UnplayedAlpha)
        clipRect(right = size.width * fraction.coerceIn(0f, 1f)) {
            drawPath(path, brush, alpha = PlayedAlpha + (1f - PlayedAlpha) * pulse)
        }
    }
}

/**
 * The waveform as one closed shape: the amplitudes as a curve above the centre line, mirrored
 * below it.
 *
 * The outline is a Catmull-Rom spline written as cubic segments, so it curves *through* each
 * sample instead of cornering at it. Together with the smoothing the extractor applies to the
 * profile, that is what replaces a row of blocks with peaks and valleys.
 */
private fun waveformPath(bars: FloatArray, width: Float, height: Float, stretch: Float): Path {
    val count = bars.size
    val path = Path()
    if (count < 2 || width <= 0f || height <= 0f) return path

    val half = height / 2f
    val step = width / (count - 1)
    // One ring: the top edge walked left to right, then the mirrored bottom edge walked back.
    val points = count * 2
    val xs = FloatArray(points) { if (it < count) it * step else (points - 1 - it) * step }
    val ys = FloatArray(points) {
        val amplitude = (bars[if (it < count) it else points - 1 - it] * half * stretch).coerceIn(0f, half)
        if (it < count) half - amplitude else half + amplitude
    }

    path.moveTo(xs[0], ys[0])
    for (i in 0 until points) {
        val previous = if (i == 0) points - 1 else i - 1
        val next = (i + 1) % points
        val afterNext = (i + 2) % points
        path.cubicTo(
            xs[i] + (xs[next] - xs[previous]) / 6f,
            ys[i] + (ys[next] - ys[previous]) / 6f,
            xs[next] - (xs[afterNext] - xs[i]) / 6f,
            ys[next] - (ys[afterNext] - ys[i]) / 6f,
            xs[next],
            ys[next],
        )
    }
    path.close()
    return path
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
 * coloured control on the screen.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    motion: EtherealMotion,
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

        PlayPauseButton(isPlaying = isPlaying, motion = motion, onClick = onPlayPause)

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

/**
 * The one control that carries the accent: a disc painted with the same flowing mask as the
 * waveform, that swells on every beat and brightens its own glow as it does.
 *
 * The glyph stays white so it survives the whole ramp — the amber end of it would swallow a dark
 * one, and picking per colour would make the glyph flicker as the colour moves.
 */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, motion: EtherealMotion, onClick: () -> Unit) {
    val pulse = if (isPlaying) motion.beat.value else 0f
    val flow = motion.flow.value
    val accent = rampAt(flow)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(1f + GlowScale * pulse, 1f + GlowScale * pulse)
                .background(accent.copy(alpha = GlowIdle + GlowRange * pulse), CircleShape),
        )
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(76.dp)
                .scale(1f + ButtonScale * pulse, 1f + ButtonScale * pulse)
                .background(
                    brush = Brush.linearGradient(colors = List(ButtonStops) { flowingColour(flow, it.toFloat() / ButtonStops) }),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(34.dp),
                tint = Color.White,
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

// --- The look -----------------------------------------------------------------------------

private const val MinBeatMs = 300
private const val MaxBeatMs = 1_000
private const val AttackFraction = 0.14f
private const val FlowCycleMs = 7_000

private const val WaveformPulse = 0.06f
private const val ButtonScale = 0.045f
private const val GlowScale = 0.10f
private const val GlowIdle = 0.16f
private const val GlowRange = 0.22f

private const val UnplayedAlpha = 0.22f
private const val PlayedAlpha = 0.82f
private const val WaveformStops = 12
private const val ButtonStops = 6

/** Violet → sky → mint → the brand amber → rose, and back to violet. */
private val EtherealRamp: List<Color> = buildRamp()

/**
 * The closed gradient, sampled once at class load. Sampling it up front means a frame picks a
 * colour with an index instead of rebuilding the whole ramp 60 times a second.
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

/** The ramp colour at an absolute position on the closed gradient; [position] wraps past 1f. */
private fun rampAt(position: Float): Color {
    val slots = EtherealRamp.size
    val wrapped = (position % 1f + 1f) % 1f
    return EtherealRamp[(wrapped * slots).toInt() % slots]
}

/**
 * The colour mask: the ramp at [position], with a crest of light travelling through it.
 *
 * Sliding [flow] along the ramp is what makes the colour move. The crest — a sine on the same
 * clock — adds a bright band that travels with it, so the mask flows like liquid instead of just
 * changing hue in place.
 */
private fun flowingColour(flow: Float, position: Float): Color {
    val base = rampAt(flow + position)
    val crest = sin(2f * PI.toFloat() * (position - flow))
    return if (crest >= 0f) {
        lerp(base, Color.White, crest * 0.38f)
    } else {
        lerp(base, Color.Black, -crest * 0.28f)
    }
}

/**
 * A gradient across [stops] samples of the mask, on an axis that swings between the top and bottom
 * of the shape as the flow moves. The swing is at twice the rate of the colour slide, so the two
 * motions never lock into one mechanical sweep.
 */
private fun flowBrush(flow: Float, width: Float, height: Float, stops: Int): Brush {
    val tilt = sin(2f * PI.toFloat() * flow * 2f) * 0.45f
    return Brush.linearGradient(
        colors = List(stops) { flowingColour(flow, it.toFloat() / stops) },
        start = Offset(0f, height * (0.5f - tilt * 0.5f)),
        end = Offset(width, height * (0.5f + tilt * 0.5f)),
    )
}
