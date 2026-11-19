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

  private val fastDownloads = mutableMapOf<String, MutableStateFlow<Boolean>>()

  /** Whether [providerId]'s downloads use several connections at once; [default] until changed. */
  fun fastDownloads(providerId: String, default: Boolean): StateFlow<Boolean> = synchronized(fastDownloads) {
    fastDownloads.getOrPut(providerId) { MutableStateFlow(prefs.getBoolean(KEY_FAST + providerId, default)) }
  }

  fun setFastDownloads(providerId: String, value: Boolean) {
    prefs.edit().putBoolean(KEY_FAST + providerId, value).apply()
    synchronized(fastDownloads) { fastDownloads.getOrPut(providerId) { MutableStateFlow(value) } }.value = value
  }

  private val _autoRewind = MutableStateFlow(
    AutoRewind(
      enabled = prefs.getBoolean(KEY_REWIND_ON, false),
      shortSeconds = prefs.getInt(KEY_REWIND_SHORT, AutoRewind.DEFAULT_SHORT),
      longSeconds = prefs.getInt(KEY_REWIND_LONG, AutoRewind.DEFAULT_LONG),
      longPauseMinutes = prefs.getInt(KEY_REWIND_AFTER, AutoRewind.DEFAULT_LONG_PAUSE),
    ),
  )

  /** Going back a little when playback resumes after a pause, so the listener can pick up the thread. */
  val autoRewind: StateFlow<AutoRewind> = _autoRewind.asStateFlow()

  fun setAutoRewind(value: AutoRewind) {
    prefs.edit()
      .putBoolean(KEY_REWIND_ON, value.enabled)
      .putInt(KEY_REWIND_SHORT, value.shortSeconds)
      .putInt(KEY_REWIND_LONG, value.longSeconds)
      .putInt(KEY_REWIND_AFTER, value.longPauseMinutes)
      .apply()
    _autoRewind.value = value
  }

  private val _updateConnections = MutableStateFlow(
    prefs.getInt(KEY_UPDATE_CONNECTIONS, UpdateConnections.DEFAULT).let { saved ->
      saved.takeIf { it in UpdateConnections.CHOICES } ?: UpdateConnections.DEFAULT
    },
  )

  /** How many connections an app update downloads over at once. */
  val updateConnections: StateFlow<Int> = _updateConnections.asStateFlow()

  fun setUpdateConnections(value: Int) {
    prefs.edit().putInt(KEY_UPDATE_CONNECTIONS, value).apply()
    _updateConnections.value = value
  }

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
    const val KEY_UPDATE_CONNECTIONS = "updates.connections"
    const val KEY_FAST = "downloads.fast."
    const val KEY_REWIND_ON = "player.rewind.enabled"
    const val KEY_REWIND_SHORT = "player.rewind.shortSeconds"
    const val KEY_REWIND_LONG = "player.rewind.longSeconds"
    const val KEY_REWIND_AFTER = "player.rewind.longPauseMinutes"
  }
}

/** The choices for [Settings.updateConnections]. */
object UpdateConnections {
  const val DEFAULT = 6
  val CHOICES = listOf(1, 2, 4, 6, 8)
}

/**
 * Auto-rewind: on resuming after a pause, go back [shortSeconds], or [longSeconds] when the pause
 * lasted [longPauseMinutes] or more.
 */
data class AutoRewind(
  val enabled: Boolean = false,
  val shortSeconds: Int = DEFAULT_SHORT,
  val longSeconds: Int = DEFAULT_LONG,
  val longPauseMinutes: Int = DEFAULT_LONG_PAUSE,
) {
  /** How far to go back after a pause of [pausedMs], in milliseconds; 0 when off. */
  fun rewindMs(pausedMs: Long): Long = when {
    !enabled || pausedMs < MIN_PAUSE_MS -> 0
    pausedMs >= longPauseMinutes * 60_000L -> longSeconds * 1_000L
    else -> shortSeconds * 1_000L
  }

  companion object {
    const val DEFAULT_SHORT = 5
    const val DEFAULT_LONG = 30
    const val DEFAULT_LONG_PAUSE = 30

    /** Pauses shorter than this (a quick double tap) don't rewind. */
    const val MIN_PAUSE_MS = 1_000L

    val SHORT_CHOICES = listOf(2, 3, 5, 10, 15, 20, 30)
    val LONG_CHOICES = listOf(10, 15, 20, 30, 45, 60, 90, 120)
    val LONG_PAUSE_CHOICES = listOf(5, 10, 15, 30, 60, 120, 240, 480)
  }
}
