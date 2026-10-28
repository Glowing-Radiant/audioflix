package app.audioflix.download

import android.media.MediaMetadataRetriever
import app.audioflix.data.Track
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Turns a finished download (a single audio file or a zip of them) into playable tracks. */
object MediaInstaller {
  const val PART_FILE = "download.part"

  private val audioExtensions =
    setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka")

  /**
   * Moves [part] into place inside [dir]: zips are extracted (and deleted), anything else is renamed
   * to [fileName]. Returns true when the download was an archive.
   */
  fun install(part: File, dir: File, fileName: String?, fallbackTitle: String): Boolean {
    if (isZip(part)) {
      unzip(part, dir)
      part.delete()
      return true
    }
    var name = sanitizeFileName(fileName ?: fallbackTitle)
    if (name.substringAfterLast('.', "").lowercase() !in audioExtensions) name += ".mp3"
    val target = File(dir, name)
    if (target.exists()) target.delete()
    if (!part.renameTo(target)) throw IOException("Couldn't move the downloaded file into place")
    return false
  }

  /** All audio files under [dir], in natural (episode) order, as tracks of [itemKey]. */
  fun scanTracks(dir: File, itemKey: String): List<Track> {
    val root = dir.canonicalFile
    val files = root.walkTopDown()
      .onEnter { it.name != "__MACOSX" }
      .filter { it.isFile && !it.name.startsWith("._") && it.extension.lowercase() in audioExtensions }
      .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
      .sortedWith(NaturalOrder)
      .toList()
    return files.mapIndexed { i, rel ->
      Track(
        itemKey = itemKey,
        index = i,
        title = prettyTitle(rel),
        relativePath = rel,
        durationMs = probeDurationMs(File(root, rel)),
      )
    }
  }

  fun isZip(file: File): Boolean = runCatching {
    FileInputStream(file).use { input ->
      val sig = ByteArray(4)
      input.read(sig) == 4 && sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte() &&
        sig[2] == 3.toByte() && sig[3] == 4.toByte()
    }
  }.getOrDefault(false)

  private fun unzip(zip: File, dir: File) {
    val root = dir.canonicalFile
    // Zips made on Windows often use the legacy code page for names instead of UTF-8.
    var zipFile = ZipFile(zip, Charsets.UTF_8)
    if (runCatching { zipFile.entries().asSequence().forEach { it.name } }.isFailure) {
      zipFile.close()
      zipFile = ZipFile(zip, Charset.forName("IBM437"))
    }
    zipFile.use { z ->
      for (entry in z.entries()) {
        val target = File(root, entry.name).canonicalFile
        // Reject entries that would escape the item directory ("zip slip").
        if (!target.path.startsWith(root.path + File.separator)) continue
        if (entry.isDirectory) {
          target.mkdirs()
          continue
        }
        target.parentFile?.mkdirs()
        z.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
      }
    }
  }

  private fun probeDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.path)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
      0L
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun prettyTitle(relativePath: String): String =
    relativePath.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()

  private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(150).ifEmpty { "audio" }
}

/** Orders "Episode 2" before "Episode 10" by comparing digit runs numerically. */
object NaturalOrder : Comparator<String> {
  private val chunk = Regex("""\d+|\D+""")

  override fun compare(a: String, b: String): Int {
    val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ca.size, cb.size)) {
      val x = ca[i]
      val y = cb[i]
      val cmp = if (x[0].isDigit() && y[0].isDigit()) {
        val nx = x.trimStart('0')
        val ny = y.trimStart('0')
        if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
      } else {
        x.compareTo(y)
      }
      if (cmp != 0) return cmp
    }
    return ca.size - cb.size
  }
}
