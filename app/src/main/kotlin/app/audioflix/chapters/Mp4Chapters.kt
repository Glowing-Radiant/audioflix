package app.audioflix.chapters

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class Chapter(val startMs: Long, val title: String)

/** What an MP4 (m4b, m4a) file says about itself: its length and its chapters, if any. */
data class Mp4Info(val durationMs: Long, val chapters: List<Chapter>)

/** Random access to a file's bytes, wherever it is. [read] may return fewer bytes only at the end. */
interface ByteSource {
  val length: Long

  fun read(position: Long, size: Int): ByteArray
}

class FileByteSource(file: File) : ByteSource, Closeable {
  private val raf = RandomAccessFile(file, "r")
  override val length: Long = raf.length()

  override fun read(position: Long, size: Int): ByteArray {
    val n = minOf(size.toLong(), (length - position).coerceAtLeast(0)).toInt()
    val buf = ByteArray(n)
    raf.seek(position)
    raf.readFully(buf)
    return buf
  }

  override fun close() = raf.close()
}

/**
 * Reads [source] in aligned blocks and keeps recent ones, so the many small reads of walking
 * boxes become a few large ones. Over HTTP each block is one range request.
 */
class BlockCachedSource(private val source: ByteSource, private val blockSize: Int = 64 * 1024) : ByteSource {
  private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 32
  }

  override val length: Long get() = source.length

  override fun read(position: Long, size: Int): ByteArray {
    val end = minOf(position + size, length)
    if (end <= position) return ByteArray(0)
    val out = ByteArray((end - position).toInt())
    var pos = position
    while (pos < end) {
      val index = pos / blockSize
      val block = blocks.getOrPut(index) { source.read(index * blockSize, blockSize) }
      val offsetInBlock = (pos - index * blockSize).toInt()
      if (offsetInBlock >= block.size) throw IOException("Unexpected end of file")
      val n = minOf(block.size - offsetInBlock, (end - pos).toInt())
      System.arraycopy(block, offsetInBlock, out, (pos - position).toInt(), n)
      pos += n
    }
    return out
  }
}

/**
 * Finds chapters in MP4 audio files (audiobooks are usually .m4b). Two formats are common and both
 * are read: Nero's `chpl` list in `moov/udta`, and Apple's chapter track, a text track that the
 * audio track points at with `tref/chap`. Only metadata boxes are read, never the audio.
 */
object Mp4Chapters {
  private val extensions = setOf("m4b", "m4a", "mp4")
  private const val MAX_CHAPTERS = 2_000

  fun isMp4Name(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions

  /** Reads a downloaded file; null when it isn't a readable MP4. */
  fun readFile(file: File): Mp4Info? = runCatching {
    FileByteSource(file).use { read(BlockCachedSource(it)) }
  }.getOrNull()

  /** Null when [source] isn't an MP4 or has no `moov` box. */
  fun read(source: ByteSource): Mp4Info? {
    val moov = children(source, 0, source.length).firstOrNull { it.type == "moov" } ?: return null
    var durationMs = 0L
    val tracks = mutableListOf<TrackBox>()
    var nero: List<Chapter>? = null

    for (box in children(source, moov.bodyStart, moov.end)) {
      when (box.type) {
        "mvhd" -> durationMs = readDuration(source, box)
        "trak" -> tracks += readTrack(source, box)
        "udta" -> children(source, box.bodyStart, box.end).firstOrNull { it.type == "chpl" }?.let {
          nero = readChpl(source, it)
        }
      }
    }
    if (durationMs <= 0) durationMs = tracks.firstOrNull { it.handler == "soun" }?.durationMs ?: 0

    val raw = nero?.takeIf { it.isNotEmpty() } ?: run {
      val chapterIds = tracks.filter { it.handler == "soun" }.flatMap { it.chapterTrackIds }.toSet()
      tracks.firstOrNull { it.id in chapterIds }?.let { readChapterTrack(source, it) }
    }.orEmpty()
    return Mp4Info(durationMs, normalize(raw, durationMs))
  }

  /** Sorted, without duplicates or chapters past the end, and with readable titles. */
  internal fun normalize(chapters: List<Chapter>, durationMs: Long): List<Chapter> {
    val sorted = chapters
      .filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
      .sortedBy { it.startMs }
      .distinctBy { it.startMs }
      .take(MAX_CHAPTERS)
    return sorted.mapIndexed { i, ch ->
      val title = ch.title.trim()
      Chapter(
        startMs = if (i == 0) 0 else ch.startMs, // the first chapter covers any lead-in
        title = when {
          title.isEmpty() -> "Chapter ${i + 1}"
          title.all { it.isDigit() } -> "Chapter ${title.trimStart('0').ifEmpty { "0" }}"
          else -> title
        },
      )
    }
  }

  private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
    val bodyStart: Long get() = start + headerSize
    val end: Long get() = start + size
    val bodySize: Long get() = size - headerSize
  }

  private class TrackBox(
    val id: Int,
    val handler: String?,
    val timescale: Long,
    val durationMs: Long,
    val chapterTrackIds: List<Int>,
    val stbl: Box?,
  )

  private fun children(source: ByteSource, from: Long, to: Long): Sequence<Box> = sequence {
    var pos = from
    while (pos + 8 <= to) {
      val h = source.read(pos, 16)
      if (h.size < 8) break
      var size = u32(h, 0)
      val type = String(h, 4, 4, Charsets.ISO_8859_1)
      var headerSize = 8
      if (size == 1L) {
        if (h.size < 16) break
        size = u64(h, 8)
        headerSize = 16
      } else if (size == 0L) {
        size = to - pos // extends to the end of its parent
      }
      if (size < headerSize || pos + size > to) break
      yield(Box(type, pos, headerSize, size))
      pos += size
    }
  }

  private fun child(source: ByteSource, parent: Box, type: String): Box? =
    children(source, parent.bodyStart, parent.end).firstOrNull { it.type == type }

  private fun body(source: ByteSource, box: Box, max: Int = 4 * 1024 * 1024): ByteArray =
    source.read(box.bodyStart, minOf(box.bodySize, max.toLong()).toInt())

  /** mvhd and mdhd share a layout: version, flags, times, timescale, duration. */
  private fun readTimescaleAndDuration(source: ByteSource, box: Box): Pair<Long, Long> {
    val b = body(source, box, 32)
    return if (b[0].toInt() == 1) u32(b, 20) to u64(b, 24) else u32(b, 12) to u32(b, 16)
  }

  private fun readDuration(source: ByteSource, box: Box): Long {
    val (timescale, duration) = readTimescaleAndDuration(source, box)
    return if (timescale > 0) duration * 1000 / timescale else 0
  }

  private fun readTrack(source: ByteSource, trak: Box): TrackBox {
    var id = -1
    var handler: String? = null
    var timescale = 0L
    var durationMs = 0L
    val chapterIds = mutableListOf<Int>()
    var stbl: Box? = null
    for (box in children(source, trak.bodyStart, trak.end)) {
      when (box.type) {
        "tkhd" -> {
          val b = body(source, box, 24)
          id = (if (b[0].toInt() == 1) u32(b, 20) else u32(b, 12)).toInt()
        }
        "tref" -> child(source, box, "chap")?.let { chap ->
          val b = body(source, chap, 4 * 64)
          for (i in 0 until b.size / 4) chapterIds += u32(b, i * 4).toInt()
        }
        "mdia" -> for (m in children(source, box.bodyStart, box.end)) {
          when (m.type) {
            "mdhd" -> {
              val (ts, duration) = readTimescaleAndDuration(source, m)
              timescale = ts
              durationMs = if (ts > 0) duration * 1000 / ts else 0
            }
            "hdlr" -> handler = String(body(source, m, 12), 8, 4, Charsets.ISO_8859_1)
            // The audio track's sample tables are large; they're only located here, not read.
            "minf" -> stbl = child(source, m, "stbl")
          }
        }
      }
    }
    return TrackBox(id, handler, timescale, durationMs, chapterIds, stbl)
  }

  /** Nero chapters: start times in 100 ns units, and UTF-8 titles. Laid out as FFmpeg writes them. */
  private fun readChpl(source: ByteSource, box: Box): List<Chapter> {
    val b = body(source, box, 1024 * 1024)
    if (b.size < 5) return emptyList()
    var p = 4 // version and flags
    if (b[0].toInt() == 1) p += 4
    val count = b[p].toInt() and 0xff
    p += 1
    val chapters = mutableListOf<Chapter>()
    repeat(count) {
      if (p + 9 > b.size) return chapters
      val start = u64(b, p)
      val len = b[p + 8].toInt() and 0xff
      if (p + 9 + len > b.size) return chapters
      chapters += Chapter(start / 10_000, String(b, p + 9, len, Charsets.UTF_8))
      p += 9 + len
    }
    return chapters
  }

  /** Apple chapters: each sample of the text track is one title, timed by the sample table. */
  private fun readChapterTrack(source: ByteSource, track: TrackBox): List<Chapter> {
    val stbl = track.stbl ?: return emptyList()
    if (track.timescale <= 0) return emptyList()
    val tables = children(source, stbl.bodyStart, stbl.end).associateBy { it.type }

    // Sample start times.
    val stts = tables["stts"]?.let { body(source, it) } ?: return emptyList()
    val starts = mutableListOf<Long>()
    var t = 0L
    for (i in 0 until u32(stts, 4).toInt()) {
      val count = u32(stts, 8 + i * 8)
      val delta = u32(stts, 12 + i * 8)
      for (j in 0 until count) {
        if (starts.size >= MAX_CHAPTERS) break
        starts += t
        t += delta
      }
    }

    // Sample sizes.
    val stsz = tables["stsz"]?.let { body(source, it) } ?: return emptyList()
    val fixedSize = u32(stsz, 4)
    val sampleCount = minOf(u32(stsz, 8).toInt(), starts.size)
    val sizes = List(sampleCount) { i -> if (fixedSize != 0L) fixedSize else u32(stsz, 12 + i * 4) }

    // Sample offsets, from chunk offsets and samples per chunk.
    val chunkOffsets = tables["stco"]?.let { box ->
      val b = body(source, box)
      List(u32(b, 4).toInt()) { i -> u32(b, 8 + i * 4) }
    } ?: tables["co64"]?.let { box ->
      val b = body(source, box)
      List(u32(b, 4).toInt()) { i -> u64(b, 8 + i * 8) }
    } ?: return emptyList()
    val stsc = tables["stsc"]?.let { body(source, it) } ?: return emptyList()
    val runs = List(u32(stsc, 4).toInt()) { i -> u32(stsc, 8 + i * 12) to u32(stsc, 12 + i * 12) }
    val offsets = mutableListOf<Long>()
    for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
      val chunk = chunkIndex + 1L
      val perChunk = runs.lastOrNull { it.first <= chunk }?.second ?: 1L
      var offset = chunkOffset
      for (j in 0 until perChunk) {
        if (offsets.size >= sampleCount) break
        offsets += offset
        offset += sizes[offsets.size - 1]
      }
    }

    return offsets.indices.mapNotNull { i ->
      val sample = source.read(offsets[i], minOf(sizes[i], 1024L).toInt())
      if (sample.size < 2) return@mapNotNull null
      val len = minOf(((sample[0].toInt() and 0xff) shl 8) or (sample[1].toInt() and 0xff), sample.size - 2)
      val utf16 = len >= 2 && sample[2] == 0xFE.toByte() && sample[3] == 0xFF.toByte()
      val title = if (utf16) String(sample, 4, len - 2, Charsets.UTF_16BE) else String(sample, 2, len, Charsets.UTF_8)
      Chapter(starts[i] * 1000 / track.timescale, title)
    }
  }

  private fun u32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
      ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

  private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
}
