package app.audioflix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.audioflix.provider.MediaKind
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
  @Transaction
  @Query("SELECT * FROM library_items ORDER BY COALESCE(lastPlayedAt, addedAt) DESC")
  fun observeAll(): Flow<List<LibraryItemWithTracks>>

  @Transaction
  @Query("SELECT * FROM library_items WHERE `key` = :key")
  fun observe(key: String): Flow<LibraryItemWithTracks?>

  @Transaction
  @Query("SELECT * FROM library_items WHERE `key` = :key")
  suspend fun get(key: String): LibraryItemWithTracks?

  @Query("SELECT * FROM library_items WHERE `key` = :key")
  suspend fun getItem(key: String): LibraryItem?

  @Query("SELECT * FROM library_items WHERE status IN ('QUEUED', 'DOWNLOADING', 'EXTRACTING')")
  suspend fun pending(): List<LibraryItem>

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(item: LibraryItem)

  @Query("UPDATE library_items SET status = :status, error = :error WHERE `key` = :key")
  suspend fun setStatus(key: String, status: DownloadStatus, error: String? = null)

  @Query("UPDATE library_items SET bytesDownloaded = :downloaded, totalBytes = :total WHERE `key` = :key")
  suspend fun setProgress(key: String, downloaded: Long, total: Long)

  @Query("UPDATE library_items SET kind = :kind WHERE `key` = :key")
  suspend fun setKind(key: String, kind: MediaKind)

  @Query("UPDATE library_items SET lastPlayedAt = :at, currentTrackIndex = :trackIndex WHERE `key` = :key")
  suspend fun setPlayed(key: String, trackIndex: Int, at: Long)

  @Query("UPDATE library_items SET finished = :finished WHERE `key` = :key")
  suspend fun setFinished(key: String, finished: Boolean)

  @Query("DELETE FROM library_items WHERE `key` = :key")
  suspend fun delete(key: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTracks(tracks: List<Track>)

  @Query("DELETE FROM tracks WHERE itemKey = :key")
  suspend fun deleteTracks(key: String)

  @Query("UPDATE tracks SET positionMs = :positionMs WHERE itemKey = :key AND `index` = :index")
  suspend fun setTrackPosition(key: String, index: Int, positionMs: Long)

  @Query("UPDATE tracks SET completed = :completed, positionMs = :positionMs WHERE itemKey = :key AND `index` = :index")
  suspend fun setTrackCompleted(key: String, index: Int, completed: Boolean, positionMs: Long)

  @Query("UPDATE tracks SET durationMs = :durationMs WHERE itemKey = :key AND `index` = :index AND durationMs <= 0")
  suspend fun fillTrackDuration(key: String, index: Int, durationMs: Long)

  @Query("UPDATE tracks SET completed = :completed, positionMs = 0 WHERE itemKey = :key")
  suspend fun setAllTracksCompleted(key: String, completed: Boolean)

  @Transaction
  suspend fun replaceTracks(key: String, tracks: List<Track>) {
    deleteTracks(key)
    insertTracks(tracks)
  }
}
