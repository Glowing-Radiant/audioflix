package app.audioflix.chapters

import app.audioflix.net.contentRangeTotal
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads chapters of files that are only streamed, a few byte ranges at a time. */
class ChapterReader(private val client: OkHttpClient) {
  /** Null when [url] doesn't serve a readable MP4, or doesn't allow byte ranges. */
  suspend fun readRemote(url: String): Mp4Info? = withContext(Dispatchers.IO) {
    runCatching { Mp4Chapters.read(BlockCachedSource(HttpByteSource(client, url))) }.getOrNull()
  }
}

/** A remote file read with HTTP range requests. Its length comes from the first response. */
private class HttpByteSource(private val client: OkHttpClient, private val url: String) : ByteSource {
  private var knownLength = -1L

  override val length: Long
    get() {
      if (knownLength < 0) read(0, 1)
      return knownLength
    }

  override fun read(position: Long, size: Int): ByteArray {
    if (knownLength in 0..position) return ByteArray(0)
    val request = Request.Builder()
      .url(url)
      .header("Accept", "*/*")
      .header("Range", "bytes=$position-${position + size - 1}")
      .build()
    client.newCall(request).execute().use { response ->
      // Anything but a partial response (a full file, or an error page) means no random access.
      if (response.code != 206) throw IOException("Byte ranges not supported (HTTP ${response.code})")
      knownLength = contentRangeTotal(response.header("Content-Range")) ?: throw IOException("No Content-Range")
      return response.body?.bytes() ?: ByteArray(0)
    }
  }
}
