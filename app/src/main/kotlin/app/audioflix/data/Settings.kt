package app.audioflix.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(private val prefs: SharedPreferences) {
  private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, false))
  val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

  private val _playbackSpeed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1f))
  val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

  /** The latest version the update screen was opened for automatically, so it opens only once. */
  var updatePromptedVersion: String?
    get() = prefs.getString(KEY_UPDATE_PROMPTED, null)
    set(value) = prefs.edit().putString(KEY_UPDATE_PROMPTED, value).apply()

  /** The version being installed by the updater, until the new version starts. */
  var pendingUpdateVersion: String?
    get() = prefs.getString(KEY_UPDATE_PENDING, null)
    // Written synchronously: the installer may kill the process right after.
    set(value) {
      prefs.edit().putString(KEY_UPDATE_PENDING, value).commit()
    }

  fun setWifiOnly(value: Boolean) {
    prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    _wifiOnly.value = value
  }

  fun setPlaybackSpeed(value: Float) {
    prefs.edit().putFloat(KEY_SPEED, value).apply()
    _playbackSpeed.value = value
  }

  private companion object {
    const val KEY_WIFI_ONLY = "downloads.wifiOnly"
    const val KEY_SPEED = "player.speed"
    const val KEY_UPDATE_PROMPTED = "updates.promptedVersion"
    const val KEY_UPDATE_PENDING = "updates.pendingVersion"
  }
}
