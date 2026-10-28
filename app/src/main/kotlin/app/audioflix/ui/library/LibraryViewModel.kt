package app.audioflix.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.audioflix.AppContainer
import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.provider.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryFilter(val label: String) {
  ALL("All"),
  IN_PROGRESS("In progress"),
  MOVIES("Movies"),
  SHOWS("Shows"),
  DOWNLOADS("Downloads"),
  FINISHED("Finished"),
}

data class LibraryState(
  val loaded: Boolean = false,
  val filter: LibraryFilter = LibraryFilter.ALL,
  val items: List<LibraryItemWithTracks> = emptyList(),
  val isEmpty: Boolean = true,
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
  private val filter = MutableStateFlow(LibraryFilter.ALL)

  val state: StateFlow<LibraryState> = combine(container.library.items, filter) { all, f ->
    LibraryState(loaded = true, filter = f, items = all.filter { matches(it, f) }, isEmpty = all.isEmpty())
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

  val player = container.player.state

  fun setFilter(f: LibraryFilter) {
    filter.value = f
  }

  fun play(key: String) = container.player.play(key)

  fun togglePlayPause() = container.player.togglePlayPause()

  fun retry(key: String) = viewModelScope.launch { container.library.retry(key) }

  private fun matches(entry: LibraryItemWithTracks, f: LibraryFilter): Boolean {
    val item = entry.item
    return when (f) {
      LibraryFilter.ALL -> true
      LibraryFilter.IN_PROGRESS -> item.status == DownloadStatus.COMPLETED && !item.finished && entry.listenedMs > 0
      LibraryFilter.MOVIES -> item.kind == MediaKind.MOVIE
      LibraryFilter.SHOWS -> item.kind == MediaKind.SHOW
      LibraryFilter.DOWNLOADS -> item.status != DownloadStatus.COMPLETED
      LibraryFilter.FINISHED -> item.finished
    }
  }
}
