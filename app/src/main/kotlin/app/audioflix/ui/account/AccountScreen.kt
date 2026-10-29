package app.audioflix.ui.account

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.LoginResult
import app.audioflix.ui.formatBytes
import app.audioflix.ui.update.UpdateCheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onOpenUpdate: () -> Unit) {
  val context = LocalContext.current
  val container = context.container
  val wifiOnly by container.settings.wifiOnly.collectAsStateWithLifecycle()
  val updateState by container.updates.state.collectAsStateWithLifecycle()
  var storageUsed by remember { mutableLongStateOf(-1L) }

  LaunchedEffect(Unit) {
    storageUsed = withContext(Dispatchers.IO) {
      val base = context.getExternalFilesDir(null) ?: context.filesDir
      java.io.File(base, "library").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
  }

  Scaffold(topBar = { TopAppBar(title = { Text("Account and settings") }) }) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      container.providers.all.forEach { provider -> ProviderAccount(provider) }

      HorizontalDivider()
      SectionTitle("Downloads")
      Row(
        Modifier.fillMaxWidth().toggleable(
          value = wifiOnly,
          role = Role.Switch,
          onValueChange = container.settings::setWifiOnly,
        ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text("Download only on Wi-Fi", style = MaterialTheme.typography.bodyLarge)
          Text("Applies to downloads started from now on.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = wifiOnly, onCheckedChange = null)
      }
      Text(
        if (storageUsed < 0) "Calculating storage used…" else "Library uses ${formatBytes(storageUsed)} on this device.",
        style = MaterialTheme.typography.bodyMedium,
      )

      HorizontalDivider()
      SectionTitle("App updates")
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
    }
  }
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

  SectionTitle(provider.displayName)
  if (!provider.requiresLogin) {
    Text("No account needed.")
    return
  }
  val signedIn = account
  if (signedIn != null) {
    Text("Signed in as $signedIn", style = MaterialTheme.typography.bodyLarge)
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
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
