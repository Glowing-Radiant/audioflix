package app.audioflix.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.audioflix.AppContainer
import app.audioflix.container
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.download.MediaInstaller
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.FolderProvider
import app.audioflix.provider.RemoteEntry
import app.audioflix.provider.RemoteFolder
import app.audioflix.provider.SavedFolder
import app.audioflix.provider.local.LocalFolderProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.audioflix.ui.browse.ErrorMessage
import app.audioflix.ui.browse.Loading
import app.audioflix.ui.library.libraryStatusLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A title the user is about to add: whether it can be streamed decides which buttons show. */
data class AddChoice(val item: CatalogItem, val canStream: Boolean, val isFolder: Boolean)

fun folderChoice(provider: FolderProvider, id: String, title: String) =
  AddChoice(provider.folderItem(id, title), canStream = true, isFolder = true)

fun fileChoice(provider: FolderProvider, id: String, name: String) = AddChoice(
  provider.fileItem(id, name),
  canStream = !MediaInstaller.isArchiveName(name),
  isFolder = false,
)

/** A folder provider's page in Browse: open a link, or one of the folders opened before. */
@Composable
fun FolderHomeContent(
  provider: FolderProvider,
  saved: List<SavedFolder>,
  linkText: String,
  busy: Boolean,
  error: String?,
  onLinkChange: (String) -> Unit,
  onOpenLink: () -> Unit,
  onOpenFolder: (SavedFolder) -> Unit,
  onRemoveFolder: (SavedFolder) -> Unit,
  onOpenFolderId: (String) -> Unit,
) {
  val focus = LocalFocusManager.current
  fun submit() {
    focus.clearFocus()
    onOpenLink()
  }

  LazyColumn(Modifier.fillMaxSize()) {
    if (provider is LocalFolderProvider) {
      item(key = "pick") { FolderPicker(provider, onOpenFolderId) }
    } else item(key = "link") {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(provider.linkHelp, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
          value = linkText,
          onValueChange = onLinkChange,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(provider.linkLabel) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
          keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        Button(onClick = ::submit, enabled = linkText.isNotBlank() && !busy) {
          Text(if (busy) "Opening…" else "Open link")
        }
        // Announced when opening fails, since focus stays on the button.
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
          error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      }
      HorizontalDivider()
    }
    if (saved.isNotEmpty()) {
      item(key = "saved-heading") { SectionHeading(provider.savedHeading) }
      items(saved, key = { it.id }) { folder ->
        ListItem(
          modifier = Modifier
            .clickable(onClickLabel = "Open folder") { onOpenFolder(folder) }
            .semantics {
              customActions = listOf(
                CustomAccessibilityAction("Remove from your folders") {
                  onRemoveFolder(folder)
                  true
                },
              )
            },
          leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
          headlineContent = { Text(folder.title) },
          trailingContent = {
            TextButton(onClick = { onRemoveFolder(folder) }) { Text("Remove") }
          },
        )
        HorizontalDivider()
      }
    }
  }
}

/** The system folder picker, for folders on the phone. */
@Composable
private fun FolderPicker(provider: LocalFolderProvider, onOpenFolderId: (String) -> Unit) {
  var error by remember { mutableStateOf<String?>(null) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    try {
      onOpenFolderId(provider.adoptTree(uri))
      error = null
    } catch (e: Exception) {
      error = errorMessage(e)
    }
  }
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(provider.linkHelp, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = {
      error = null
      picker.launch(null)
    }) { Text("Choose a folder") }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }
  HorizontalDivider()
}

sealed interface FolderState {
  data object Loading : FolderState
  data class Loaded(val folder: RemoteFolder) : FolderState
  data class Failed(val message: String) : FolderState
}

class FolderViewModel(
  private val container: AppContainer,
  private val provider: FolderProvider,
  private val folderId: String,
  private val saveToList: Boolean,
) : ViewModel() {
  private val adder = FolderAdder(container, provider)

  private val _state = MutableStateFlow<FolderState>(FolderState.Loading)
  val state: StateFlow<FolderState> = _state.asStateFlow()

  val library: StateFlow<Map<String, LibraryItemWithTracks>> = container.library.items
    .map { list -> list.associateBy { it.item.key } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  init {
    load()
  }

  fun load() {
    _state.value = FolderState.Loading
    viewModelScope.launch {
      _state.value = try {
        val folder = provider.listFolder(folderId)
        if (saveToList) provider.saveFolder(SavedFolder(folder.id, folder.title))
        FolderState.Loaded(folder)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        FolderState.Failed(errorMessage(e))
      }
    }
  }

  fun stream(item: CatalogItem) = adder.stream(item)

  fun download(item: CatalogItem) = adder.download(item)
}

/** One folder of a folder provider: its subfolders and the audio files and zips in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
  providerId: String,
  folderId: String,
  saveToList: Boolean,
  onBack: () -> Unit,
  onOpenFolder: (String) -> Unit,
  onOpenItem: (String) -> Unit,
) {
  val container = LocalContext.current.container
  val provider = remember(providerId) { container.providers.folders(providerId) }
  val viewModel: FolderViewModel =
    viewModel(key = "$providerId:$folderId") { FolderViewModel(container, provider, folderId, saveToList) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val library by viewModel.library.collectAsStateWithLifecycle()
  var adding by remember { mutableStateOf<AddChoice?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text((state as? FolderState.Loaded)?.folder?.title ?: provider.displayName, maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      when (val s = state) {
        FolderState.Loading -> Loading()
        is FolderState.Failed -> ErrorMessage(s.message, viewModel::load)
        is FolderState.Loaded -> FolderContent(
          provider = provider,
          folder = s.folder,
          library = library,
          onAdd = { adding = it },
          onOpenFolder = onOpenFolder,
          onOpenItem = onOpenItem,
        )
      }
    }
  }

  adding?.let { choice ->
    AddToLibraryDialog(
      choice = choice,
      provider = provider,
      onStream = {
        adding = null
        viewModel.stream(choice.item)
      },
      onDownload = {
        adding = null
        viewModel.download(choice.item)
      },
      onDismiss = { adding = null },
    )
  }
}

@Composable
private fun FolderContent(
  provider: FolderProvider,
  folder: RemoteFolder,
  library: Map<String, LibraryItemWithTracks>,
  onAdd: (AddChoice) -> Unit,
  onOpenFolder: (String) -> Unit,
  onOpenItem: (String) -> Unit,
) {
  val folders = folder.entries.filter { it.type == RemoteEntry.Type.FOLDER }
  val files = folder.entries.filter {
    it.type == RemoteEntry.Type.FILE && (MediaInstaller.isAudioName(it.name) || MediaInstaller.isArchiveName(it.name))
  }
  val others = folder.entries.size - folders.size - files.size
  val self = folderChoice(provider, folder.id, folder.title)
  val selfEntry = library[self.item.key]

  LazyColumn(Modifier.fillMaxSize()) {
    item(key = "header") {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(folderSummary(folders.size, files, others), style = MaterialTheme.typography.bodyLarge)
        if (selfEntry != null) {
          Text(libraryStatusLabel(selfEntry), style = MaterialTheme.typography.bodyMedium)
          Button(onClick = { onOpenItem(self.item.key) }) { Text("Open in library") }
        } else if (folders.isNotEmpty() || files.isNotEmpty()) {
          Button(onClick = { onAdd(self) }) { Text("Add this folder to your library") }
          Text(
            "Adds everything in it, subfolders included, as one title.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      HorizontalDivider()
    }
    if (folders.isNotEmpty()) {
      item(key = "folders-heading") { SectionHeading("Folders (${folders.size})") }
      items(folders, key = { "d:${it.id}" }) { entry ->
        val choice = folderChoice(provider, entry.id, entry.name)
        EntryRow(
          entry = entry,
          inLibrary = library[choice.item.key],
          typeLabel = "Folder",
          onClickLabel = "Open folder",
          onClick = { onOpenFolder(entry.id) },
          extraAction = "Add folder to library" to { onAdd(choice) },
        )
      }
    }
    if (files.isNotEmpty()) {
      item(key = "files-heading") { SectionHeading("Files (${files.size})") }
      items(files, key = { "f:${it.id}" }) { entry ->
        val choice = fileChoice(provider, entry.id, entry.name)
        val inLibrary = library[choice.item.key]
        EntryRow(
          entry = entry,
          inLibrary = inLibrary,
          typeLabel = if (choice.canStream) "Audio" else "Zip file, download only",
          onClickLabel = if (inLibrary != null) "Open in library" else "Add to library",
          onClick = { if (inLibrary != null) onOpenItem(choice.item.key) else onAdd(choice) },
          extraAction = null,
        )
      }
    }
    if (folders.isEmpty() && files.isEmpty()) {
      item(key = "empty") {
        Text("There's no audio in this folder.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
      }
    }
  }
}

private fun folderSummary(folders: Int, files: List<RemoteEntry>, others: Int): String {
  val zips = files.count { MediaInstaller.isArchiveName(it.name) }
  val audio = files.size - zips
  val parts = listOfNotNull(
    plural(folders, "folder").takeIf { folders > 0 },
    plural(audio, "audio file").takeIf { audio > 0 },
    plural(zips, "zip file").takeIf { zips > 0 },
  )
  val main = parts.joinToString(", ").ifEmpty { "Empty" }
  val skipped = when (others) {
    0 -> ""
    1 -> ". 1 other file isn't audio and is left out"
    else -> ". $others other files aren't audio and are left out"
  }
  return "$main$skipped."
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

@Composable
private fun EntryRow(
  entry: RemoteEntry,
  inLibrary: LibraryItemWithTracks?,
  typeLabel: String,
  onClickLabel: String,
  onClick: () -> Unit,
  extraAction: Pair<String, () -> Unit>?,
) {
  val isFolder = entry.type == RemoteEntry.Type.FOLDER
  ListItem(
    modifier = Modifier
      .clickable(onClickLabel = onClickLabel, onClick = onClick)
      .semantics {
        if (extraAction != null && inLibrary == null) {
          customActions = listOf(
            CustomAccessibilityAction(extraAction.first) {
              extraAction.second()
              true
            },
          )
        }
      },
    leadingContent = {
      Icon(
        when {
          isFolder -> Icons.Filled.Folder
          MediaInstaller.isArchiveName(entry.name) -> Icons.Filled.Archive
          else -> Icons.Filled.MusicNote
        },
        contentDescription = null,
      )
    },
    headlineContent = { Text(entry.name) },
    supportingContent = {
      Text(listOfNotNull(typeLabel, inLibrary?.let(::libraryStatusLabel)).joinToString(", "))
    },
  )
  HorizontalDivider()
}

/** Asks whether to stream (or, for folders on the phone, play in place) a title or download (copy) it. */
@Composable
fun AddToLibraryDialog(
  choice: AddChoice,
  provider: FolderProvider,
  onStream: () -> Unit,
  onDownload: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sourceName = provider.displayName
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add ${choice.item.title}") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (provider.isLocal) {
          if (choice.canStream) {
            Text("Play from folder plays the files where they are, and takes no extra space. If they're moved or deleted, AudioFlix can't play them any more.")
            Text("Copy into AudioFlix keeps a copy of its own, so the files in the folder can be moved or deleted. Zip files are unpacked.")
            if (choice.isFolder) {
              Text("Zip files in the folder are only included when copying.", style = MaterialTheme.typography.bodySmall)
            }
          } else {
            Text("This is a zip file, so it can't be played in place. Copying it into AudioFlix unpacks it.")
          }
        } else if (choice.canStream) {
          Text("Stream plays it straight from $sourceName. It needs an internet connection and takes no space on the phone.")
          Text("Download saves it to the phone so you can listen offline. Zip files are unpacked.")
          Text(
            "Either way your listening progress is kept, and a streamed title can be downloaded later from its page.",
            style = MaterialTheme.typography.bodySmall,
          )
          if (choice.isFolder) {
            Text("Zip files in the folder can't be streamed; they're only included when downloading.", style = MaterialTheme.typography.bodySmall)
          }
        } else {
          Text("This is a zip file, so it can't be streamed. Downloading saves it to the phone and unpacks it.")
        }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (choice.canStream) {
          OutlinedButton(onClick = onStream) { Text(if (provider.isLocal) "Play from folder" else "Stream") }
        }
        Button(onClick = onDownload) { Text(if (provider.isLocal) "Copy into AudioFlix" else "Download") }
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

suspend fun showOutcome(snackbar: SnackbarHostState, outcome: AddOutcome, onOpenItem: (String) -> Unit) {
  val result = snackbar.showSnackbar(
    message = outcome.message,
    actionLabel = if (outcome.openKey != null) "Open" else null,
    // A "working on it" message stays up until the result replaces it.
    duration = if (outcome.interim) SnackbarDuration.Indefinite else SnackbarDuration.Long,
  )
  if (result == SnackbarResult.ActionPerformed) outcome.openKey?.let(onOpenItem)
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
