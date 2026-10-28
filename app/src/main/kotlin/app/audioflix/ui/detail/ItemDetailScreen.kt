package app.audioflix.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.audioflix.AppContainer
import app.audioflix.container
import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.data.Track
import app.audioflix.player.PlayerUiState
import app.audioflix.provider.MediaKind
import app.audioflix.ui.browse.Loading
import app.audioflix.ui.formatDuration
import app.audioflix.ui.library.downloadPercent
import app.audioflix.ui.library.kindLabel
import app.audioflix.ui.library.statusSummary
import app.audioflix.ui.spokenDuration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ItemDetailViewModel(private val container: AppContainer, val itemKey: String) : ViewModel() {
  /** Null until loaded, and again once the item is deleted. */
  val entry: StateFlow<LibraryItemWithTracks?> = container.library.observe(itemKey)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  val player = container.player.state

  fun play(trackIndex: Int? = null) = container.player.play(itemKey, trackIndex)

  fun togglePlayPause() = container.player.togglePlayPause()

  fun retry() = viewModelScope.launch { container.library.retry(itemKey) }

  fun setFinished(finished: Boolean) = viewModelScope.launch { container.library.setFinished(itemKey, finished) }

  fun setTrackCompleted(index: Int, completed: Boolean) =
    viewModelScope.launch { container.library.setTrackCompleted(itemKey, index, completed) }

  fun delete(onDone: () -> Unit) = viewModelScope.launch {
    container.player.stopIfPlaying(itemKey)
    container.library.delete(itemKey)
    onDone()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
  itemKey: String,
  onBack: () -> Unit,
  onOpenPlayer: () -> Unit,
  onSignIn: () -> Unit,
) {
  val container = LocalContext.current.container
  val viewModel: ItemDetailViewModel = viewModel(key = itemKey) { ItemDetailViewModel(container, itemKey) }
  val entry by viewModel.entry.collectAsStateWithLifecycle()
  val player by viewModel.player.collectAsStateWithLifecycle()
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(entry?.item?.title.orEmpty(), maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
          val current = entry ?: return@TopAppBar
          Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              if (current.item.status == DownloadStatus.COMPLETED) {
                DropdownMenuItem(
                  text = { Text(if (current.item.finished) "Mark as not finished" else "Mark as finished") },
                  onClick = {
                    viewModel.setFinished(!current.item.finished)
                    menuOpen = false
                  },
                )
              }
              DropdownMenuItem(
                text = { Text(if (current.item.status == DownloadStatus.COMPLETED) "Delete from library" else "Cancel download") },
                onClick = {
                  menuOpen = false
                  confirmDelete = true
                },
              )
            }
          }
        },
      )
    },
  ) { padding ->
    val current = entry
    if (current == null) {
      Loading(Modifier.padding(padding).fillMaxSize())
      return@Scaffold
    }
    LazyColumn(Modifier.padding(padding).fillMaxSize()) {
      item(key = "header") {
        Header(current, player, viewModel, onOpenPlayer, onSignIn)
      }
      if (current.item.status == DownloadStatus.COMPLETED) {
        item(key = "tracks-heading") {
          val count = current.tracks.size
          Text(
            if (current.item.kind == MediaKind.SHOW || count > 1) "Episodes ($count)" else "Audio",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp).semantics { heading() },
          )
          HorizontalDivider()
        }
        items(current.sortedTracks, key = { it.index }) { track ->
          TrackRow(
            track = track,
            isCurrent = player.itemKey == current.item.key && player.trackIndex == track.index,
            isPlaying = player.isPlaying,
            onPlay = {
              viewModel.play(track.index)
              onOpenPlayer()
            },
            onSetCompleted = { viewModel.setTrackCompleted(track.index, it) },
          )
        }
      }
    }
  }

  if (confirmDelete) {
    val downloaded = entry?.item?.status == DownloadStatus.COMPLETED
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(if (downloaded) "Delete from library?" else "Cancel download?") },
      text = {
        Text(
          if (downloaded) "The downloaded audio and your listening progress will be removed from this device."
          else "The partially downloaded file will be removed.",
        )
      },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = false
          viewModel.delete(onBack)
        }) { Text(if (downloaded) "Delete" else "Cancel download") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
  }
}

@Composable
private fun Header(
  entry: LibraryItemWithTracks,
  player: PlayerUiState,
  viewModel: ItemDetailViewModel,
  onOpenPlayer: () -> Unit,
  onSignIn: () -> Unit,
) {
  val item = entry.item
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    kindLabel(item.kind)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

    when (item.status) {
      DownloadStatus.COMPLETED -> {
        Text(statusSummary(entry), style = MaterialTheme.typography.bodyLarge)
        if (entry.totalDurationMs > 0) {
          Text(
            "Total length ${formatDuration(entry.totalDurationMs)}",
            modifier = Modifier.clearAndSetSemantics {
              contentDescription = "Total length ${spokenDuration(entry.totalDurationMs)}"
            },
          )
        }
        val isCurrent = player.itemKey == item.key
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(onClick = {
            if (isCurrent) viewModel.togglePlayPause() else viewModel.play()
            if (!isCurrent || !player.isPlaying) onOpenPlayer()
          }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(playButtonLabel(entry, player), modifier = Modifier.padding(start = 8.dp))
          }
          if (isCurrent) OutlinedButton(onClick = onOpenPlayer) { Text("Open player") }
        }
      }
      DownloadStatus.FAILED -> {
        Text(statusSummary(entry), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(onClick = { viewModel.retry() }) { Text("Retry download") }
          if (item.error?.contains("sign in", ignoreCase = true) == true) {
            OutlinedButton(onClick = onSignIn) { Text("Sign in") }
          }
        }
      }
      else -> {
        // Announce download progress changes politely for screen-reader users.
        Text(statusSummary(entry), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (item.status == DownloadStatus.DOWNLOADING && item.totalBytes > 0) {
          LinearProgressIndicator(
            progress = { downloadPercent(entry) / 100f },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
          )
        } else {
          LinearProgressIndicator(Modifier.fillMaxWidth().clearAndSetSemantics { })
        }
      }
    }
  }
  HorizontalDivider()
}

private fun playButtonLabel(entry: LibraryItemWithTracks, player: PlayerUiState): String {
  val item = entry.item
  if (player.itemKey == item.key) return if (player.isPlaying) "Pause" else "Continue"
  if (item.finished) return "Play again"
  if (entry.listenedMs <= 0) return "Play"
  val track = entry.sortedTracks.getOrNull(item.currentTrackIndex) ?: return "Resume"
  val at = formatDuration(track.positionMs)
  return if (entry.tracks.size > 1) "Resume episode ${track.index + 1} at $at" else "Resume at $at"
}

@Composable
private fun TrackRow(
  track: Track,
  isCurrent: Boolean,
  isPlaying: Boolean,
  onPlay: () -> Unit,
  onSetCompleted: (Boolean) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val progressText = when {
    track.completed -> "Played"
    track.positionMs > 0 && track.durationMs > 0 -> "${formatDuration(track.positionMs)} of ${formatDuration(track.durationMs)}"
    track.durationMs > 0 -> formatDuration(track.durationMs)
    else -> null
  }
  val spokenProgress = when {
    track.completed -> "Played"
    track.positionMs > 0 && track.durationMs > 0 ->
      "${spokenDuration(track.positionMs)} of ${spokenDuration(track.durationMs)} listened"
    track.durationMs > 0 -> spokenDuration(track.durationMs)
    else -> null
  }
  val toggleLabel = if (track.completed) "Mark as not played" else "Mark as played"

  ListItem(
    modifier = Modifier
      .clickable(onClickLabel = "Play", onClick = onPlay)
      .semantics(mergeDescendants = true) {
        customActions = listOf(CustomAccessibilityAction(toggleLabel) { onSetCompleted(!track.completed); true })
      },
    leadingContent = {
      when {
        isCurrent -> Icon(Icons.Filled.GraphicEq, contentDescription = if (isPlaying) "Now playing" else "Current", tint = MaterialTheme.colorScheme.primary)
        track.completed -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        else -> Text("${track.index + 1}", modifier = Modifier.size(24.dp).clearAndSetSemantics { }, style = MaterialTheme.typography.labelLarge)
      }
    },
    headlineContent = { Text(track.title) },
    supportingContent = progressText?.let {
      {
        Text(it, modifier = Modifier.clearAndSetSemantics { spokenProgress?.let { s -> contentDescription = s } })
      }
    },
    trailingContent = {
      Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = { menuOpen = true }) {
          Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${track.title}")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(text = { Text(toggleLabel) }, onClick = {
            onSetCompleted(!track.completed)
            menuOpen = false
          })
        }
      }
    },
  )
  HorizontalDivider()
}
