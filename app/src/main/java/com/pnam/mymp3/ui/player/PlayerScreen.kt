package com.pnam.mymp3.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pnam.mymp3.R
import com.pnam.mymp3.data.Track
import com.pnam.mymp3.player.PlaybackUiState
import com.pnam.mymp3.ui.common.rememberImageBitmap
import com.pnam.mymp3.util.formatClockTime

/** The now-playing screen: artwork, scrubber, and the -10s / play / +30s control row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlaybackUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onDismissError: () -> Unit,
) {
    val track = state.currentTrack ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.errorMessage?.let { message ->
                ErrorBanner(message = message, onDismiss = onDismissError)
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(8.dp))
            Artwork(artworkPath = track.artworkPath, size = 240.dp)
            Spacer(Modifier.height(28.dp))

            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (track.artist.isBlank()) stringResource(R.string.unknown_artist) else track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))
            Scrubber(state = state, onSeek = onSeek)
            Spacer(Modifier.height(28.dp))
            Controls(
                isPlaying = state.isPlaying,
                onPlayPause = onPlayPause,
                onSkipForward = onSkipForward,
                onSkipBackward = onSkipBackward,
            )
        }
    }
}

@Composable
private fun Artwork(artworkPath: String?, size: Dp) {
    val bitmap = rememberImageBitmap(artworkPath)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                modifier = Modifier.size(size / 3),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Scrubber(state: PlaybackUiState, onSeek: (Long) -> Unit) {
    val durationMs = state.durationMs
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = state.progress,
            onValueChange = { fraction ->
                if (durationMs > 0L) onSeek((fraction * durationMs).toLong())
            },
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClockTime(state.positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatClockTime(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Controls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeekButton(
            label = stringResource(R.string.label_back_10),
            description = stringResource(R.string.skip_back_10),
            onClick = onSkipBackward,
        )

        FloatingActionButton(
            onClick = onPlayPause,
            shape = CircleShape,
            modifier = Modifier.size(76.dp),
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            )
        }

        SeekButton(
            label = stringResource(R.string.label_forward_30),
            description = stringResource(R.string.skip_forward_30),
            onClick = onSkipForward,
        )
    }
}

/**
 * A round tonal button labelled with the distance it seeks.
 *
 * Labelling beats a "skip" glyph here: the same gesture on the earbuds means the same thing, and
 * the number is the whole point of the button.
 */
@Composable
private fun SeekButton(label: String, description: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(64.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
