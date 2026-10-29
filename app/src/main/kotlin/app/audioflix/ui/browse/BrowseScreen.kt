package app.audioflix.ui.browse

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.MediaKind
import app.audioflix.ui.DropdownSelector
import app.audioflix.download.QuotaStatus
import app.audioflix.ui.LocalSnackbar
import app.audioflix.ui.spokenDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
  viewModel: BrowseViewModel,
  onOpenItem: (String) -> Unit,
  onSignIn: () -> Unit,
) {
  val tab by viewModel.tab.collectAsStateWithLifecycle()
  val library by viewModel.library.collectAsStateWithLifecycle()
  val snackbar = LocalSnackbar.current
  var selected by remember { mutableStateOf<CatalogItem?>(null) }
  // Set by the "View summary" action, so the popup looks the summary up straight away.
  var fetchSummaryOnOpen by remember { mutableStateOf(false) }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        is BrowseEvent.NeedsSignIn -> {
          val result = snackbar.showSnackbar(
            message = "Sign in to ${event.providerName} to download",
            actionLabel = "Sign in",
            duration = SnackbarDuration.Long,
          )
          if (result == SnackbarResult.ActionPerformed) onSignIn()
        }
        is BrowseEvent.Queued -> snackbar.showSnackbar("Downloading ${event.title}")
        is BrowseEvent.LimitReached -> snackbar.showSnackbar(
          message = limitReachedText(event.providerName, event.status),
          duration = SnackbarDuration.Long,
        )
      }
    }
  }

  Scaffold(
    topBar = { TopAppBar(title = { Text("Browse ${viewModel.provider.displayName}") }) },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      DropdownSelector(
        label = "Category",
        options = BrowseTab.entries,
        selected = tab,
        optionLabel = { it.label },
        onSelect = viewModel::selectTab,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      )
      val actions = RowActions(
        onClick = { item ->
          if (library[item.key] != null) {
            onOpenItem(item.key)
          } else {
            fetchSummaryOnOpen = false
            selected = item
          }
        },
        onViewSummary = { item ->
          fetchSummaryOnOpen = true
          selected = item
        },
        onDownload = viewModel::download,
      )
      when (tab) {
        BrowseTab.HOME -> HomeContent(viewModel, library, actions)
        BrowseTab.MOVIES -> ListingContent(viewModel, MediaKind.MOVIE, library, actions)
        BrowseTab.SHOWS -> ListingContent(viewModel, MediaKind.SHOW, library, actions)
      }
    }
  }

  selected?.let { item ->
    LaunchedEffect(item.key) { viewModel.showSummary(item, fetch = fetchSummaryOnOpen) }
    val inLibrary = library[item.key] != null
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val quotaChanges by viewModel.quotaChanges.collectAsStateWithLifecycle()
    val quota = remember(item.key, quotaChanges) { viewModel.quota() }
    val limitReached = quota != null && quota.remaining == 0
    AlertDialog(
      onDismissRequest = { selected = null },
      title = { Text(item.title) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          item.kind?.let { Text(if (it == MediaKind.SHOW) "TV show" else "Movie") }
          item.description?.let { Text(it) }
          SummarySection(
            state = summary?.takeIf { it.itemKey == item.key }?.state ?: SummaryState.NotLoaded,
            onFetch = { viewModel.fetchSummary(item) },
          )
          Text(
            "Downloads go to your library, where you can listen offline and pick up where you left off.",
            style = MaterialTheme.typography.bodySmall,
          )
          quota?.let {
            Text(
              if (limitReached) limitReachedText(viewModel.provider.displayName, it) else remainingText(viewModel.provider.displayName, it),
              style = MaterialTheme.typography.bodyMedium,
              color = if (limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      },
      confirmButton = {
        if (inLibrary) {
          Button(onClick = {
            selected = null
            onOpenItem(item.key)
          }) { Text("Open in library") }
        } else {
          Button(
            onClick = {
              viewModel.download(item)
              selected = null
            },
            enabled = !limitReached,
          ) { Text("Download") }
        }
      },
      dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
    )
  }
}

/** What a catalog row does when activated and from its TalkBack actions. */
private class RowActions(
  val onClick: (CatalogItem) -> Unit,
  val onViewSummary: (CatalogItem) -> Unit,
  val onDownload: (CatalogItem) -> Unit,
)

@Composable
private fun HomeContent(
  viewModel: BrowseViewModel,
  library: Map<String, LibraryItemWithTracks>,
  actions: RowActions,
) {
  val home by viewModel.home.collectAsStateWithLifecycle()
  when {
    home.loading && home.sections.isEmpty() -> Loading()
    home.error != null && home.sections.isEmpty() -> ErrorMessage(home.error!!, viewModel::loadHome)
    else -> LazyColumn(Modifier.fillMaxSize()) {
      home.sections.forEach { section ->
        item(key = "h:${section.title}") { SectionHeading(section.title) }
        items(section.items, key = { "${section.title}:${it.remoteId}" }) { item ->
          CatalogRow(item, library[item.key], showDescription = true, actions)
        }
      }
    }
  }
}

@Composable
private fun ListingContent(
  viewModel: BrowseViewModel,
  kind: MediaKind,
  library: Map<String, LibraryItemWithTracks>,
  actions: RowActions,
) {
  val state by viewModel.listing(kind).collectAsStateWithLifecycle()
  val focus = LocalFocusManager.current
  var query by rememberSaveable(kind) { mutableStateOf(state.query) }
  val noun = if (kind == MediaKind.MOVIE) "movies" else "shows"

  Column(Modifier.fillMaxSize()) {
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      label = { Text("Search $noun") },
      singleLine = true,
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      trailingIcon = {
        if (query.isNotEmpty()) {
          IconButton(onClick = {
            query = ""
            viewModel.search(kind, "")
          }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = {
        focus.clearFocus()
        viewModel.search(kind, query)
      }),
    )
    if (state.query.isNotEmpty() && !state.loading && state.items.isEmpty() && state.error == null) {
      Text(
        "No $noun found for \"${state.query}\"",
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    LazyColumn(Modifier.fillMaxSize()) {
      items(state.items, key = { it.remoteId }) { item ->
        CatalogRow(item, library[item.key], showDescription = false, actions)
      }
      listFooter(state, onLoadMore = { viewModel.loadMore(kind) })
    }
  }
}

private fun LazyListScope.listFooter(state: ListingState, onLoadMore: () -> Unit) {
  when {
    state.error != null -> item(key = "footer") { ErrorMessage(state.error, onLoadMore) }
    state.loading -> item(key = "footer") { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
    state.hasNext && state.items.isNotEmpty() -> item(key = "footer") {
      // Loads the next page as soon as the end of the list comes into view; the button is there
      // for screen-reader users and if auto-loading is interrupted.
      LaunchedEffect(state.page) { onLoadMore() }
      Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onLoadMore) { Text("Load more") }
      }
    }
  }
}

@Composable
private fun CatalogRow(
  item: CatalogItem,
  inLibrary: LibraryItemWithTracks?,
  showDescription: Boolean,
  actions: RowActions,
) {
  val status = inLibrary?.item
  val statusText = when (status?.status) {
    null -> null
    DownloadStatus.COMPLETED -> "In library"
    DownloadStatus.QUEUED -> "Waiting to download"
    DownloadStatus.DOWNLOADING ->
      if (status.totalBytes > 0) "Downloading ${status.bytesDownloaded * 100 / status.totalBytes}%" else "Downloading"
    DownloadStatus.EXTRACTING -> "Unpacking"
    DownloadStatus.FAILED -> "Download failed"
  }
  ListItem(
    modifier = Modifier
      .clickable(onClickLabel = if (status == null) "Details and download" else "Open in library") {
        actions.onClick(item)
      }
      .semantics {
        // Shortcuts from the list; activating the row still opens the details popup.
        customActions = listOfNotNull(
          CustomAccessibilityAction("View summary") {
            actions.onViewSummary(item)
            true
          },
          if (status == null) {
            CustomAccessibilityAction("Download") {
              actions.onDownload(item)
              true
            }
          } else {
            null
          },
        )
      },
    headlineContent = { Text(item.title) },
    supportingContent = {
      val lines = listOfNotNull(
        statusText,
        item.description?.takeIf { showDescription },
      )
      if (lines.isNotEmpty()) {
        Text(lines.joinToString("\n"), maxLines = 3, overflow = TextOverflow.Ellipsis)
      }
    },
    trailingContent = {
      when (status?.status) {
        null -> Icon(Icons.Filled.Download, contentDescription = null)
        DownloadStatus.COMPLETED -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        DownloadStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
      }
    },
  )
  HorizontalDivider()
}

/** "18 of 25 AudioVault downloads left today." */
internal fun remainingText(providerName: String, status: QuotaStatus): String =
  "${status.remaining} of ${status.limit} $providerName downloads left today."

internal fun limitReachedText(providerName: String, status: QuotaStatus): String {
  val wait = status.nextFreeAt?.let { it - System.currentTimeMillis() }?.takeIf { it > 0 }
  // Rounded up to whole minutes: "in 2 hours 5 minutes" reads better than seconds.
  val next = wait?.let { " The next one is available in ${spokenDuration((it + 59_999) / 60_000 * 60_000)}." }.orEmpty()
  return "You've used all ${status.limit} $providerName downloads for today.$next"
}

@Composable
private fun SummarySection(state: SummaryState, onFetch: () -> Unit) {
  // Announced when a lookup finishes, since the button the user pressed goes away.
  Column(
    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    when (state) {
      SummaryState.NotLoaded -> OutlinedButton(onClick = onFetch) { Text("Get summary from Wikipedia") }
      SummaryState.Loading -> Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Looking it up on Wikipedia…")
      }
      is SummaryState.Found -> {
        state.summary.description?.let {
          Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleSmall)
        }
        Text(state.summary.extract)
        Text("From Wikipedia: ${state.summary.articleTitle}", style = MaterialTheme.typography.bodySmall)
      }
      SummaryState.NotFound -> Text("Wikipedia has no article that matches this title.")
      is SummaryState.Failed -> {
        Text("Couldn't get a summary. ${state.message}", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onFetch) { Text("Try again") }
      }
    }
  }
}

@Composable
private fun SectionHeading(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
  )
}

@Composable
internal fun Loading(modifier: Modifier = Modifier.fillMaxSize()) {
  Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun ErrorMessage(message: String, onRetry: () -> Unit) {
  Column(
    Modifier.fillMaxWidth().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
    Row { Button(onClick = onRetry) { Text("Try again") } }
  }
}
