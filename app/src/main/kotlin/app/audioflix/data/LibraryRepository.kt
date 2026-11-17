package app.audioflix.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.audioflix.chapters.ChapterReader
import app.audioflix.chapters.Mp4Chapters
import app.audioflix.download.DownloadQuota
import app.audioflix.download.DownloadWorker
import app.audioflix.download.MediaInstaller
import app.audioflix.download.NaturalOrder
import app.audioflix.download.QuotaStatus
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.CatalogProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface DownloadResult {
  data object Queued : DownloadResult
  data class LimitReached(val status: QuotaStatus) : DownloadResult
}

sealed interface StreamResult {
  /** [skippedArchives] zips were left out: they can only be downloaded. */
  data class Added(val trackCount: Int, val skippedArchives: Int) : StreamResult
  data object AlreadyInLibrary : StreamResult

  /** Nothing here can be streamed; [archives] zips could still be downloaded. */
  data class NothingToStream(val archives: Int) : StreamResult
}

class LibraryRepository(
  private val context: Context,
  private val dao: LibraryDao,
  private val settings: Settings,
  val quota: DownloadQuota,
  private val chapters: ChapterReader,
) {
  private val workManager get() = WorkManager.getInstance(context)

  val items: Flow<List<LibraryItemWithTracks>> = dao.observeAll()

  fun observe(key: String): Flow<LibraryItemWithTracks?> = dao.observe(key)

  suspend fun get(key: String): LibraryItemWithTracks? = dao.get(key)

  /**
   * Adds [item] to the library and starts downloading it, or downloads it if it's in the library
   * for streaming. A new download counts against [dailyLimit]; retrying one that failed doesn't.
   */
  suspend fun download(item: CatalogItem, dailyLimit: Int?): DownloadResult {
    val existing = dao.getItem(item.key)
    if (existing != null) return downloadExisting(existing, dailyLimit)
    if (dailyLimit != null && !quota.tryUse(item.providerId, dailyLimit)) {
      return DownloadResult.LimitReached(quota.status(item.providerId, dailyLimit))
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
    return DownloadResult.Queued
  }

  /** Downloads a library item that is only streamed so far, or retries a failed download. */
  suspend fun download(key: String, dailyLimit: Int?): DownloadResult {
    val existing = dao.getItem(key) ?: return DownloadResult.Queued
    return downloadExisting(existing, dailyLimit)
  }

  private suspend fun downloadExisting(existing: LibraryItem, dailyLimit: Int?): DownloadResult {
    when (existing.status) {
      DownloadStatus.FAILED -> retry(existing.key)
      DownloadStatus.ONLINE -> {
        if (dailyLimit != null && !quota.tryUse(existing.providerId, dailyLimit)) {
          return DownloadResult.LimitReached(quota.status(existing.providerId, dailyLimit))
        }
        retry(existing.key)
      }
      else -> {}
    }
    return DownloadResult.Queued
  }

  /**
   * Adds [item] to the library without downloading it: its audio files become tracks that play
   * straight from [provider]. Zips can't be streamed, so they're left out. Audiobook files are
   * split into their chapters, read from a few byte ranges of each file.
   */
  suspend fun addForStreaming(item: CatalogItem, provider: CatalogProvider): StreamResult {
    if (dao.getItem(item.key) != null) return StreamResult.AlreadyInLibrary
    val files = provider.files(item.remoteId) ?: return StreamResult.NothingToStream(0)
    val archives = files.count { MediaInstaller.isArchiveName(it.relativePath) }
    val audio = files
      .filter { MediaInstaller.isAudioName(it.relativePath) && provider.streamUrl(it.id) != null }
      .sortedWith(compareBy(NaturalOrder) { it.relativePath })
    if (audio.isEmpty()) return StreamResult.NothingToStream(archives)
    dao.insert(
      LibraryItem(
        key = item.key,
        providerId = item.providerId,
        remoteId = item.remoteId,
        title = item.title,
        kind = item.kind,
        description = item.description,
        status = DownloadStatus.ONLINE,
        localDir = itemDir(item.providerId, item.remoteId).path,
        addedAt = System.currentTimeMillis(),
      ),
    )
    val infos = coroutineScope {
      val permits = Semaphore(4)
      audio.map { file ->
        async {
          val url = provider.streamUrl(file.id)
          if (url != null && Mp4Chapters.isMp4Name(file.relativePath)) {
            permits.withPermit { chapters.readRemote(url) }
          } else {
            null
          }
        }
      }.awaitAll()
    }
    val tracks = mutableListOf<Track>()
    audio.forEachIndexed { i, file ->
      val info = infos[i]
      tracks += MediaInstaller.tracksForFile(
        itemKey = item.key,
        firstIndex = tracks.size,
        relativePath = file.relativePath,
        // Unknown for other formats until played; the player fills it in then.
        durationMs = info?.durationMs ?: 0,
        chapters = info?.chapters.orEmpty(),
        remoteFileId = file.id,
      )
    }
    dao.insertTracks(tracks)
    return StreamResult.Added(tracks.size, archives)
  }

  /**
   * Stops a download, or removes downloaded files, of an item that can be streamed: it stays in the
   * library with its listening progress. Items that can't be streamed are deleted instead.
   */
  suspend fun removeDownload(key: String) {
    val entry = dao.get(key) ?: return
    if (!entry.streamable) return delete(key)
    runCatching { workManager.cancelUniqueWork(workName(key)).result.await() }
    dao.setStatus(key, DownloadStatus.ONLINE)
    dao.setProgress(key, 0, -1)
    withContext(Dispatchers.IO) { File(entry.item.localDir).deleteRecursively() }
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
