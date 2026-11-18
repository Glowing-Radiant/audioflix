package app.audioflix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.audioflix.container
import app.audioflix.ui.settings.SettingsScreen
import app.audioflix.ui.settings.SettingsSection
import app.audioflix.ui.settings.SettingsSectionScreen
import app.audioflix.ui.folders.showOutcome
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.launch
import app.audioflix.ui.browse.BrowseScreen
import app.audioflix.ui.browse.BrowseViewModel
import app.audioflix.ui.detail.ItemDetailScreen
import app.audioflix.ui.folders.FolderScreen
import app.audioflix.ui.library.LibraryScreen
import app.audioflix.ui.library.LibraryViewModel
import app.audioflix.ui.player.MiniPlayer
import app.audioflix.ui.player.PlayerScreen
import app.audioflix.ui.update.UpdateScreen
import kotlinx.serialization.Serializable

@Serializable data object BrowseKey : NavKey
@Serializable data object LibraryKey : NavKey
@Serializable data object SettingsKey : NavKey

/** One page of Settings; see [SettingsSection]. */
@Serializable data class SettingsSectionKey(val section: String) : NavKey
@Serializable data class DetailKey(val itemKey: String) : NavKey
@Serializable data object PlayerKey : NavKey
@Serializable data object UpdateKey : NavKey

/** A folder of a link-browsed provider; [save] adds it to "Your folders" (true for pasted links). */
@Serializable data class FolderKey(val providerId: String, val folderId: String, val save: Boolean) : NavKey

private data class Tab(val key: NavKey, val label: String, val icon: ImageVector)

private val tabs = listOf(
  Tab(LibraryKey, "Library", Icons.Filled.LibraryMusic),
  Tab(BrowseKey, "Browse", Icons.Filled.Search),
  Tab(SettingsKey, "Settings", Icons.Filled.Settings),
)

/** App-wide snackbar, so screens can report things like "Sign in to download". */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun AppRoot() {
  val container = LocalContext.current.container
  val backStack = rememberNavBackStack(LibraryKey)
  val snackbar = remember { SnackbarHostState() }
  val playerState by container.player.state.collectAsStateWithLifecycle()
  val updateState by container.updates.state.collectAsStateWithLifecycle()

  // Top-level screens keep their state (search results, filters) while switching tabs.
  val browseViewModel: BrowseViewModel = viewModel { BrowseViewModel(container) }
  val libraryViewModel: LibraryViewModel = viewModel { LibraryViewModel(container) }

  fun selectTab(key: NavKey) {
    if (backStack.size == 1 && backStack.first() == key) return
    backStack.clear()
    backStack.add(key)
  }

  /** Sign-in lives on the page of the source that has accounts, under Settings. */
  fun openSignIn() {
    selectTab(SettingsKey)
    val provider = container.providers.all.firstOrNull { it.requiresLogin } ?: return
    backStack.add(SettingsSectionKey(SettingsSection.source(provider.id)))
  }

  // Results of adding titles arrive after the user may have left the screen they started on.
  LaunchedEffect(Unit) {
    container.addNotices.flow.collect { outcome ->
      // A "working on it" message gives way to its result.
      snackbar.currentSnackbarData?.takeIf { it.visuals.duration == SnackbarDuration.Indefinite }?.dismiss()
      launch { showOutcome(snackbar, outcome) { backStack.add(DetailKey(it)) } }
    }
  }

  // Open the update screen once for each new version found.
  val availableUpdate = updateState.available
  LaunchedEffect(availableUpdate?.latest?.version) {
    if (availableUpdate != null && container.updates.shouldPrompt(availableUpdate) && backStack.lastOrNull() != UpdateKey) {
      backStack.add(UpdateKey)
    }
  }

  val current = backStack.lastOrNull()
  val showChrome = current != PlayerKey

  CompositionLocalProvider(LocalSnackbar provides snackbar) {
    // Screens draw their own top bars and status-bar insets; this scaffold only adds the bottom chrome.
    Scaffold(
      contentWindowInsets = WindowInsets(0),
      snackbarHost = { SnackbarHost(snackbar) },
      bottomBar = {
        if (showChrome) {
          Column {
            if (playerState.hasMedia) {
              MiniPlayer(
                state = playerState,
                onTogglePlay = container.player::togglePlayPause,
                onOpen = { backStack.add(PlayerKey) },
              )
            }
            NavigationBar {
              val root = backStack.firstOrNull()
              tabs.forEach { tab ->
                NavigationBarItem(
                  selected = root == tab.key,
                  onClick = { selectTab(tab.key) },
                  icon = { Icon(tab.icon, contentDescription = null) },
                  label = { Text(tab.label) },
                )
              }
            }
          }
        }
      },
    ) { padding ->
      NavDisplay(
        backStack = backStack,
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
          rememberSaveableStateHolderNavEntryDecorator(),
          rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
          entry<LibraryKey> {
            LibraryScreen(
              viewModel = libraryViewModel,
              onOpenItem = { backStack.add(DetailKey(it)) },
              onBrowse = { selectTab(BrowseKey) },
            )
          }
          entry<BrowseKey> {
            BrowseScreen(
              viewModel = browseViewModel,
              onOpenItem = { backStack.add(DetailKey(it)) },
              onSignIn = { openSignIn() },
              onOpenFolder = { providerId, folderId ->
                backStack.add(FolderKey(providerId, folderId, save = true))
              },
            )
          }
          entry<FolderKey> { key ->
            FolderScreen(
              providerId = key.providerId,
              folderId = key.folderId,
              saveToList = key.save,
              onBack = { backStack.removeLastOrNull() },
              onOpenFolder = { backStack.add(FolderKey(key.providerId, it, save = false)) },
              onOpenItem = { backStack.add(DetailKey(it)) },
            )
          }
          entry<SettingsKey> { SettingsScreen(onOpenSection = { backStack.add(SettingsSectionKey(it)) }) }
          entry<SettingsSectionKey> { key ->
            SettingsSectionScreen(
              section = key.section,
              onBack = { backStack.removeLastOrNull() },
              onOpenUpdate = { backStack.add(UpdateKey) },
            )
          }
          entry<UpdateKey> { UpdateScreen(onBack = { backStack.removeLastOrNull() }) }
          entry<DetailKey> { key ->
            ItemDetailScreen(
              itemKey = key.itemKey,
              onBack = { backStack.removeLastOrNull() },
              onOpenPlayer = { backStack.add(PlayerKey) },
              onSignIn = { openSignIn() },
              onOpenFolder = { providerId, folderId -> backStack.add(FolderKey(providerId, folderId, save = false)) },
            )
          }
          entry<PlayerKey> {
            PlayerScreen(
              onBack = { backStack.removeLastOrNull() },
              onOpenItem = { itemKey ->
                backStack.removeLastOrNull()
                if ((backStack.lastOrNull() as? DetailKey)?.itemKey != itemKey) backStack.add(DetailKey(itemKey))
              },
            )
          }
        },
      )
    }
  }
}
