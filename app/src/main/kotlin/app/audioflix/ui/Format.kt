package app.audioflix.ui

import java.util.Locale

/** "1:02:03" or "12:34". */
fun formatDuration(ms: Long): String {
  val totalSeconds = (ms.coerceAtLeast(0) / 1000)
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** Screen-reader friendly duration, e.g. "1 hour 2 minutes". */
fun spokenDuration(ms: Long): String {
  val totalSeconds = ms.coerceAtLeast(0) / 1000
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  val parts = buildList {
    if (h > 0) add(plural(h, "hour"))
    if (m > 0) add(plural(m, "minute"))
    if (h == 0L && (s > 0 || m == 0L)) add(plural(s, "second"))
  }
  return parts.joinToString(" ")
}

private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "?"
  val units = listOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
