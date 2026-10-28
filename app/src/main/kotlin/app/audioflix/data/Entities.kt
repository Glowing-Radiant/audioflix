package app.audioflix.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.audioflix.provider.MediaKind

enum class DownloadStatus { QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, FAILED }

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

/** One playable audio file of a library item: a movie has one, a show has one per episode. */
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
)

data class LibraryItemWithTracks(
  @Embedded val item: LibraryItem,
  @Relation(parentColumn = "key", entityColumn = "itemKey") val tracks: List<Track>,
) {
  val sortedTracks: List<Track> get() = tracks.sortedBy { it.index }

  val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }

  /** Listened time across all tracks; completed tracks count in full. */
  val listenedMs: Long
    get() = tracks.sumOf { if (it.completed) it.durationMs else it.positionMs.coerceAtMost(it.durationMs) }

  val listenedFraction: Float
    get() = if (item.finished) 1f else totalDurationMs.takeIf { it > 0 }?.let { listenedMs.toFloat() / it } ?: 0f
}
