package app.audioflix.ui.library

import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItemWithTracks
import app.audioflix.provider.MediaKind
import app.audioflix.ui.formatBytes
import kotlin.math.roundToInt

fun kindLabel(kind: MediaKind?): String? = when (kind) {
  MediaKind.MOVIE -> "Movie"
  MediaKind.SHOW -> "TV show"
  null -> null
}

/** One-line status for a library entry: download progress, or how far the user has listened. */
fun statusSummary(entry: LibraryItemWithTracks): String {
  val item = entry.item
  return when (item.status) {
    DownloadStatus.QUEUED -> item.error ?: "Waiting to download"
    DownloadStatus.DOWNLOADING -> if (item.totalBytes > 0) {
      "Downloading ${downloadPercent(entry)}%, ${formatBytes(item.bytesDownloaded)} of ${formatBytes(item.totalBytes)}"
    } else {
      "Downloading, ${formatBytes(item.bytesDownloaded)}"
    }
    DownloadStatus.EXTRACTING -> "Unpacking files"
    DownloadStatus.FAILED -> "Download failed: ${item.error ?: "unknown error"}"
    DownloadStatus.COMPLETED -> {
      val count = entry.tracks.size
      val parts = mutableListOf<String>()
      if (count > 1) parts += "$count episodes"
      parts += when {
        item.finished -> "Finished"
        entry.listenedMs <= 0 -> "Not started"
        else -> "${(entry.listenedFraction * 100).roundToInt().coerceIn(1, 99)}% listened"
      }
      if (count > 1 && !item.finished && entry.listenedMs > 0) {
        parts += "up to episode ${item.currentTrackIndex + 1}"
      }
      parts.joinToString(", ")
    }
  }
}

fun downloadPercent(entry: LibraryItemWithTracks): Int {
  val item = entry.item
  return if (item.totalBytes > 0) (item.bytesDownloaded * 100 / item.totalBytes).toInt().coerceIn(0, 100) else 0
}
