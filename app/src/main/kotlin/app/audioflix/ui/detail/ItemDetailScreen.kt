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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.audioflix.data.DownloadResult
import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.data.Track
import app.audioflix.download.MediaInstaller
import app.audioflix.player.PlayerUiState
import app.audioflix.ui.LocalSnackbar
import app.audioflix.ui.browse.Loading
import app.audioflix.ui.browse.limitReachedText
import app.audioflix.ui.formatDuration
import app.audioflix.ui.library.downloadPercent
import app.audioflix.ui.library.kindLabel
import app.audioflix.ui.library.listeningSummary
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

  /** Name of the service the item comes from, e.g. "Google Drive". */
  fun providerName(providerId: String): String = container.providers[providerId].displayName

  /** Downloads a streamed item; returns a message when the provider's daily limit stops it. */
  fun download(onMessage: (String) -> Unit) = viewModelScope.launch {
    val providerId = entry.value?.item?.providerId ?: return@launch
    val provider = container.providers[providerId]
    val result = container.library.download(itemKey, provider.dailyDownloadLimit)
    if (result is DownloadResult.LimitReached) onMessage(limitReachedText(provider.displayName, result.status))
  }

  /** Stops or removes the download but keeps the item, which goes back to streaming. */
  fun removeDownload() = viewModelScope.launch { container.library.removeDownload(itemKey) }

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
  val snackbar = LocalSnackbar.current
  val scope = rememberCoroutineScope()
  var menuOpen by remember { mutableStateOf(false) }
  var confirm by remember { mutableStateOf<Confirm?>(null) }

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
              if (current.playable) {
                DropdownMenuItem(
                  text = { Text(if (current.item.finished) "Mark as not finished" else "Mark as finished") },
                  onClick = {
                    viewModel.setFinished(!current.item.finished)
                    menuOpen = false
                  },
                )
              }
              confirmOptions(current).forEach { option ->
                DropdownMenuItem(
                  text = { Text(option.menuLabel) },
                  onClick = {
                    menuOpen = false
                    confirm = option
                  },
                )
              }
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
        Header(current, player, viewModel, onOpenPlayer, onSignIn, onMessage = { message ->
          scope.launch { snackbar.showSnackbar(message) }
        })
      }
      if (current.playable) {
        item(key = "tracks-heading") {
          val count = current.tracks.size
          Text(
            if (count <= 1) "Audio" else "${current.trackNoun.replaceFirstChar(Char::uppercase)}s ($count)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp).semantics { heading() },
          )
          HorizontalDivider()
        }
        val tracks = current.sortedTracks
        val groupByFile = current.hasSeveralFiles && current.hasChapters
        tracks.forEachIndexed { i, track ->
          // Chapters of several books (files) are easy to tell apart, and to jump between by heading.
          if (groupByFile && (i == 0 || tracks[i - 1].relativePath != track.relativePath)) {
            item(key = "file:${track.index}") {
              Text(
                MediaInstaller.trackTitle(track.relativePath),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
              )
            }
          }
          item(key = track.index) {
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
  }

  confirm?.let { option ->
    AlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text(option.title) },
      text = { Text(option.body) },
      confirmButton = {
        TextButton(onClick = {
          confirm = null
          if (option.keepsItem) viewModel.removeDownload() else viewModel.delete(onBack)
        }) { Text(option.confirmLabel) }
      },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
    )
  }
}

/** A destructive menu action and the wording of its confirmation. */
private enum class Confirm(
  val menuLabel: String,
  val title: String,
  val body: String,
  val confirmLabel: String,
  /** True when the item stays in the library (streaming); false when it's deleted. */
  val keepsItem: Boolean,
) {
  DELETE(
    "Delete from library",
    "Delete from library?",
    "The downloaded audio and your listening progress will be removed from this device.",
    "Delete",
    keepsItem = false,
  ),
  CANCEL(
    "Cancel download",
    "Cancel download?",
    "The partially downloaded files will be removed.",
    "Cancel download",
    keepsItem = false,
  ),
  CANCEL_KEEP_STREAMING(
    "Cancel download",
    "Cancel download?",
    "The partially downloaded files will be removed. The title stays in your library for streaming.",
    "Cancel download",
    keepsItem = true,
  ),
  REMOVE_DOWNLOAD(
    "Remove download, keep streaming",
    "Remove the download?",
    "The audio files will be removed from this device. The title stays in your library and streams " +
      "from the internet, with your listening progress kept.",
    "Remove download",
    keepsItem = true,
  ),
  REMOVE(
    "Remove from library",
    "Remove from library?",
    "The title and your listening progress will be removed from your library.",
    "Remove",
    keepsItem = false,
  ),
}

private fun confirmOptions(entry: LibraryItemWithTracks): List<Confirm> {
  val status = entry.item.status
  if (!entry.streamable) {
    return listOf(if (status == DownloadStatus.COMPLETED) Confirm.DELETE else Confirm.CANCEL)
  }
  return when {
    status == DownloadStatus.COMPLETED -> listOf(Confirm.REMOVE_DOWNLOAD, Confirm.DELETE)
    entry.downloading -> listOf(Confirm.CANCEL_KEEP_STREAMING, Confirm.REMOVE)
    else -> listOf(Confirm.REMOVE)
  }
}

@Composable
private fun Header(
  entry: LibraryItemWithTracks,
  player: PlayerUiState,
  viewModel: ItemDetailViewModel,
  onOpenPlayer: () -> Unit,
  onSignIn: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val item = entry.item
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    kindLabel(item.kind)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

    if (entry.playable) {
      Text(listeningSummary(entry), style = MaterialTheme.typography.bodyLarge)
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

    when (item.status) {
      DownloadStatus.COMPLETED -> {}
      DownloadStatus.ONLINE -> {
        Text(
          "Plays from ${viewModel.providerName(item.providerId)} over the internet. Download it to listen offline.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { viewModel.download(onMessage) }) { Text("Download for offline listening") }
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
  return if (entry.tracks.size > 1) "Resume ${entry.trackNoun} ${track.index + 1} at $at" else "Resume at $at"
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
