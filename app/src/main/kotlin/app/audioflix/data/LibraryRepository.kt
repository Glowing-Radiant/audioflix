package app.audioflix.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.audioflix.download.DownloadWorker
import app.audioflix.provider.CatalogItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

class LibraryRepository(
  private val context: Context,
  private val dao: LibraryDao,
  private val settings: Settings,
) {
  private val workManager get() = WorkManager.getInstance(context)

  val items: Flow<List<LibraryItemWithTracks>> = dao.observeAll()

  fun observe(key: String): Flow<LibraryItemWithTracks?> = dao.observe(key)

  suspend fun get(key: String): LibraryItemWithTracks? = dao.get(key)

  /** Adds [item] to the library and starts downloading it. Failed items are retried. */
  suspend fun download(item: CatalogItem) {
    val existing = dao.getItem(item.key)
    if (existing != null) {
      if (existing.status == DownloadStatus.FAILED) retry(item.key)
      return
    }
    dao.insert(
      LibraryItem(
        key = item.key,
        providerId = item.providerId,
        remoteId = item.remoteId,
        title = item.title,
        kind = item.kind,
        description = item.description,
        status = DownloadStatus.QUEUED,
        localDir = itemDir(item.providerId, item.remoteId).path,
        addedAt = System.currentTimeMillis(),
      ),
    )
    enqueue(item.key)
  }

  suspend fun retry(key: String) {
    dao.setStatus(key, DownloadStatus.QUEUED)
    enqueue(key)
  }

  /** Re-queues downloads that were interrupted, e.g. by the app being killed. */
  suspend fun resumePending() {
    dao.pending().forEach { enqueue(it.key, ExistingWorkPolicy.KEEP) }
  }

  /** Removes the item, cancelling any download and deleting its files. */
  suspend fun delete(key: String) {
    val item = dao.getItem(key) ?: return
    runCatching { workManager.cancelUniqueWork(workName(key)).result.await() }
    dao.delete(key)
    withContext(Dispatchers.IO) { File(item.localDir).deleteRecursively() }
  }

  suspend fun setFinished(key: String, finished: Boolean) {
    dao.setFinished(key, finished)
    dao.setAllTracksCompleted(key, finished)
    if (!finished) dao.setPlayed(key, 0, System.currentTimeMillis())
  }

  suspend fun setTrackCompleted(key: String, index: Int, completed: Boolean) {
    dao.setTrackCompleted(key, index, completed, 0)
    if (!completed) dao.setFinished(key, false)
  }

  // Playback progress, written by the playback service.

  suspend fun savePosition(key: String, index: Int, positionMs: Long, durationMs: Long) {
    if (durationMs > 0) dao.fillTrackDuration(key, index, durationMs)
    dao.setTrackPosition(key, index, positionMs)
    dao.setPlayed(key, index, System.currentTimeMillis())
  }

  suspend fun completeTrack(key: String, index: Int, isLast: Boolean) {
    dao.setTrackCompleted(key, index, true, 0)
    if (isLast) {
      dao.setFinished(key, true)
    } else {
      dao.setPlayed(key, index + 1, System.currentTimeMillis())
    }
  }

  private fun enqueue(key: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
      .setInputData(workDataOf(DownloadWorker.KEY_ITEM to key))
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
          .build(),
      )
      .addTag(DownloadWorker.TAG)
      .build()
    workManager.enqueueUniqueWork(workName(key), policy, request)
  }

  private fun workName(key: String) = "download:$key"

  private fun itemDir(providerId: String, remoteId: String): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "library/$providerId/$remoteId")
  }
}
