package app.audioflix.net

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
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
  private fun serve(
    ranges: Boolean = true,
    failingStart: Long? = null,
    /** The ETag for the nth request to /asset, counting from 0. */
    etag: (Int) -> String? = { null },
    /** This many piece requests are refused with 403, like an expired signed link. */
    refused: AtomicInteger = AtomicInteger(0),
  ) {
    val assetRequests = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")
        requests += "${request.path} $range"
        if (request.path == "/release") {
          return MockResponse().setResponseCode(302).setHeader("Location", server.url("/asset"))
        }
        val tag = etag(assetRequests.getAndIncrement())
        if (!ranges || range == null) return MockResponse().setBody(Buffer().write(data))
        val (start, last) = range.removePrefix("bytes=").split("-").map { it.toLong() }
        if (start == failingStart) return MockResponse().setResponseCode(503)
        if (last > 0 && refused.getAndUpdate { maxOf(0, it - 1) } > 0) return MockResponse().setResponseCode(403)
        val end = min(last, data.size - 1L)
        return MockResponse()
          .setResponseCode(206)
          .apply { if (tag != null) setHeader("ETag", tag) }
          .setHeader("Content-Range", "bytes $start-$end/${data.size}")
          .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
      }
    }
  }

  private fun downloader(connections: Int = 3, requireSameFile: Boolean = false) =
    RangedDownloader(OkHttpClient(), connections = connections, chunkSize = chunk, retryDelayMs = 0, requireSameFile = requireSameFile)

  private fun download(downloader: RangedDownloader, part: File, connections: Int = 3): Pair<Long, Long> {
    var last = 0L to 0L
    runBlocking {
      downloader.download(server.url("/release").toString(), part, connections) { done, total ->
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

  @Test
  fun aResumeCanUseFewerConnections() {
    val part = tmp.newFile("app.apk.part")
    serve(failingStart = 3 * chunk)
    assertThrows(IOException::class.java) { download(downloader(), part) }
    requests.clear()
    serve()
    download(downloader(), part, connections = 1)
    assertArrayEquals(data, part.readBytes())
    // Only what the first attempt didn't finish is fetched again.
    assertTrue(chunkRequests().size < 5)
  }

  @Test
  fun anExpiredLinkIsLookedUpAgainAndTheDownloadCarriesOn() {
    serve(refused = AtomicInteger(2))
    val part = tmp.newFile("app.apk.part")
    download(downloader(connections = 1), part, connections = 1)
    assertArrayEquals(data, part.readBytes())
    assertEquals(3, requests.count { it.startsWith("/release") })
  }

  @Test
  fun aLinkThatKeepsBeingRefusedFails() {
    serve(refused = AtomicInteger(100))
    val part = tmp.newFile("app.apk.part")
    val error = assertThrows(HttpStatusException::class.java) { download(downloader(connections = 1), part, connections = 1) }
    assertEquals(403, error.code)
  }

  @Test
  fun aFileReplacedMidDownloadIsThrownAway() {
    // The probe and the first piece see one version of the file, later pieces another.
    serve(etag = { if (it < 2) "\"v1\"" else "\"v2\"" })
    val part = tmp.newFile("app.apk.part")
    assertThrows(FileChangedException::class.java) {
      download(downloader(connections = 1, requireSameFile = true), part, connections = 1)
    }
    assertFalse(part.exists())
    assertFalse(File(part.path + ".done").exists())

    // Trying again downloads the new version whole.
    serve(etag = { "\"v2\"" })
    download(downloader(connections = 1, requireSameFile = true), part, connections = 1)
    assertArrayEquals(data, part.readBytes())
  }

  @Test
  fun piecesOfAnotherVersionAreNotReused() {
    val part = tmp.newFile("app.apk.part")
    serve(failingStart = 2 * chunk, etag = { "\"v1\"" })
    assertThrows(IOException::class.java) {
      download(downloader(connections = 1, requireSameFile = true), part, connections = 1)
    }

    requests.clear()
    serve(etag = { "\"v2\"" })
    download(downloader(connections = 1, requireSameFile = true), part, connections = 1)
    assertArrayEquals(data, part.readBytes())
    // Everything is fetched again, not just the pieces the first attempt missed.
    assertEquals(5, chunkRequests().size)
  }

  @Test
  fun refusesAPieceThatEndsInTheWrongPlace() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val (start, _) = request.getHeader("Range")!!.removePrefix("bytes=").split("-").map { it.toLong() }
        // Starts in the right place, but says it holds a different stretch of the file.
        return MockResponse()
          .setResponseCode(206)
          .setHeader("Content-Range", "bytes $start-${start + 9}/${data.size}")
          .setBody(Buffer().write(data, start.toInt(), if (start == 0L) 1 else 10))
      }
    }
    val error = assertThrows(IOException::class.java) {
      runBlocking { downloader().download(server.url("/asset").toString(), File(tmp.root, "x.part")) { _, _ -> } }
    }
    assertEquals("The server sent the wrong part of the file.", error.message)
  }
}
