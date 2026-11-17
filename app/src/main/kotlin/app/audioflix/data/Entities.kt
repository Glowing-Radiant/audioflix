package app.audioflix.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.audioflix.provider.MediaKind

/**
 * Where a library item's audio is. [ONLINE] items aren't downloaded: their tracks stream from the
 * provider, and they can still be downloaded later (going through the other states).
 */
enum class DownloadStatus { QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, FAILED, ONLINE }

/** A title the user added to their library: its download state and overall listening state. */
@Entity(tableName = "library_items")
data class LibraryItem(
  /** "<providerId>:<remoteId>", see [app.audioflix.provider.libraryKey]. */
  @PrimaryKey val key: String,
  val providerId: String,
  val remoteId: String,
  val title: String,
  val kind: MediaKind?,
  val description: String?,
  val status: DownloadStatus,
  val bytesDownloaded: Long = 0,
  val totalBytes: Long = -1,
  val error: String? = null,
  /** Absolute directory holding this title's audio files. */
  val localDir: String,
  val addedAt: Long,
  val lastPlayedAt: Long? = null,
  /** Index into [Track.index] of the track to resume. */
  val currentTrackIndex: Int = 0,
  val finished: Boolean = false,
)

/**
 * One playable part of a library item: a movie has one, a show has one per episode, and an
 * audiobook file with chapters has one per chapter, each a slice ([startMs] to [endMs]) of the file.
 */
@Entity(
  tableName = "tracks",
  primaryKeys = ["itemKey", "index"],
  foreignKeys = [
    ForeignKey(
      entity = LibraryItem::class,
      parentColumns = ["key"],
      childColumns = ["itemKey"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("itemKey")],
)
data class Track(
  val itemKey: String,
  val index: Int,
  val title: String,
  /** Path relative to [LibraryItem.localDir]. */
  val relativePath: String,
  val durationMs: Long,
  val positionMs: Long = 0,
  val completed: Boolean = false,
  /** The provider's id for this file, for tracks that can be streamed when not downloaded. */
  val remoteFileId: String? = null,
  /** Where this track starts in its file; non-zero for chapters after the first. */
  @ColumnInfo(defaultValue = "0") val startMs: Long = 0,
  /** Where it ends in its file, or null for the end of the file. */
  val endMs: Long? = null,
) {
  /** True when this track is a chapter: a slice of a longer file. */
  val isChapter: Boolean get() = startMs > 0 || endMs != null
}

data class LibraryItemWithTracks(
  @Embedded val item: LibraryItem,
  @Relation(parentColumn = "key", entityColumn = "itemKey") val tracks: List<Track>,
) {
  val sortedTracks: List<Track> get() = tracks.sortedBy { it.index }

  /** Some tracks are chapters of a longer file. */
  val hasChapters: Boolean get() = tracks.any { it.isChapter }

  /** What one track is called: chapters for audiobooks, episodes for shows, tracks otherwise. */
  val trackNoun: String
    get() = when {
      hasChapters -> "chapter"
      item.kind == null -> "track"
      else -> "episode"
    }

  /** More than one file, so chapters are shown under the file they belong to. */
  val hasSeveralFiles: Boolean get() = tracks.distinctBy { it.relativePath }.size > 1

  /** True when the tracks can be streamed, so the item plays before (or without) downloading. */
  val streamable: Boolean get() = tracks.isNotEmpty() && tracks.all { it.remoteFileId != null }

  /** Downloaded, or streamable. */
  val playable: Boolean get() = item.status == DownloadStatus.COMPLETED || streamable

  /** A download is waiting, running or unpacking. */
  val downloading: Boolean
    get() = item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING ||
      item.status == DownloadStatus.EXTRACTING

  val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }

  /** Listened time across all tracks; completed tracks count in full. */
  val listenedMs: Long
    get() = tracks.sumOf { if (it.completed) it.durationMs else it.positionMs.coerceAtMost(it.durationMs) }

  val listenedFraction: Float
    get() = if (item.finished) 1f else totalDurationMs.takeIf { it > 0 }?.let { listenedMs.toFloat() / it } ?: 0f
}
