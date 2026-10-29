package app.audioflix

import android.Manifest
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.audioflix.ui.AppRoot
import app.audioflix.ui.theme.AudioFlixTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    container.player.connect()

    // Download progress and the playback controls live in notifications.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // The system installer's confirmation screen for an in-app update.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        container.updates.confirmations.collect { intent ->
          runCatching { startActivity(intent) }
            .onFailure { container.updates.onInstallResult(PackageInstaller.STATUS_FAILURE, it.message) }
        }
      }
    }

    setContent { AudioFlixTheme { AppRoot() } }
  }
}
