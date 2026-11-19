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
open class HttpStatusException(val code: Int) : IOException("The download failed with HTTP $code.")

/**
 * The server sent a web page instead of the file: a sign-in page, or a warning page. Downloading
 * the normal way (one connection, through the provider) knows what to do with those.
 */
class NotTheFileException : IOException("The server sent a web page instead of the file.")

/**
 * The file on the server changed part way through, so the pieces saved so far belong to a
 * different file. They have been thrown away; trying again starts afresh.
 */
class FileChangedException : IOException("The file changed on the server while it was downloading. Try again.")

/** A piece was refused after the size probe worked, usually because a signed download link expired. */
private class LinkRefusedException(code: Int) : HttpStatusException(code)

/**
 * Downloads one file over several connections at once, a range of it on each.
 *
 * GitHub's release download server is slow per connection from some networks. In November 2026 a
 * 16 MB APK took 266 seconds over one connection from India, and 46 seconds over eight. Over HTTP/2
 * every range would share one connection, so this uses HTTP/1.1, one connection per range.
 *
 * Finished pieces are listed in a `.done` file next to the partial file, so trying again carries
 * on where the last attempt stopped, with any number of connections. A server that ignores ranges
 * gets one plain download.
 *
 * Every piece is checked against the range asked for, and with [requireSameFile] also against the
 * file's ETag (or Last-Modified date), so a file replaced on the server mid-download is never
 * stitched together from two versions. A download link that stops working part way, as GitHub's
 * signed links do after an hour, is looked up again from [download]'s url and the download carries
 * on.
 *
 * Used for app updates, and for titles from providers whose "faster downloads" setting is on.
 */
class RangedDownloader(
  client: OkHttpClient,
  /** The most connections one download may use, and the default. */
  private val connections: Int = 6,
  private val chunkSize: Long = 1L shl 20,
  private val retryDelayMs: Long = 1_000,
  /** Tries per piece before the download fails. Waits double between tries. */
  private val maxAttempts: Int = 3,
  private val requireSameFile: Boolean = false,
) {
  private val http = client.newBuilder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .dispatcher(Dispatcher().apply { maxRequestsPerHost = connections })
    .build()

  private sealed interface Probe {
    data class Ranged(val url: HttpUrl, val total: Long, val validator: String?) : Probe
    /** The server ignored the range and sent the whole file, which is now saved. */
    data object Saved : Probe
    /** Ranges work, but the size is unknown. */
    data object SizeUnknown : Probe
  }

  /** Where [part]'s list of finished pieces is kept. */
  fun logFile(part: File): File = File(part.path + ".done")

  /**
   * Downloads [url] into [part], resuming an earlier attempt when [part] has one, over at most
   * [connections] connections. [onProgress] gets the bytes so far and the total (or -1), and can be
   * called from several threads at once.
   */
  suspend fun download(
    url: String,
    part: File,
    connections: Int = this.connections,
    onProgress: (done: Long, total: Long) -> Unit,
  ): String? = withContext(Dispatchers.IO) {
    val log = logFile(part)
    var refreshes = 0
    var name: String? = null
    while (true) {
      // The file's name, from the first response, for callers that name the file after it.
      var fileName: String? = null
      // One byte tells us the size, whether ranges work, and where redirects end up.
      val probe = Request.Builder().url(url).header("Accept", "*/*").header("Range", "bytes=0-0").build()
      val result = http.newCall(probe).readCancellably { response ->
        fileName = contentDispositionFileName(response.header("Content-Disposition"))
          ?: response.request.url.pathSegments.lastOrNull()?.takeIf { '.' in it }
        val total = contentRangeTotal(response.header("Content-Range"))
        when {
          response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("text/html") ->
            throw NotTheFileException()
          response.code == 206 && total != null && total > 0 ->
            Probe.Ranged(response.request.url, total, validatorOf(response))
          response.code == 206 -> Probe.SizeUnknown
          response.isSuccessful -> {
            writeWhole(response, part, onProgress)
            Probe.Saved
          }
          else -> throw HttpStatusException(response.code)
        }
      }
      try {
        when (result) {
          is Probe.Ranged -> downloadRanges(result, connections.coerceIn(1, this@RangedDownloader.connections), part, log, onProgress)
          Probe.Saved -> log.delete()
          Probe.SizeUnknown -> {
            log.delete()
            http.newCall(Request.Builder().url(url).header("Accept", "*/*").build()).readCancellably { response ->
              if (!response.isSuccessful) throw HttpStatusException(response.code)
              writeWhole(response, part, onProgress)
            }
          }
        }
        name = fileName
        break
      } catch (e: LinkRefusedException) {
        // Look the link up again from the start; the pieces saved so far are kept.
        if (++refreshes > MAX_LINK_REFRESHES) throw HttpStatusException(e.code)
      } catch (e: FileChangedException) {
        log.delete()
        part.delete()
        throw e
      }
    }
    name
  }

  private fun validatorOf(response: Response): String? =
    if (requireSameFile) (response.header("ETag") ?: response.header("Last-Modified"))?.trim() else null

  /** The first line of the log: what was being downloaded, so a resume only reuses pieces of the same file. */
  private fun logHeader(total: Long, validator: String?) = listOfNotNull("$total", "$chunkSize", validator).joinToString(" ")

  private suspend fun downloadRanges(
    probe: Probe.Ranged,
    connections: Int,
    part: File,
    log: File,
    onProgress: (Long, Long) -> Unit,
  ) {
    val total = probe.total
    val header = logHeader(total, probe.validator)
    val chunks = ((total + chunkSize - 1) / chunkSize).toInt()
    val done = finishedChunks(part, log, total, header)
    if (done.isEmpty()) {
      RandomAccessFile(part, "rw").use { it.setLength(total) }
      log.writeText("$header\n")
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
              fetchChunk(probe, index, file, progress, onProgress)
              synchronized(log) { log.appendText("$index\n") }
            }
          }
        }
      }
    }
    log.delete()
  }

  /** Pieces an earlier attempt finished, if it was downloading the same file the same way. */
  private fun finishedChunks(part: File, log: File, total: Long, header: String): Set<Int> {
    val lines = runCatching { log.readText() }.getOrNull()?.split('\n')
    if (lines != null && lines.first() == header && part.length() == total) {
      val chunks = (total + chunkSize - 1) / chunkSize
      // The last element is either empty or a line cut short by a crash.
      return lines.drop(1).dropLast(1).mapNotNull { it.toIntOrNull() }.filter { it in 0 until chunks }.toSet()
    }
    log.delete()
    return emptySet()
  }

  private fun chunkLength(index: Int, total: Long): Long = min(chunkSize, total - index * chunkSize)

  private suspend fun fetchChunk(
    probe: Probe.Ranged,
    index: Int,
    file: RandomAccessFile,
    progress: AtomicLong,
    onProgress: (Long, Long) -> Unit,
  ) {
    val total = probe.total
    val start = index * chunkSize
    val length = chunkLength(index, total)
    val end = start + length - 1
    val request = Request.Builder().url(probe.url).header("Accept", "*/*").header("Range", "bytes=$start-$end").build()
    var attempt = 0
    while (true) {
      var written = 0L
      try {
        http.newCall(request).readCancellably { response ->
          when (response.code) {
            206 -> Unit
            // The file is now shorter than when the download started.
            416 -> throw FileChangedException()
            401, 403, 404, 410 -> throw LinkRefusedException(response.code)
            else -> throw HttpStatusException(response.code)
          }
          val validator = validatorOf(response)
          if (probe.validator != null && validator != null && validator != probe.validator) throw FileChangedException()
          val range = parseContentRange(response.header("Content-Range"))
          if (range != null && range.total != null && range.total != total) throw FileChangedException()
          if (range == null || range.start != start || range.end != end) {
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
        onProgress(progress.get(), total)
        attempt++
        val giveUp = e is FileChangedException || e is LinkRefusedException ||
          (e is HttpStatusException && e.code in 400..499)
        if (giveUp || attempt >= maxAttempts) throw e
        delay(retryDelayMs * (1L shl (attempt - 1).coerceAtMost(5)))
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

  private companion object {
    /** How many times one download looks its link up again after pieces start being refused. */
    const val MAX_LINK_REFRESHES = 2
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
