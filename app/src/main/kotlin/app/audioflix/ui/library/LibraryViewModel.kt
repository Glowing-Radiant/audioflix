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

enum class LibrarySort(val label: String) {
  RECENT("Recently played"),
  TITLE("Title"),

  /** Grouped by where titles come from, under a heading for each. */
  SOURCE("Source"),
}

data class LibraryState(
  val loaded: Boolean = false,
  val filter: LibraryFilter = LibraryFilter.ALL,
  val sort: LibrarySort = LibrarySort.RECENT,
  val items: List<LibraryItemWithTracks> = emptyList(),
  val isEmpty: Boolean = true,
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
  private val filter = MutableStateFlow(LibraryFilter.ALL)
  private val sort = MutableStateFlow(LibrarySort.RECENT)

  /** Where each source sits in the list: the order Browse and Settings list them in. */
  private val sourceOrder = container.providers.all.withIndex().associate { (i, p) -> p.id to i }

  val state: StateFlow<LibraryState> = combine(container.library.items, filter, sort) { all, f, s ->
    LibraryState(
      loaded = true,
      filter = f,
      sort = s,
      items = sorted(all.filter { matches(it, f) }, s),
      isEmpty = all.isEmpty(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

  fun setSort(s: LibrarySort) {
    sort.value = s
  }

  /** "Google Drive", for headings when sorted by source. */
  fun sourceName(providerId: String): String =
    runCatching { container.providers[providerId].displayName }.getOrDefault(providerId)

  // The library arrives most recently played first, and sorting keeps that order within ties.
  private fun sorted(items: List<LibraryItemWithTracks>, s: LibrarySort): List<LibraryItemWithTracks> = when (s) {
    LibrarySort.RECENT -> items
    LibrarySort.TITLE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.title })
    LibrarySort.SOURCE -> items.sortedBy { sourceOrder[it.item.providerId] ?: Int.MAX_VALUE }
  }

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
      LibraryFilter.IN_PROGRESS -> entry.playable && !item.finished && entry.listenedMs > 0
      LibraryFilter.MOVIES -> item.kind == MediaKind.MOVIE
      LibraryFilter.SHOWS -> item.kind == MediaKind.SHOW
      LibraryFilter.DOWNLOADS -> item.status != DownloadStatus.COMPLETED && item.status != DownloadStatus.ONLINE
      LibraryFilter.FINISHED -> item.finished
    }
  }
}
