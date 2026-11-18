package app.audioflix.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.audioflix.AppContainer
import app.audioflix.data.DownloadResult
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.download.QuotaStatus
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.CatalogSection
import app.audioflix.provider.FolderProvider
import app.audioflix.provider.LinkTarget
import app.audioflix.provider.MediaKind
import app.audioflix.provider.SavedFolder
import app.audioflix.summary.TitleSummary
import app.audioflix.ui.folders.AddChoice
import app.audioflix.ui.folders.AddOutcome
import app.audioflix.ui.folders.FolderAdder
import app.audioflix.ui.folders.errorMessage
import app.audioflix.ui.folders.fileChoice
import java.net.UnknownHostException
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

/** Where Browse looks: the default provider's catalog, or a provider browsed by link. */
sealed interface BrowseSource {
  val label: String

  data class Catalog(val provider: CatalogProvider) : BrowseSource {
    override val label: String get() = provider.displayName
  }

  data class Folders(val provider: FolderProvider) : BrowseSource {
    override val label: String get() = provider.displayName
  }
}

/** The state of one folder provider's link box. */
data class LinkState(val busy: Boolean = false, val error: String? = null)

/** A single file opened from a link, waiting for the user to say stream or download. */
data class PendingAdd(val provider: FolderProvider, val choice: AddChoice)

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

sealed interface SummaryState {
  /** Not in the cache; the user can ask for it. */
  data object NotLoaded : SummaryState
  data object Loading : SummaryState
  data class Found(val summary: TitleSummary) : SummaryState
  data object NotFound : SummaryState
  data class Failed(val message: String) : SummaryState
}

/** The summary shown in the details popup, tagged with the item it belongs to. */
data class ItemSummary(val itemKey: String, val state: SummaryState)

sealed interface BrowseEvent {
  data class NeedsSignIn(val providerName: String) : BrowseEvent
  data class Queued(val title: String) : BrowseEvent
  data class LimitReached(val providerName: String, val status: QuotaStatus) : BrowseEvent
  data class OpenFolder(val providerId: String, val folderId: String) : BrowseEvent
  data class Added(val outcome: AddOutcome) : BrowseEvent
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

  private val _summary = MutableStateFlow<ItemSummary?>(null)
  val summary: StateFlow<ItemSummary?> = _summary.asStateFlow()
  private var summaryJob: Job? = null

  private val _events = Channel<BrowseEvent>(Channel.BUFFERED)
  val events = _events.receiveAsFlow()

  private val folderProviders: List<FolderProvider> = container.providers.folderProviders

  /** The catalog first, then each provider the user opens by link. */
  val sources: List<BrowseSource> =
    listOf(BrowseSource.Catalog(provider)) + folderProviders.map(BrowseSource::Folders)

  private val _source = MutableStateFlow(sources.first())
  val source: StateFlow<BrowseSource> = _source.asStateFlow()

  private val linkStates = folderProviders.associate { it.id to MutableStateFlow(LinkState()) }
  private val adders = folderProviders.associate { it.id to FolderAdder(container, it) }

  fun linkState(provider: FolderProvider): StateFlow<LinkState> = linkStates.getValue(provider.id).asStateFlow()

  private val _pendingAdd = MutableStateFlow<PendingAdd?>(null)
  val pendingAdd: StateFlow<PendingAdd?> = _pendingAdd.asStateFlow()

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

  fun selectSource(source: BrowseSource) {
    _source.value = source
  }

  /**
   * Opens a pasted link: a folder opens its own screen, a single file asks how to add it. The
   * provider works out which it is, asking the server when the link alone doesn't say.
   */
  fun openLink(provider: FolderProvider, text: String) {
    val state = linkStates.getValue(provider.id)
    state.value = LinkState(busy = true)
    viewModelScope.launch {
      try {
        when (val target = provider.resolveLink(text)) {
          is LinkTarget.Folder -> {
            state.value = LinkState()
            _events.send(BrowseEvent.OpenFolder(provider.id, target.id))
          }
          is LinkTarget.File -> {
            state.value = LinkState()
            _pendingAdd.value = PendingAdd(provider, fileChoice(provider, target.id, target.name))
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        state.value = LinkState(error = errorMessage(e))
      }
    }
  }

  fun dismissPendingAdd() {
    _pendingAdd.value = null
  }

  fun streamPending(pending: PendingAdd) {
    _pendingAdd.value = null
    viewModelScope.launch {
      _events.send(BrowseEvent.Added(adders.getValue(pending.provider.id).stream(pending.choice)))
    }
  }

  fun downloadPending(pending: PendingAdd) {
    _pendingAdd.value = null
    viewModelScope.launch {
      _events.send(BrowseEvent.Added(adders.getValue(pending.provider.id).download(pending.choice)))
    }
  }

  fun savedFolders(provider: FolderProvider): StateFlow<List<SavedFolder>> = provider.savedFolders

  fun removeSavedFolder(provider: FolderProvider, folder: SavedFolder) = provider.removeFolder(folder.id)

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
      when (val result = container.library.download(item, provider.dailyDownloadLimit)) {
        DownloadResult.Queued -> _events.send(BrowseEvent.Queued(item.title))
        is DownloadResult.LimitReached ->
          _events.send(BrowseEvent.LimitReached(provider.displayName, result.status))
      }
    }
  }

  /** Changes whenever a download is counted against the daily limit. */
  val quotaChanges = container.library.quota.changes

  /** Today's allowance for the provider, or null when it has no limit. */
  fun quota(): QuotaStatus? = provider.dailyDownloadLimit?.let { container.library.quota.status(provider.id, it) }

  /**
   * Shows the cached summary for [item] when its details popup opens. Without one, [fetch] looks it
   * up on Wikipedia right away instead of waiting for the button.
   */
  fun showSummary(item: CatalogItem, fetch: Boolean = false) {
    summaryJob?.cancel()
    _summary.value = ItemSummary(item.key, if (fetch) SummaryState.Loading else SummaryState.NotLoaded)
    summaryJob = viewModelScope.launch {
      val cached = container.summaries.cached(item)
      when {
        cached != null -> _summary.value = ItemSummary(item.key, SummaryState.Found(cached))
        fetch -> _summary.value = ItemSummary(item.key, lookUpSummary(item))
      }
    }
  }

  fun fetchSummary(item: CatalogItem) {
    summaryJob?.cancel()
    _summary.value = ItemSummary(item.key, SummaryState.Loading)
    summaryJob = viewModelScope.launch { _summary.value = ItemSummary(item.key, lookUpSummary(item)) }
  }

  private suspend fun lookUpSummary(item: CatalogItem): SummaryState = try {
    container.summaries.fetch(item)?.let(SummaryState::Found) ?: SummaryState.NotFound
  } catch (e: CancellationException) {
    throw e
  } catch (e: UnknownHostException) {
    SummaryState.Failed("No internet connection.")
  } catch (e: Exception) {
    SummaryState.Failed(e.message ?: "Something went wrong.")
  }

  private fun ensureLoaded(kind: MediaKind) {
    val s = listings.getValue(kind).value
    if (s.items.isEmpty() && s.page == 0 && !s.loading) loadMore(kind)
  }
}
