package app.audioflix.net

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RangedDownloaderTest {
  @get:Rule val tmp = TemporaryFolder()

  private val chunk = 64 * 1024L
  private val data = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
  private val server = MockWebServer()
  /** "path range" for every request, so tests can check what was fetched. */
  private val requests = ConcurrentLinkedQueue<String>()

  @After fun stop() = server.shutdown()

  /** Serves [data] at /asset behind a redirect from /release, like GitHub does. */
  private fun serve(ranges: Boolean = true, failingStart: Long? = null) {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")
        requests += "${request.path} $range"
        if (request.path == "/release") {
          return MockResponse().setResponseCode(302).setHeader("Location", server.url("/asset"))
        }
        if (!ranges || range == null) return MockResponse().setBody(Buffer().write(data))
        val (start, last) = range.removePrefix("bytes=").split("-").map { it.toLong() }
        if (start == failingStart) return MockResponse().setResponseCode(503)
        val end = min(last, data.size - 1L)
        return MockResponse()
          .setResponseCode(206)
          .setHeader("Content-Range", "bytes $start-$end/${data.size}")
          .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
      }
    }
  }

  private fun downloader(connections: Int = 3) =
    RangedDownloader(OkHttpClient(), connections = connections, chunkSize = chunk, retryDelayMs = 0)

  private fun download(downloader: RangedDownloader, part: File): Pair<Long, Long> {
    var last = 0L to 0L
    runBlocking {
      downloader.download(server.url("/release").toString(), part) { done, total ->
        synchronized(this@RangedDownloaderTest) { if (done >= last.first) last = done to total }
      }
    }
    return last
  }

  private fun chunkRequests() = requests.filter { it.startsWith("/asset") && !it.endsWith("bytes=0-0") }

  @Test
  fun downloadsInParallelRanges() {
    serve()
    val part = tmp.newFile("app.apk.part")
    val progress = download(downloader(), part)

    assertArrayEquals(data, part.readBytes())
    assertEquals(data.size.toLong() to data.size.toLong(), progress)
    // The redirect is followed once; the pieces go straight to where it led.
    assertEquals(1, requests.count { it.startsWith("/release") })
    assertEquals(5, chunkRequests().size)
    assertFalse(File(part.path + ".done").exists())
  }

  @Test
  fun serverWithoutRangesGetsOnePlainDownload() {
    serve(ranges = false)
    val part = tmp.newFile("app.apk.part")
    download(downloader(), part)

    assertArrayEquals(data, part.readBytes())
    assertEquals(0, chunkRequests().size)
  }

  @Test
  fun tryingAgainFetchesOnlyWhatIsMissing() {
    val part = tmp.newFile("app.apk.part")
    serve(failingStart = 2 * chunk)
    assertThrows(IOException::class.java) { download(downloader(connections = 1), part) }
    // Three attempts at the third piece, after the first two were saved.
    assertEquals(
      listOf(0L, chunk, 2 * chunk, 2 * chunk, 2 * chunk),
      chunkRequests().map { it.substringAfter("bytes=").substringBefore('-').toLong() },
    )

    requests.clear()
    serve()
    download(downloader(connections = 1), part)
    assertArrayEquals(data, part.readBytes())
    assertEquals(
      listOf(2 * chunk, 3 * chunk, 4 * chunk),
      chunkRequests().map { it.substringAfter("bytes=").substringBefore('-').toLong() },
    )
  }

  @Test fun refusesAWebPageInsteadOfTheFile() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) =
        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>Sign in</html>")
    }
    val part = File(tmp.root, "page.part")
    assertThrows(NotTheFileException::class.java) {
      runBlocking { downloader().download(server.url("/asset").toString(), part) { _, _ -> } }
    }
    assertFalse(part.exists())
  }

  @Test fun reportsTheFileNameTheServerGives() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Range", "bytes 0-0/1")
        .setHeader("Content-Disposition", "attachment; filename=\"Movie (2020).mp3\"")
        .setBody(Buffer().write(byteArrayOf(1)))
    }
    val name = runBlocking { downloader().download(server.url("/download/1").toString(), File(tmp.root, "n.part")) { _, _ -> } }
    assertEquals("Movie (2020).mp3", name)
  }
}
