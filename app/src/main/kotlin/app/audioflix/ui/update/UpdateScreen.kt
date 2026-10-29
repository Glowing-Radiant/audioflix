package app.audioflix.ui.update

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.audioflix.container
import app.audioflix.ui.formatBytes
import app.audioflix.update.AvailableUpdate
import app.audioflix.update.InstallStep
import app.audioflix.update.NotesBlock
import app.audioflix.update.Release
import app.audioflix.update.UpdateManager
import app.audioflix.update.UpdateState
import app.audioflix.update.parseReleaseNotes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
  val updates = LocalContext.current.container.updates
  val state by updates.state.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("App update") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      val available = state.available
      if (available == null) {
        NoUpdate(state, updates)
      } else {
        UpdateSummary(available, updates)
        InstallControls(state.install, updates, onLater = onBack)
        HorizontalDivider()
        Changelog(available.releases)
      }
    }
  }
}

@Composable
private fun NoUpdate(state: UpdateState, updates: UpdateManager) {
  Text("AudioFlix ${updates.installed}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
  UpdateCheckStatus(state, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Button(onClick = updates::check, enabled = !state.checking) { Text("Check for updates") }
    if (state.checking) CircularProgressIndicator(Modifier.padding(4.dp))
  }
}

/** One line saying what the last update check found. Shared with the Account screen. */
@Composable
fun UpdateCheckStatus(state: UpdateState, modifier: Modifier = Modifier) {
  val text = when {
    state.checking -> "Checking for updates…"
    state.checkError != null -> "Couldn't check for updates. ${state.checkError}"
    state.available != null -> "Version ${state.available.latest.version} is available."
    state.upToDate -> "You have the latest version."
    else -> "Not checked yet."
  }
  Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = if (state.checkError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    modifier = modifier,
  )
}

@Composable
private fun UpdateSummary(update: AvailableUpdate, updates: UpdateManager) {
  val latest = update.latest
  Text(
    "AudioFlix ${latest.version} is available",
    style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.semantics { heading() },
  )
  val details = buildList {
    add("You have version ${updates.installed}.")
    if (latest.apkSize > 0) add("Download size ${formatBytes(latest.apkSize)}.")
    if (update.releases.size > 1) add("This update includes ${update.releases.size} releases.")
  }
  Text(details.joinToString(" "), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun InstallControls(step: InstallStep, updates: UpdateManager, onLater: () -> Unit) {
  val context = LocalContext.current
  var canInstall by remember { mutableStateOf(updates.canInstallPackages()) }
  // The permission is granted on a system settings screen; re-check on return.
  LifecycleResumeEffect(Unit) {
    canInstall = updates.canInstallPackages()
    onPauseOrDispose { }
  }

  when (step) {
    is InstallStep.Downloading -> {
      // Announce in steps of ten so the screen reader isn't interrupted constantly.
      val spoken = step.percent / 10 * 10
      Text(
        if (step.totalBytes > 0) "Downloading update, $spoken%" else "Downloading update",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
      LinearProgressIndicator(
        progress = { step.percent / 100f },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
      )
      OutlinedButton(onClick = updates::cancelDownload) { Text("Cancel download") }
    }
    InstallStep.Installing -> Text(
      "Confirm the installation when Android asks. AudioFlix closes while it updates, and a " +
        "notification lets you open it again.",
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    InstallStep.Idle, is InstallStep.Failed -> {
      if (step is InstallStep.Failed) {
        Text(
          step.message,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
      }
      if (!canInstall) {
        Text(
          "To install updates, Android needs your permission for AudioFlix to install apps. " +
            "Choose Allow installs, turn on Allow from this source, then come back.",
          style = MaterialTheme.typography.bodyLarge,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canInstall) {
          Button(onClick = updates::install) {
            Text(if (step is InstallStep.Failed) "Try again" else "Download and install")
          }
        } else {
          Button(onClick = {
            context.startActivity(
              Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
            )
          }) { Text("Allow installs") }
        }
        TextButton(onClick = onLater) { Text("Later") }
      }
    }
  }
}

@Composable
private fun Changelog(releases: List<Release>) {
  Text("What's new", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
  releases.forEach { release ->
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      val date = release.publishedAt?.let(::formatDate)
      Text(
        "Version ${release.version}",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
      )
      date?.let { Text("Released $it", style = MaterialTheme.typography.bodyMedium) }
      val blocks = remember(release.notes) { parseReleaseNotes(release.notes) }
      if (blocks.isEmpty()) Text("No release notes.", style = MaterialTheme.typography.bodyLarge)
      blocks.forEach { block ->
        when (block) {
          is NotesBlock.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
          )
          is NotesBlock.Bullet -> Row {
            // The bullet is decoration; each item is read as its own line.
            Text("•", modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics { })
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
          }
          is NotesBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
}

private fun formatDate(iso: String): String? = runCatching {
  DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrNull()
