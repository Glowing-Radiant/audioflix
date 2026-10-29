package app.audioflix.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import app.audioflix.BuildConfig
import app.audioflix.MainActivity
import app.audioflix.container

/** Receives the result of an install session started by [UpdateManager]. */
class InstallResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val updates = context.container.updates
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        ?.let(updates::requestConfirmation)
    }
    updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
  }
}

/**
 * The installer closes AudioFlix while replacing it, so after an update started from the app this
 * posts a notification that says it worked and reopens the app.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val settings = context.container.settings
    val expected = settings.pendingUpdateVersion ?: return
    settings.pendingUpdateVersion = null
    if (expected != BuildConfig.VERSION_NAME) return

    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val open = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("AudioFlix updated to ${BuildConfig.VERSION_NAME}")
      .setContentText("Tap to open AudioFlix.")
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
      runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
  }

  private companion object {
    const val CHANNEL = "updates"
    const val NOTIFICATION_ID = 7001
  }
}
