package app.audioflix.download

import android.media.MediaMetadataRetriever
import app.audioflix.chapters.Chapter
import app.audioflix.chapters.Mp4Chapters
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

  fun isAudioName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in audioExtensions

  fun isArchiveName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "zip"

  /**
   * Unpacks every zip under [dir] next to where it lies, then deletes it. Used for titles that
   * arrive as several files, some of which may be archives.
   */
  fun extractArchives(dir: File) {
    val zips = dir.walkTopDown().filter { it.isFile && isArchiveName(it.name) && isZip(it) }.toList()
    for (zip in zips) {
      unzip(zip, zip.parentFile ?: dir)
      zip.delete()
    }
  }

  /**
   * All audio files under [dir], in natural (episode) order, as tracks of [itemKey]. Files with
   * chapters (audiobooks) become one track per chapter.
   */
  fun scanTracks(dir: File, itemKey: String): List<Track> {
    val root = dir.canonicalFile
    val files = root.walkTopDown()
      .onEnter { it.name != "__MACOSX" }
      .filter { it.isFile && !it.name.startsWith("._") && isAudioName(it.name) }
      .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
      .sortedWith(NaturalOrder)
      .toList()
    val tracks = mutableListOf<Track>()
    for (rel in files) {
      val file = File(root, rel)
      val info = if (Mp4Chapters.isMp4Name(rel)) Mp4Chapters.readFile(file) else null
      val durationMs = info?.durationMs?.takeIf { it > 0 } ?: probeDurationMs(file)
      tracks += tracksForFile(itemKey, tracks.size, rel, durationMs, info?.chapters.orEmpty(), remoteFileId = null)
    }
    return tracks
  }

  /**
   * The tracks one file contributes, numbered from [firstIndex]: one per chapter when it has two
   * or more [chapters], otherwise the whole file.
   */
  fun tracksForFile(
    itemKey: String,
    firstIndex: Int,
    relativePath: String,
    durationMs: Long,
    chapters: List<Chapter>,
    remoteFileId: String?,
  ): List<Track> {
    if (chapters.size < 2) {
      return listOf(
        Track(
          itemKey = itemKey,
          index = firstIndex,
          title = trackTitle(relativePath),
          relativePath = relativePath,
          durationMs = durationMs,
          remoteFileId = remoteFileId,
        ),
      )
    }
    return chapters.mapIndexed { i, chapter ->
      val end = chapters.getOrNull(i + 1)?.startMs
      val length = (end ?: durationMs) - chapter.startMs
      Track(
        itemKey = itemKey,
        index = firstIndex + i,
        title = chapter.title,
        relativePath = relativePath,
        durationMs = length.coerceAtLeast(0),
        remoteFileId = remoteFileId,
        startMs = chapter.startMs,
        endMs = end,
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

  /** A track's title from its file name: "Episode_01.mp3" -> "Episode 01". */
  fun trackTitle(relativePath: String): String =
    relativePath.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()

  /** [name] made safe as a single file or folder name on the phone's storage. */
  fun sanitizeFileName(name: String): String =
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
