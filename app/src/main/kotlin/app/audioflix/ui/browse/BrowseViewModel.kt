package app.audioflix.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.audioflix.AppContainer
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.CatalogSection
import app.audioflix.provider.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BrowseTab(val label: String) { HOME("Featured"), MOVIES("Movies"), SHOWS("Shows") }

data class HomeState(
  val sections: List<CatalogSection> = emptyList(),
  val loading: Boolean = false,
  val error: String? = null,
)

data class ListingState(
  val query: String = "",
  val items: List<CatalogItem> = emptyList(),
  val page: Int = 0,
  val hasNext: Boolean = true,
  val loading: Boolean = false,
  val error: String? = null,
)

sealed interface BrowseEvent {
  data class NeedsSignIn(val providerName: String) : BrowseEvent
  data class Queued(val title: String) : BrowseEvent
}

class BrowseViewModel(private val container: AppContainer) : ViewModel() {
  val provider: CatalogProvider = container.providers.default

  private val _tab = MutableStateFlow(BrowseTab.HOME)
  val tab: StateFlow<BrowseTab> = _tab.asStateFlow()

  private val _home = MutableStateFlow(HomeState())
  val home: StateFlow<HomeState> = _home.asStateFlow()

  private val listings = MediaKind.entries.associateWith { MutableStateFlow(ListingState()) }
  private val listingJobs = mutableMapOf<MediaKind, Job>()

  fun listing(kind: MediaKind): StateFlow<ListingState> = listings.getValue(kind)

  /** Library entries by key, to show which listed titles are already downloaded or downloading. */
  val library: StateFlow<Map<String, LibraryItemWithTracks>> = container.library.items
    .map { list -> list.associateBy { it.item.key } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  private val _events = Channel<BrowseEvent>(Channel.BUFFERED)
  val events = _events.receiveAsFlow()

  init {
    loadHome()
  }

  fun selectTab(tab: BrowseTab) {
    _tab.value = tab
    when (tab) {
      BrowseTab.HOME -> if (_home.value.sections.isEmpty() && !_home.value.loading) loadHome()
      BrowseTab.MOVIES -> ensureLoaded(MediaKind.MOVIE)
      BrowseTab.SHOWS -> ensureLoaded(MediaKind.SHOW)
    }
  }

  fun loadHome() {
    _home.update { it.copy(loading = true, error = null) }
    viewModelScope.launch {
      _home.value = try {
        HomeState(sections = provider.home())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        HomeState(error = e.message ?: "Couldn't load ${provider.displayName}")
      }
    }
  }

  fun search(kind: MediaKind, query: String) {
    listings.getValue(kind).value = ListingState(query = query.trim())
    loadMore(kind)
  }

  fun loadMore(kind: MediaKind) {
    val state = listings.getValue(kind)
    if (state.value.loading || !state.value.hasNext) return
    val request = state.value
    state.update { it.copy(loading = true, error = null) }
    listingJobs[kind]?.cancel()
    listingJobs[kind] = viewModelScope.launch {
      try {
        val nextPage = request.page + 1
        val page = if (request.query.isEmpty()) {
          provider.browse(kind, nextPage)
        } else {
          provider.search(kind, request.query, nextPage)
        }
        state.update {
          // Pages can overlap if the catalog changed between requests.
          val seen = it.items.mapTo(HashSet()) { item -> item.remoteId }
          it.copy(
            items = it.items + page.items.filter { item -> item.remoteId !in seen },
            page = nextPage,
            hasNext = page.hasNext && page.items.isNotEmpty(),
            loading = false,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the list") }
      }
    }
  }

  fun download(item: CatalogItem) {
    viewModelScope.launch {
      if (provider.requiresLogin && provider.account.value == null) {
        _events.send(BrowseEvent.NeedsSignIn(provider.displayName))
        return@launch
      }
      container.library.download(item)
      _events.send(BrowseEvent.Queued(item.title))
    }
  }

  private fun ensureLoaded(kind: MediaKind) {
    val s = listings.getValue(kind).value
    if (s.items.isEmpty() && s.page == 0 && !s.loading) loadMore(kind)
  }
}
