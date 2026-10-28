package app.audioflix.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.player.PlayerUiState
import app.audioflix.ui.DropdownSelector
import app.audioflix.ui.browse.Loading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  viewModel: LibraryViewModel,
  onOpenItem: (String) -> Unit,
  onBrowse: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val player by viewModel.player.collectAsStateWithLifecycle()

  Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      when {
        !state.loaded -> Loading()
        state.isEmpty -> EmptyLibrary(onBrowse)
        else -> {
          DropdownSelector(
            label = "Filter",
            options = LibraryFilter.entries,
            selected = state.filter,
            optionLabel = { it.label },
            onSelect = viewModel::setFilter,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          )
          if (state.items.isEmpty()) {
            Text(
              "No titles match \"${state.filter.label}\".",
              modifier = Modifier.padding(16.dp),
              style = MaterialTheme.typography.bodyLarge,
            )
          }
          LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.item.key }) { entry ->
              LibraryRow(
                entry = entry,
                player = player,
                onClick = { onOpenItem(entry.item.key) },
                onPlay = {
                  if (player.itemKey == entry.item.key) viewModel.togglePlayPause() else viewModel.play(entry.item.key)
                },
                onRetry = { viewModel.retry(entry.item.key) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LibraryRow(
  entry: LibraryItemWithTracks,
  player: PlayerUiState,
  onClick: () -> Unit,
  onPlay: () -> Unit,
  onRetry: () -> Unit,
) {
  val item = entry.item
  val isCurrent = player.itemKey == item.key
  val summary = statusSummary(entry)
  ListItem(
    modifier = Modifier.clickable(onClickLabel = "Open details", onClick = onClick),
    overlineContent = kindLabel(item.kind)?.let { { Text(if (isCurrent) "$it, now playing" else it) } },
    headlineContent = { Text(item.title) },
    supportingContent = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary)
        val progress = when (item.status) {
          DownloadStatus.COMPLETED -> entry.listenedFraction.takeIf { it > 0f }
          DownloadStatus.DOWNLOADING -> downloadPercent(entry) / 100f
          else -> null
        }
        // The summary text already says this; keep the bar out of the accessibility tree.
        progress?.let {
          LinearProgressIndicator(
            progress = { it },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
          )
        }
      }
    },
    trailingContent = {
      when (item.status) {
        DownloadStatus.COMPLETED -> IconButton(onClick = onPlay) {
          val playing = isCurrent && player.isPlaying
          Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = when {
              playing -> "Pause ${item.title}"
              entry.listenedMs > 0 && !item.finished -> "Resume ${item.title}"
              else -> "Play ${item.title}"
            },
          )
        }
        DownloadStatus.FAILED -> IconButton(onClick = onRetry) {
          Icon(Icons.Filled.Refresh, contentDescription = "Retry download of ${item.title}")
        }
        else -> {}
      }
    },
  )
  HorizontalDivider()
}

@Composable
private fun EmptyLibrary(onBrowse: () -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
    Text(
      "Find a movie or show and download it. It will show up here with your listening progress.",
      modifier = Modifier.padding(vertical = 16.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onBrowse) { Text("Browse titles") }
  }
}
