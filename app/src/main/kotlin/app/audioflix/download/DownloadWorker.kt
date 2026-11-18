package app.audioflix.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.audioflix.AudioFlixApp
import app.audioflix.data.DownloadStatus
import app.audioflix.data.LibraryItem
import app.audioflix.data.Track
import app.audioflix.provider.AuthRequiredException
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.DownloadStream
import app.audioflix.provider.MediaKind
import app.audioflix.provider.RemoteFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import app.audioflix.net.HttpStatusException
import app.audioflix.net.NotTheFileException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Downloads one library item to its directory, resuming partial files when the server allows,
 * then unpacks it and records its tracks. An item is either one file (a movie, or a zip of
 * episodes) or, for folder-based providers, several files kept in their folder layout.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val container = (context.applicationContext as AudioFlixApp).container
  private val dao = container.database.libraryDao()

  override suspend fun doWork(): Result {
    val key = inputData.getString(KEY_ITEM) ?: return Result.failure()
    val item = dao.getItem(key) ?: return Result.success() // deleted meanwhile
    if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.ONLINE) return Result.success()

    val notificationId = key.hashCode()
    runCatching { setForeground(foregroundInfo(notificationId, item.title, 0, 0)) }

    val dir = File(item.localDir).apply { mkdirs() }
    return try {
      dao.setStatus(key, DownloadStatus.DOWNLOADING)
      val provider = container.providers[item.providerId]
      val files = provider.files(item.remoteId)
      val singleFileName = if (files == null) {
        downloadSingle(item, provider, dir, notificationId)
      } else {
        downloadFiles(item, provider, files, dir, notificationId)
        null
      }

      if (dao.getItem(key) == null) return Result.success() // deleted while downloading
      dao.setStatus(key, DownloadStatus.EXTRACTING)
      val tracks = withContext(Dispatchers.IO) {
        if (files == null) {
          val archive = MediaInstaller.install(File(dir, MediaInstaller.PART_FILE), dir, singleFileName, item.title)
          if (item.kind == null) dao.setKind(key, if (archive) MediaKind.SHOW else MediaKind.MOVIE)
        } else {
          MediaInstaller.extractArchives(dir)
        }
        MediaInstaller.scanTracks(dir, key)
      }
      if (tracks.isEmpty()) {
        dao.setStatus(key, DownloadStatus.FAILED, "The download didn't contain any audio files")
        return Result.failure()
      }
      saveTracks(item, tracks, files)
      dao.setStatus(key, DownloadStatus.COMPLETED)
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthRequiredException) {
      dao.setStatus(key, DownloadStatus.FAILED, e.message)
      Result.failure()
    } catch (e: Exception) {
      val message = e.message ?: e.javaClass.simpleName
      if (e is IOException && runAttemptCount < MAX_RETRIES) {
        dao.setStatus(key, DownloadStatus.QUEUED, "Retrying: $message")
        Result.retry()
      } else {
        dao.setStatus(key, DownloadStatus.FAILED, message)
        Result.failure()
      }
    }
  }

  /** Downloads the item's single file to [MediaInstaller.PART_FILE]; returns the file's name. */
  private suspend fun downloadSingle(item: LibraryItem, provider: CatalogProvider, dir: File, notificationId: Int): String? {
    val part = File(dir, MediaInstaller.PART_FILE)
    fastDownload(provider, item.remoteId, part) { downloaded, total ->
      report(item, notificationId, downloaded, total)
    }?.let { return it.fileName }
    val resumeFrom = if (part.exists()) part.length() else 0L
    val stream = provider.openDownload(item.remoteId, resumeFrom)
    stream.use {
      copyToPart(stream, part, resumeFrom) { downloaded, total -> report(item, notificationId, downloaded, total) }
    }
    return stream.fileName
  }

  /**
   * Downloads each of [files] into its place under [dir]. Finished files are kept between attempts,
   * so a retry only fetches what's missing.
   */
  private suspend fun downloadFiles(
    item: LibraryItem,
    provider: CatalogProvider,
    files: List<RemoteFile>,
    dir: File,
    notificationId: Int,
  ) {
    if (files.isEmpty()) throw IOException("There are no audio files here to download")
    val root = dir.canonicalFile
    val targets = files.map { file ->
      val target = File(root, file.relativePath).canonicalFile
      // Paths come from the provider; never write outside the item directory.
      if (!target.path.startsWith(root.path + File.separator)) throw IOException("Unsafe file name: ${file.relativePath}")
      file to target
    }
    val pending = targets.filter { (_, target) -> !target.exists() }
    val doneBytes = targets.filter { (_, target) -> target.exists() }.sumOf { (_, target) -> target.length() }

    // Sizes up front, so progress is for the whole title rather than restarting with each file.
    val sizes = coroutineScope {
      val permits = Semaphore(4)
      pending.map { (file, _) -> async { permits.withPermit { provider.fileSize(file.id) } } }.awaitAll()
    }
    val total = if (sizes.all { it > 0 }) doneBytes + sizes.sum() else -1L

    var finishedBytes = doneBytes
    for ((file, target) in pending) {
      target.parentFile?.mkdirs()
      val part = File(target.path + PART_SUFFIX)
      val base = finishedBytes
      val fast = fastDownload(provider, file.id, part) { downloaded, _ ->
        report(item, notificationId, base + downloaded, total)
      }
      val written = if (fast != null) {
        part.length()
      } else {
        val resumeFrom = if (part.exists()) part.length() else 0L
        val stream = provider.openDownload(file.id, resumeFrom)
        stream.use {
          copyToPart(stream, part, resumeFrom) { downloaded, _ ->
            report(item, notificationId, base + downloaded, total)
          }
        }
      }
      if (!part.renameTo(target)) throw IOException("Couldn't move ${target.name} into place")
      finishedBytes += written
    }
    report(item, notificationId, finishedBytes, total, force = true)
  }

  private class FastResult(val fileName: String?)

  /**
   * Downloads [fileId] into [part] over several connections, when [provider] supports it and the
   * user has faster downloads on for it. Returns null when the file should be downloaded the usual
   * way instead: the setting is off, or the server sent a page (a sign-in or warning page) rather
   * than the file, which the provider's own download knows how to handle.
   */
  private suspend fun fastDownload(
    provider: CatalogProvider,
    fileId: String,
    part: File,
    onProgress: suspend (downloaded: Long, total: Long) -> Unit,
  ): FastResult? {
    val downloader = container.mediaDownloader
    val log = downloader.logFile(part)
    val enabled = provider.supportsFastDownloads &&
      container.settings.fastDownloads(provider.id, provider.fastDownloadsDefault).value
    if (enabled) {
      val url = provider.rangedDownloadUrl(fileId)
      if (url != null) {
        try {
          val name = withProgressTicker(onProgress) { setProgress -> downloader.download(url, part, setProgress) }
          return FastResult(name)
        } catch (e: NotTheFileException) {
          // Fall through to the usual download.
        } catch (e: HttpStatusException) {
          if (e.code !in 400..499) throw e
        }
      }
    }
    // A piece-by-piece partial file can't be resumed from its end: start the usual download afresh.
    if (log.exists()) {
      log.delete()
      part.delete()
    }
    return null
  }

  /**
   * Runs [block], which reports progress from several threads at once, and passes the latest
   * progress to [onTick] a few times a second from this coroutine.
   */
  private suspend fun <T> withProgressTicker(
    onTick: suspend (Long, Long) -> Unit,
    block: suspend (setProgress: (Long, Long) -> Unit) -> T,
  ): T = coroutineScope {
    val done = AtomicLong(0)
    val total = AtomicLong(-1)
    val ticker = launch {
      while (true) {
        delay(750)
        onTick(done.get(), total.get())
      }
    }
    try {
      block { d, t ->
        done.set(d)
        total.set(t)
      }.also { onTick(done.get(), total.get()) }
    } finally {
      ticker.cancel()
    }
  }

  private var lastReport = 0L

  private suspend fun report(item: LibraryItem, notificationId: Int, downloaded: Long, total: Long, force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - lastReport < 750) return
    lastReport = now
    dao.setProgress(item.key, downloaded, total)
    runCatching { setForeground(foregroundInfo(notificationId, item.title, downloaded, total)) }
  }

  /**
   * Writes [stream] to [part], appending when the server resumed at [resumeFrom]. Returns the size
   * of [part] when done.
   */
  private suspend fun copyToPart(
    stream: DownloadStream,
    part: File,
    resumeFrom: Long,
    onProgress: suspend (downloaded: Long, total: Long) -> Unit,
  ): Long = withContext(Dispatchers.IO) {
    val append = stream.offset > 0 && stream.offset == resumeFrom
    var downloaded = if (append) resumeFrom else 0L
    val total = stream.totalBytes
    onProgress(downloaded, total)
    FileOutputStream(part, append).use { out ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        ensureActive()
        val n = stream.input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        downloaded += n
        onProgress(downloaded, total)
      }
    }
    if (total > 0 && downloaded < total) throw IOException("Connection closed before the download finished")
    downloaded
  }

  /**
   * Records the downloaded tracks. Listening progress made while streaming carries over to the
   * same files, and tracks keep their provider ids so they can still be matched later.
   */
  private suspend fun saveTracks(item: LibraryItem, scanned: List<Track>, files: List<RemoteFile>?) {
    val old = dao.get(item.key)?.tracks.orEmpty()
    // A file can hold several tracks (chapters), so tracks are matched by file and start.
    val oldByPath = old.associateBy { it.relativePath to it.startMs }
    val fileIds = files.orEmpty().associate { it.relativePath to it.id }
    val tracks = scanned.map { track ->
      val previous = oldByPath[track.relativePath to track.startMs]
      track.copy(
        positionMs = previous?.positionMs ?: 0,
        completed = previous?.completed ?: false,
        durationMs = track.durationMs.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
        remoteFileId = fileIds[track.relativePath],
      )
    }
    dao.replaceTracks(item.key, tracks)
    // Zips can add tracks, so the one to resume may have moved.
    val current = old.firstOrNull { it.index == item.currentTrackIndex }
    val newIndex = current?.let { c ->
      tracks.firstOrNull { it.relativePath == c.relativePath && it.startMs == c.startMs }?.index
    }
    if (newIndex != null && newIndex != item.currentTrackIndex) dao.setCurrentTrack(item.key, newIndex)
  }

  private fun foregroundInfo(id: Int, title: String, downloaded: Long, total: Long): ForegroundInfo {
    val manager = applicationContext.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
      )
    }
    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
    val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(if (total > 0) "Downloading, $percent%" else "Downloading")
      .setProgress(100, percent, total <= 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(id, notification)
    }
  }

  companion object {
    const val KEY_ITEM = "item"
    const val TAG = "download"
    private const val CHANNEL = "downloads"
    private const val MAX_RETRIES = 3
    const val PART_SUFFIX = ".part"
  }
}
