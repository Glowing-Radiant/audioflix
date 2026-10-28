package app.audioflix.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.audioflix.container
import app.audioflix.player.PlaybackService
import app.audioflix.player.PlayerUiState
import app.audioflix.ui.formatDuration
import app.audioflix.ui.spokenDuration

private val speeds = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedLabel(speed: Float) = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit) {
  val player = LocalContext.current.container.player
  val state by player.state.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Now playing") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    if (!state.hasMedia) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Nothing is playing", style = MaterialTheme.typography.bodyLarge)
      }
      return@Scaffold
    }
    Column(
      Modifier.padding(padding).fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Spacer(Modifier.weight(1f))
      Text(
        state.itemTitle,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
      )
      if (state.trackCount > 1) {
        Text(state.trackTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text("Episode ${state.trackIndex + 1} of ${state.trackCount}", style = MaterialTheme.typography.labelLarge)
      }
      Spacer(Modifier.weight(1f))

      SeekBar(state, onSeek = player::seekTo)

      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
          Icon(Icons.Filled.SkipPrevious, contentDescription = if (state.trackCount > 1) "Previous episode" else "Restart")
        }
        IconButton(onClick = player::seekBack, modifier = Modifier.size(56.dp)) {
          Icon(Icons.Filled.Replay10, contentDescription = "Back ${PlaybackService.SEEK_BACK_MS / 1000} seconds")
        }
        FilledIconButton(onClick = player::togglePlayPause, modifier = Modifier.size(80.dp)) {
          Icon(
            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            modifier = Modifier.size(40.dp),
          )
        }
        IconButton(onClick = player::seekForward, modifier = Modifier.size(56.dp)) {
          Icon(Icons.Filled.Forward30, contentDescription = "Forward ${PlaybackService.SEEK_FORWARD_MS / 1000} seconds")
        }
        IconButton(
          onClick = player::next,
          enabled = state.trackIndex < state.trackCount - 1,
          modifier = Modifier.size(56.dp),
        ) {
          Icon(Icons.Filled.SkipNext, contentDescription = "Next episode")
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SpeedButton(state.speed, onSelect = player::setSpeed)
        state.itemKey?.let { key -> OutlinedButton(onClick = { onOpenItem(key) }) { Text("Episodes and details") } }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
  // While dragging, show the drag position instead of the live one.
  var dragging by remember { mutableStateOf<Float?>(null) }
  val duration = state.durationMs.coerceAtLeast(1)
  val fraction = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
  val shownPosition = (fraction * duration).toLong()

  Column(Modifier.fillMaxWidth()) {
    Slider(
      value = fraction,
      onValueChange = { dragging = it },
      onValueChangeFinished = {
        dragging?.let { onSeek((it * duration).toLong()) }
        dragging = null
      },
      enabled = state.durationMs > 0,
      modifier = Modifier.semantics {
        contentDescription = "Playback position"
        stateDescription = "${spokenDuration(shownPosition)} of ${spokenDuration(state.durationMs)}"
      },
    )
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
      Text(formatDuration(shownPosition), style = MaterialTheme.typography.labelMedium)
      Text("-" + formatDuration(state.durationMs - shownPosition), style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun SpeedButton(speed: Float, onSelect: (Float) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Playback speed ${speedLabel(speed)}" },
    ) { Text(speedLabel(speed)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      speeds.forEach { s ->
        DropdownMenuItem(
          text = { Text(speedLabel(s)) },
          leadingIcon = if (s == speed) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
          onClick = {
            onSelect(s)
            open = false
          },
          modifier = if (s == speed) Modifier.semantics { stateDescription = "Current" } else Modifier,
        )
      }
    }
  }
}

@Composable
fun MiniPlayer(state: PlayerUiState, onTogglePlay: () -> Unit, onOpen: () -> Unit) {
  Surface(tonalElevation = 3.dp) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = "Open player", onClick = onOpen)
        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(state.itemTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = if (state.trackCount > 1) state.trackTitle else formatDuration(state.positionMs) + " / " + formatDuration(state.durationMs)
        Text(
          sub,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = if (state.trackCount > 1) Modifier else Modifier.clearAndSetSemantics { },
        )
      }
      IconButton(onClick = onTogglePlay) {
        Icon(
          if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
          contentDescription = if (state.isPlaying) "Pause" else "Play",
        )
      }
    }
  }
}
