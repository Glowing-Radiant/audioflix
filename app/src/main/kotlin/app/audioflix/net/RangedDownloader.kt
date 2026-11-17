package app.audioflix.net

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** The server answered with an HTTP error. 4xx answers aren't retried. */
class HttpStatusException(val code: Int) : IOException("The download failed with HTTP $code.")

/**
 * Downloads one file over several connections at once, a range of it on each.
 *
 * GitHub's release download server is slow per connection from some networks. In November 2026 a
 * 16 MB APK took 266 seconds over one connection from India, and 46 seconds over eight. Over HTTP/2
 * every range would share one connection, so this uses HTTP/1.1, one connection per range.
 *
 * Finished pieces are listed in a `.done` file next to the partial file, so trying again carries
 * on where the last attempt stopped. A server that ignores ranges gets one plain download.
 */
class RangedDownloader(
  client: OkHttpClient,
  private val connections: Int = 6,
  private val chunkSize: Long = 1L shl 20,
  private val retryDelayMs: Long = 1_000,
) {
  private val http = client.newBuilder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .dispatcher(Dispatcher().apply { maxRequestsPerHost = connections })
    .build()

  private sealed interface Probe {
    data class Ranged(val url: HttpUrl, val total: Long) : Probe
    /** The server ignored the range and sent the whole file, which is now saved. */
    data object Saved : Probe
    /** Ranges work, but the size is unknown. */
    data object SizeUnknown : Probe
  }

  /**
   * Downloads [url] into [part], resuming an earlier attempt when [part] has one. [onProgress] gets
   * the bytes so far and the total (or -1), and can be called from several threads at once.
   */
  suspend fun download(url: String, part: File, onProgress: (done: Long, total: Long) -> Unit) {
    withContext(Dispatchers.IO) {
      val log = File(part.path + ".done")
      // One byte tells us the size, whether ranges work, and where redirects end up.
      val probe = Request.Builder().url(url).header("Range", "bytes=0-0").build()
      val result = http.newCall(probe).readCancellably { response ->
        val total = contentRangeTotal(response.header("Content-Range"))
        when {
          response.code == 206 && total != null && total > 0 -> Probe.Ranged(response.request.url, total)
          response.code == 206 -> Probe.SizeUnknown
          response.isSuccessful -> {
            writeWhole(response, part, onProgress)
            Probe.Saved
          }
          else -> throw HttpStatusException(response.code)
        }
      }
      when (result) {
        is Probe.Ranged -> downloadRanges(result.url, result.total, part, log, onProgress)
        Probe.Saved -> log.delete()
        Probe.SizeUnknown -> {
          log.delete()
          http.newCall(Request.Builder().url(url).build()).readCancellably { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            writeWhole(response, part, onProgress)
          }
        }
      }
    }
  }

  private suspend fun downloadRanges(
    url: HttpUrl,
    total: Long,
    part: File,
    log: File,
    onProgress: (Long, Long) -> Unit,
  ) {
    val chunks = ((total + chunkSize - 1) / chunkSize).toInt()
    val done = finishedChunks(part, log, total)
    if (done.isEmpty()) {
      RandomAccessFile(part, "rw").use { it.setLength(total) }
      log.writeText("$total $chunkSize\n")
    }
    val progress = AtomicLong(done.sumOf { chunkLength(it, total) })
    onProgress(progress.get(), total)

    val queue = Channel<Int>(Channel.UNLIMITED)
    (0 until chunks).filterNot { it in done }.forEach { queue.trySend(it) }
    queue.close()
    // Workers take the next missing piece as they finish one, so a slow connection only holds up
    // its own piece.
    coroutineScope {
      repeat(min(connections, chunks - done.size)) {
        launch {
          RandomAccessFile(part, "rw").use { file ->
            for (index in queue) {
              fetchChunk(url, index, total, file, progress, onProgress)
              synchronized(log) { log.appendText("$index\n") }
            }
          }
        }
      }
    }
    log.delete()
  }

  /** Pieces an earlier attempt finished, if it was downloading the same file the same way. */
  private fun finishedChunks(part: File, log: File, total: Long): Set<Int> {
    val lines = runCatching { log.readText() }.getOrNull()?.split('\n')
    if (lines != null && lines.first() == "$total $chunkSize" && part.length() == total) {
      // The last element is either empty or a line cut short by a crash.
      return lines.drop(1).dropLast(1).mapNotNull { it.toIntOrNull() }.toSet()
    }
    log.delete()
    return emptySet()
  }

  private fun chunkLength(index: Int, total: Long): Long = min(chunkSize, total - index * chunkSize)

  private suspend fun fetchChunk(
    url: HttpUrl,
    index: Int,
    total: Long,
    file: RandomAccessFile,
    progress: AtomicLong,
    onProgress: (Long, Long) -> Unit,
  ) {
    val start = index * chunkSize
    val length = chunkLength(index, total)
    val request = Request.Builder().url(url).header("Range", "bytes=$start-${start + length - 1}").build()
    var attempt = 0
    while (true) {
      var written = 0L
      try {
        http.newCall(request).readCancellably { response ->
          if (response.code != 206) throw HttpStatusException(response.code)
          if (contentRangeStart(response.header("Content-Range")) != start) {
            throw IOException("The server sent the wrong part of the file.")
          }
          val body = response.body ?: throw IOException("The download was empty.")
          file.seek(start)
          body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              if (written + n > length) throw IOException("The server sent more than was asked for.")
              file.write(buffer, 0, n)
              written += n
              onProgress(progress.addAndGet(n.toLong()), total)
            }
          }
          if (written != length) throw IOException("The connection closed before the download finished.")
        }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        // Cancelling closes the connection, which shows up here as an IOException.
        currentCoroutineContext().ensureActive()
        progress.addAndGet(-written)
        attempt++
        if ((e is HttpStatusException && e.code in 400..499) || attempt >= 3) throw e
        delay(retryDelayMs * attempt)
      }
    }
  }

  private fun writeWhole(response: Response, part: File, onProgress: (Long, Long) -> Unit) {
    val body = response.body ?: throw IOException("The download was empty.")
    val total = body.contentLength()
    var read = 0L
    body.byteStream().use { input ->
      part.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          out.write(buffer, 0, n)
          read += n
          onProgress(read, total)
        }
      }
    }
    if (total > 0 && read < total) throw IOException("The connection closed before the download finished.")
  }
}

/**
 * Runs the call and hands its response to [block], which may block while reading the body.
 * Cancelling the coroutine cancels the call, so a stalled read stops at once.
 */
private suspend fun <T> Call.readCancellably(block: (Response) -> T): T = coroutineScope {
  val call = this@readCancellably
  val finished = AtomicBoolean(false)
  val watcher = launch {
    try {
      awaitCancellation()
    } finally {
      if (!finished.get()) call.cancel()
    }
  }
  try {
    call.await().use(block)
  } finally {
    finished.set(true)
    watcher.cancel()
  }
}
