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
import app.audioflix.provider.AuthRequiredException
import app.audioflix.provider.MediaKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads one library item to its directory, resuming a partial file when the server allows,
 * then unpacks it and records its tracks.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val container = (context.applicationContext as AudioFlixApp).container
  private val dao = container.database.libraryDao()

  override suspend fun doWork(): Result {
    val key = inputData.getString(KEY_ITEM) ?: return Result.failure()
    val item = dao.getItem(key) ?: return Result.success() // deleted meanwhile
    if (item.status == DownloadStatus.COMPLETED) return Result.success()

    val notificationId = key.hashCode()
    runCatching { setForeground(foregroundInfo(notificationId, item.title, 0, 0)) }

    val dir = File(item.localDir).apply { mkdirs() }
    val part = File(dir, MediaInstaller.PART_FILE)
    return try {
      dao.setStatus(key, DownloadStatus.DOWNLOADING)
      val provider = container.providers[item.providerId]
      val resumeFrom = if (part.exists()) part.length() else 0L

      val stream = provider.openDownload(item.remoteId, resumeFrom)
      val fileName = stream.fileName
      stream.use {
        withContext(Dispatchers.IO) {
          val append = stream.offset > 0 && stream.offset == resumeFrom
          var downloaded = if (append) resumeFrom else 0L
          val total = stream.totalBytes
          dao.setProgress(key, downloaded, total)
          var lastReport = 0L
          FileOutputStream(part, append).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              ensureActive()
              val n = stream.input.read(buffer)
              if (n < 0) break
              out.write(buffer, 0, n)
              downloaded += n
              val now = System.currentTimeMillis()
              if (now - lastReport > 750) {
                lastReport = now
                dao.setProgress(key, downloaded, total)
                runCatching { setForeground(foregroundInfo(notificationId, item.title, downloaded, total)) }
              }
            }
          }
          dao.setProgress(key, downloaded, total)
          if (total > 0 && downloaded < total) throw IOException("Connection closed before the download finished")
        }
      }

      if (dao.getItem(key) == null) return Result.success() // deleted while downloading
      dao.setStatus(key, DownloadStatus.EXTRACTING)
      val tracks = withContext(Dispatchers.IO) {
        val archive = MediaInstaller.install(part, dir, fileName, item.title)
        if (item.kind == null) dao.setKind(key, if (archive) MediaKind.SHOW else MediaKind.MOVIE)
        MediaInstaller.scanTracks(dir, key)
      }
      if (tracks.isEmpty()) {
        dao.setStatus(key, DownloadStatus.FAILED, "The download didn't contain any audio files")
        return Result.failure()
      }
      dao.replaceTracks(key, tracks)
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
  }
}
