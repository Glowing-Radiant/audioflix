package app.audioflix.download

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much of a provider's daily download allowance is left. */
data class QuotaStatus(
  val limit: Int,
  val used: Int,
  /** When the oldest counted download leaves the window, or null when nothing is counted. */
  val nextFreeAt: Long?,
) {
  val remaining: Int get() = (limit - used).coerceAtLeast(0)
}

/**
 * Counts new downloads per provider over a rolling 24 hours, so the app never asks for more than a
 * provider allows in a day, whenever its own day resets. Retries of a failed download aren't
 * counted again.
 */
class DownloadQuota(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
  private val _changes = MutableStateFlow(0)

  /** Changes whenever a download is counted, so screens can re-read [status]. */
  val changes: StateFlow<Int> = _changes.asStateFlow()

  fun status(providerId: String, limit: Int): QuotaStatus = quotaStatus(limit, starts(providerId), clock())

  /** Counts one download and returns true, or returns false when the limit is already reached. */
  @Synchronized
  fun tryUse(providerId: String, limit: Int): Boolean {
    val now = clock()
    val starts = starts(providerId)
    if (starts.size >= limit) return false
    prefs.edit().putString(key(providerId), (starts + now).joinToString(",")).apply()
    _changes.value++
    return true
  }

  private fun starts(providerId: String) = recentStarts(prefs.getString(key(providerId), null), clock())

  private fun key(providerId: String) = "quota.$providerId"
}

internal const val QUOTA_WINDOW_MS = 24 * 60 * 60 * 1000L

/** The stored start times still inside the window, oldest first. */
internal fun recentStarts(stored: String?, now: Long): List<Long> =
  stored.orEmpty().split(',')
    .mapNotNull { it.trim().toLongOrNull() }
    .filter { it in (now - QUOTA_WINDOW_MS + 1)..now }
    .sorted()

internal fun quotaStatus(limit: Int, starts: List<Long>, now: Long): QuotaStatus {
  val recent = starts.filter { it > now - QUOTA_WINDOW_MS }
  return QuotaStatus(limit = limit, used = recent.size, nextFreeAt = recent.minOrNull()?.plus(QUOTA_WINDOW_MS))
}
