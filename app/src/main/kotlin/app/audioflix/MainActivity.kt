package app.audioflix

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.audioflix.ui.AppRoot
import app.audioflix.ui.theme.AudioFlixTheme

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

    setContent { AudioFlixTheme { AppRoot() } }
  }
}
