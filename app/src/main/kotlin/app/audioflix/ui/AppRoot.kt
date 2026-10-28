package app.audioflix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import app.audioflix.ui.account.AccountScreen
import app.audioflix.ui.browse.BrowseScreen
import app.audioflix.ui.browse.BrowseViewModel
import app.audioflix.ui.detail.ItemDetailScreen
import app.audioflix.ui.library.LibraryScreen
import app.audioflix.ui.library.LibraryViewModel
import app.audioflix.ui.player.MiniPlayer
import app.audioflix.ui.player.PlayerScreen
import kotlinx.serialization.Serializable

@Serializable data object BrowseKey : NavKey
@Serializable data object LibraryKey : NavKey
@Serializable data object AccountKey : NavKey
@Serializable data class DetailKey(val itemKey: String) : NavKey
@Serializable data object PlayerKey : NavKey

private data class Tab(val key: NavKey, val label: String, val icon: ImageVector)

private val tabs = listOf(
  Tab(LibraryKey, "Library", Icons.Filled.LibraryMusic),
  Tab(BrowseKey, "Browse", Icons.Filled.Search),
  Tab(AccountKey, "Account", Icons.Filled.AccountCircle),
)

/** App-wide snackbar, so screens can report things like "Sign in to download". */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun AppRoot() {
  val container = LocalContext.current.container
  val backStack = rememberNavBackStack(LibraryKey)
  val snackbar = remember { SnackbarHostState() }
  val playerState by container.player.state.collectAsStateWithLifecycle()

  // Top-level screens keep their state (search results, filters) while switching tabs.
  val browseViewModel: BrowseViewModel = viewModel { BrowseViewModel(container) }
  val libraryViewModel: LibraryViewModel = viewModel { LibraryViewModel(container) }

  fun selectTab(key: NavKey) {
    if (backStack.size == 1 && backStack.first() == key) return
    backStack.clear()
    backStack.add(key)
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
              onSignIn = { selectTab(AccountKey) },
            )
          }
          entry<AccountKey> { AccountScreen() }
          entry<DetailKey> { key ->
            ItemDetailScreen(
              itemKey = key.itemKey,
              onBack = { backStack.removeLastOrNull() },
              onOpenPlayer = { backStack.add(PlayerKey) },
              onSignIn = { selectTab(AccountKey) },
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
