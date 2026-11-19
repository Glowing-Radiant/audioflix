package app.audioflix.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.audioflix.container
import app.audioflix.data.AutoRewind
import app.audioflix.data.UpdateConnections
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.FolderProvider
import app.audioflix.provider.LoginResult
import app.audioflix.ui.DropdownSelector
import app.audioflix.ui.browse.limitReachedText
import app.audioflix.ui.browse.remainingText
import app.audioflix.ui.formatBytes
import app.audioflix.ui.update.UpdateCheckStatus
import app.audioflix.update.connectionsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pages of Settings. Sources have one page each, named "source:<provider id>". */
object SettingsSection {
  const val PLAYBACK = "playback"
  const val DOWNLOADS = "downloads"
  const val UPDATES = "updates"
  private const val SOURCE = "source:"

  fun source(providerId: String) = SOURCE + providerId

  fun providerIdOf(section: String): String? = section.takeIf { it.startsWith(SOURCE) }?.removePrefix(SOURCE)
}

/** Settings, by category: playback, downloads, each source, and app updates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenSection: (String) -> Unit) {
  val container = LocalContext.current.container
  val rewind by container.settings.autoRewind.collectAsStateWithLifecycle()
  val wifiOnly by container.settings.wifiOnly.collectAsStateWithLifecycle()
  val updateState by container.updates.state.collectAsStateWithLifecycle()

  Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Category(
        title = "Playback",
        summary = if (rewind.enabled) {
          "Auto-rewind on: ${seconds(rewind.shortSeconds)}, or ${seconds(rewind.longSeconds)} after ${minutes(rewind.longPauseMinutes)}"
        } else {
          "Auto-rewind off"
        },
        onClick = { onOpenSection(SettingsSection.PLAYBACK) },
      )
      Category(
        title = "Downloads and storage",
        summary = if (wifiOnly) "Only on Wi-Fi" else "On Wi-Fi and mobile data",
        onClick = { onOpenSection(SettingsSection.DOWNLOADS) },
      )
      Heading("Sources", Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
      container.providers.all.forEach { provider ->
        val account by provider.account.collectAsStateWithLifecycle()
        val fast = if (provider.supportsFastDownloads) {
          container.settings.fastDownloads(provider.id, provider.fastDownloadsDefault).collectAsStateWithLifecycle().value
        } else {
          null
        }
        val parts = listOfNotNull(
          when {
            !provider.requiresLogin -> null
            account != null -> "Signed in as $account"
            else -> "Not signed in"
          },
          fast?.let { if (it) "Faster downloads on" else "Faster downloads off" },
        )
        Category(
          title = provider.displayName,
          summary = parts.joinToString(", ").ifEmpty { "No account needed" },
          onClick = { onOpenSection(SettingsSection.source(provider.id)) },
        )
      }
      Heading("App", Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
      Category(
        title = "App updates",
        summary = updateState.available?.let { "Version ${it.latest.version} is available" }
          ?: "Version ${container.updates.installed}",
        onClick = { onOpenSection(SettingsSection.UPDATES) },
      )
    }
  }
}

@Composable
private fun Category(title: String, summary: String, onClick: () -> Unit) {
  ListItem(
    modifier = Modifier.clickable(onClickLabel = "Open", onClick = onClick),
    headlineContent = { Text(title) },
    supportingContent = { Text(summary) },
  )
  HorizontalDivider()
}

/** One page of Settings, by its [section] name. */
@Composable
fun SettingsSectionScreen(section: String, onBack: () -> Unit, onOpenUpdate: () -> Unit) {
  val container = LocalContext.current.container
  val providerId = SettingsSection.providerIdOf(section)
  when {
    providerId != null -> {
      val provider = container.providers[providerId]
      SettingsPage(provider.displayName, onBack) { ProviderSettings(provider) }
    }
    section == SettingsSection.PLAYBACK -> SettingsPage("Playback", onBack) { PlaybackSettings() }
    section == SettingsSection.DOWNLOADS -> SettingsPage("Downloads and storage", onBack) { DownloadSettings() }
    else -> SettingsPage("App updates", onBack) { UpdateSettings(onOpenUpdate) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      content = content,
    )
  }
}

@Composable
private fun PlaybackSettings() {
  val settings = LocalContext.current.container.settings
  val rewind by settings.autoRewind.collectAsStateWithLifecycle()

  Heading("Auto-rewind")
  SwitchRow(
    title = "Rewind when resuming",
    description = "When you press play after pausing, go back a few seconds so you can pick up where " +
      "you were. After a long pause it goes back further.",
    checked = rewind.enabled,
    onChange = { settings.setAutoRewind(rewind.copy(enabled = it)) },
  )
  if (rewind.enabled) {
    DropdownSelector(
      label = "Rewind after a short pause",
      options = AutoRewind.SHORT_CHOICES,
      selected = rewind.shortSeconds,
      optionLabel = ::seconds,
      onSelect = { settings.setAutoRewind(rewind.copy(shortSeconds = it)) },
      modifier = Modifier.fillMaxWidth(),
    )
    DropdownSelector(
      label = "Rewind after a long pause",
      options = AutoRewind.LONG_CHOICES,
      selected = rewind.longSeconds,
      optionLabel = ::seconds,
      onSelect = { settings.setAutoRewind(rewind.copy(longSeconds = it)) },
      modifier = Modifier.fillMaxWidth(),
    )
    DropdownSelector(
      label = "A long pause is",
      options = AutoRewind.LONG_PAUSE_CHOICES,
      selected = rewind.longPauseMinutes,
      optionLabel = { "${minutes(it)} or more" },
      onSelect = { settings.setAutoRewind(rewind.copy(longPauseMinutes = it)) },
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      "It also applies to pauses from phone calls, headphones being unplugged, and the app being closed. " +
        "It never goes back past the start of the current track.",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun DownloadSettings() {
  val context = LocalContext.current
  val settings = context.container.settings
  val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
  var storageUsed by remember { mutableLongStateOf(-1L) }

  LaunchedEffect(Unit) {
    storageUsed = withContext(Dispatchers.IO) {
      val base = context.getExternalFilesDir(null) ?: context.filesDir
      java.io.File(base, "library").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
  }

  SwitchRow(
    title = "Download only on Wi-Fi",
    description = "Applies to downloads started from now on. Copying from phone folders works without a connection.",
    checked = wifiOnly,
    onChange = settings::setWifiOnly,
  )
  Text(
    if (storageUsed < 0) "Calculating storage used…" else "Library uses ${formatBytes(storageUsed)} on this device.",
    style = MaterialTheme.typography.bodyMedium,
  )
  Text(
    "Faster downloads, over several connections at once, are set for each source under Sources in Settings.",
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun UpdateSettings(onOpenUpdate: () -> Unit) {
  val container = LocalContext.current.container
  val updateState by container.updates.state.collectAsStateWithLifecycle()
  Text("AudioFlix version ${container.updates.installed}", style = MaterialTheme.typography.bodyLarge)
  UpdateCheckStatus(updateState, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    if (updateState.available != null) {
      Button(onClick = onOpenUpdate) { Text("View update") }
    } else {
      OutlinedButton(onClick = container.updates::check, enabled = !updateState.checking) {
        Text("Check for updates")
      }
    }
    if (updateState.checking) CircularProgressIndicator(Modifier.padding(4.dp))
  }

  val connections by container.settings.updateConnections.collectAsStateWithLifecycle()
  HorizontalDivider()
  Heading("Downloading updates")
  DropdownSelector(
    label = "Download updates over",
    options = UpdateConnections.CHOICES,
    selected = connections,
    optionLabel = { n ->
      when (n) {
        1 -> "1 connection, slowest but steadiest"
        UpdateConnections.DEFAULT -> "${connectionsText(n)} (default)"
        else -> connectionsText(n)
      }
    },
    onSelect = container.settings::setUpdateConnections,
    modifier = Modifier.fillMaxWidth(),
  )
  Text(
    "More connections at once usually make updates download faster, but on a weak or patchy " +
      "network fewer can be more dependable. If a download keeps failing, choose fewer. Whatever you " +
      "choose, AudioFlix drops to fewer connections by itself when the connection keeps dropping, " +
      "and checks every update is complete and undamaged before installing it.",
    style = MaterialTheme.typography.bodySmall,
  )
}

/** A source's page: its account, faster downloads, and links to the people behind it. */
@Composable
private fun ProviderSettings(provider: CatalogProvider) {
  val settings = LocalContext.current.container.settings
  Heading("Account")
  ProviderAccount(provider)

  if (provider.supportsFastDownloads) {
    val fast by settings.fastDownloads(provider.id, provider.fastDownloadsDefault).collectAsStateWithLifecycle()
    HorizontalDivider()
    Heading("Downloads")
    SwitchRow(
      title = "Faster downloads",
      description = provider.fastDownloadsNote,
      checked = fast,
      onChange = { settings.setFastDownloads(provider.id, it) },
    )
    Text(
      if (provider.fastDownloadsDefault) "On unless you turn it off." else "Off unless you turn it on.",
      style = MaterialTheme.typography.bodySmall,
    )
  }

  if (provider is FolderProvider) {
    val saved by provider.savedFolders.collectAsStateWithLifecycle()
    HorizontalDivider()
    Heading(provider.savedHeading)
    Text(
      when (saved.size) {
        0 -> "None yet. Open one from Browse."
        1 -> "1 folder, listed in Browse."
        else -> "${saved.size} folders, listed in Browse."
      },
      style = MaterialTheme.typography.bodyMedium,
    )
  }

  SupportLinks(provider)
}

@Composable
private fun ProviderAccount(provider: CatalogProvider) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val account by provider.account.collectAsStateWithLifecycle()
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }

  fun signIn() {
    if (email.isBlank() || password.isEmpty() || busy) return
    busy = true
    message = null
    scope.launch {
      message = when (val result = provider.login(email.trim(), password)) {
        LoginResult.Success -> null.also { password = "" }
        LoginResult.InvalidCredentials -> "That email and password didn't work."
        is LoginResult.Error -> result.message
      }
      busy = false
    }
  }

  if (!provider.requiresLogin) {
    Text("No account needed.")
    return
  }
  val signedIn = account
  if (signedIn != null) {
    Text("Signed in as $signedIn", style = MaterialTheme.typography.bodyLarge)
    DownloadAllowance(provider)
    OutlinedButton(onClick = { scope.launch { provider.logout() } }) { Text("Sign out") }
    return
  }

  Text("Sign in to download. Browsing works without an account.", style = MaterialTheme.typography.bodyMedium)
  OutlinedTextField(
    value = email,
    onValueChange = { email = it },
    label = { Text("Email") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
  )
  OutlinedTextField(
    value = password,
    onValueChange = { password = it },
    label = { Text("Password") },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { signIn() }),
  )
  message?.let {
    Text(
      it,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
  }
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Button(onClick = ::signIn, enabled = !busy && email.isNotBlank() && password.isNotEmpty()) { Text("Sign in") }
    if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
    provider.signUpUrl?.let { url ->
      TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) {
        Text("Create an account")
      }
    }
  }
  Text(
    "Your password is only sent to ${provider.displayName} and is not stored on this device.",
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun DownloadAllowance(provider: CatalogProvider) {
  val limit = provider.dailyDownloadLimit ?: return
  val quota = LocalContext.current.container.library.quota
  val changes by quota.changes.collectAsStateWithLifecycle()
  val status = remember(changes) { quota.status(provider.id, limit) }
  Text(
    if (status.remaining == 0) limitReachedText(provider.displayName, status) else remainingText(provider.displayName, status),
    style = MaterialTheme.typography.bodyMedium,
  )
}

/** Links to the provider's own support and community pages, as thanks for its catalog. */
@Composable
private fun SupportLinks(provider: CatalogProvider) {
  if (provider.supportLinks.isEmpty()) return
  val context = LocalContext.current
  HorizontalDivider()
  Heading("Support ${provider.displayName}")
  Text(
    "AudioFlix gets its titles from ${provider.displayName}. If you enjoy them, consider supporting ${provider.displayName}.",
    style = MaterialTheme.typography.bodyMedium,
  )
  provider.supportLinks.forEach { link ->
    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri())) }) {
      Text(link.label)
    }
  }
}

/** A setting that's on or off: the whole row is one switch for TalkBack, read with its description. */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(description, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = checked, onCheckedChange = null)
  }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.semantics { heading() },
  )
}

private fun seconds(n: Int) = if (n == 1) "1 second" else "$n seconds"

private fun minutes(n: Int) = when {
  n % 60 == 0 && n >= 60 -> if (n == 60) "1 hour" else "${n / 60} hours"
  n == 1 -> "1 minute"
  else -> "$n minutes"
}
