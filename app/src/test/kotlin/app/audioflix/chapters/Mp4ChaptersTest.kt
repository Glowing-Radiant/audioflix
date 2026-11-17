package app.audioflix.chapters

import app.audioflix.download.MediaInstaller
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds small MP4 files laid out like real audiobooks: `ftyp`, then `mdat`, then `moov` at the
 * end, which is how the m4b files in shared Drive folders were found to be (November 2026).
 */
class Mp4ChaptersTest {
  private class Bytes(private val data: ByteArray) : ByteSource {
    var reads = 0
    override val length: Long get() = data.size.toLong()

    override fun read(position: Long, size: Int): ByteArray {
      reads++
      val end = minOf(data.size.toLong(), position + size).toInt()
      return data.copyOfRange(position.toInt(), maxOf(position.toInt(), end))
    }
  }

  private fun box(type: String, vararg parts: ByteArray): ByteArray {
    val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
  }

  private fun ints(vararg values: Long): ByteArray =
    ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it.toInt()) } }.array()

  private fun fullBox(version: Int = 0) = byteArrayOf(version.toByte(), 0, 0, 0)

  /** mvhd and mdhd, version 0: times, timescale, duration, then padding. */
  private fun header(type: String, timescale: Long, duration: Long) =
    box(type, fullBox(), ints(0, 0, timescale, duration), ByteArray(80))

  private fun tkhd(id: Long) = box("tkhd", fullBox(), ints(0, 0, id, 0, 0), ByteArray(60))

  private fun hdlr(handler: String) = box("hdlr", fullBox(), ints(0), handler.toByteArray(), ByteArray(12), byteArrayOf(0))

  private val ftyp = box("ftyp", "M4B ".toByteArray(), ints(0), "isomM4B ".toByteArray())

  private fun chpl(vararg chapters: Pair<Long, String>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(fullBox(version = 1))
    out.write(ByteArray(4))
    out.write(chapters.size)
    for ((ms, title) in chapters) {
      out.write(ByteBuffer.allocate(8).putLong(ms * 10_000).array())
      val t = title.toByteArray()
      out.write(t.size)
      out.write(t)
    }
    return box("chpl", out.toByteArray())
  }

  @Test
  fun readsNeroChapters() {
    val moov = box(
      "moov",
      header("mvhd", 1000, 600_000),
      box("trak", tkhd(1), box("mdia", header("mdhd", 44100, 600_000L * 44100 / 1000), hdlr("soun"))),
      box("udta", chpl(0L to "001", 200_000L to "002", 400_000L to "The End")),
    )
    val file = ftyp + box("mdat", ByteArray(100_000)) + moov
    val info = Mp4Chapters.read(BlockCachedSource(Bytes(file)))!!
    assertEquals(600_000, info.durationMs)
    assertEquals(
      listOf(Chapter(0, "Chapter 1"), Chapter(200_000, "Chapter 2"), Chapter(400_000, "The End")),
      info.chapters,
    )
  }

  @Test
  fun readsAppleChapterTrack() {
    val titles = listOf("Opening", "Middle", "Café")
    val samples = titles.map { t ->
      val b = t.toByteArray()
      ByteBuffer.allocate(2 + b.size).putShort(b.size.toShort()).put(b).array()
    }
    // The text samples sit at the start of mdat, after its 8-byte header.
    val mdatPayload = samples.reduce { a, b -> a + b } + ByteArray(50_000)
    val first = ftyp.size + 8L
    val offsets = samples.runningFold(first) { acc, s -> acc + s.size }.dropLast(1)

    val stbl = box(
      "stbl",
      box("stts", fullBox(), ints(2, 2, 100_000, 1, 50_000)),
      box("stsc", fullBox(), ints(1, 1, 1, 1)),
      box("stsz", fullBox(), ints(0, 3), ints(*samples.map { it.size.toLong() }.toLongArray())),
      box("stco", fullBox(), ints(3), ints(*offsets.toLongArray())),
    )
    val moov = box(
      "moov",
      header("mvhd", 1000, 250_000),
      box(
        "trak",
        tkhd(1),
        box("tref", box("chap", ints(2))),
        box("mdia", header("mdhd", 44100, 250_000L * 441 / 10), hdlr("soun"), box("minf", box("stbl"))),
      ),
      box("trak", tkhd(2), box("mdia", header("mdhd", 1000, 250_000), hdlr("text"), box("minf", stbl))),
    )
    val file = ftyp + box("mdat", mdatPayload) + moov
    val info = Mp4Chapters.read(Bytes(file))!!
    assertEquals(250_000, info.durationMs)
    assertEquals(
      listOf(Chapter(0, "Opening"), Chapter(100_000, "Middle"), Chapter(200_000, "Café")),
      info.chapters,
    )
  }

  @Test
  fun fileWithoutChaptersHasOnlyItsLength() {
    val moov = box("moov", header("mvhd", 1000, 90_000))
    val info = Mp4Chapters.read(Bytes(ftyp + box("mdat", ByteArray(10)) + moov))!!
    assertEquals(Mp4Info(90_000, emptyList()), info)
  }

  @Test
  fun notAnMp4() {
    assertNull(Mp4Chapters.read(Bytes("ID3 this is an mp3 file, not an mp4 at all".toByteArray())))
  }

  @Test
  fun blockCacheKeepsRemoteReadsFew() {
    val moov = box("moov", header("mvhd", 1000, 600_000), box("udta", chpl(0L to "A", 1_000L to "B")))
    val source = Bytes(ftyp + box("mdat", ByteArray(5_000_000)) + moov)
    Mp4Chapters.read(BlockCachedSource(source))
    // ftyp and mdat headers share the first block; moov and its children the last one or two.
    assertTrue("took ${source.reads} reads", source.reads <= 4)
  }

  @Test
  fun chaptersBecomeTracks() {
    val tracks = MediaInstaller.tracksForFile(
      itemKey = "gdrive:folder-x",
      firstIndex = 3,
      relativePath = "Book/Part 1.m4b",
      durationMs = 600_000,
      chapters = listOf(Chapter(0, "One"), Chapter(200_000, "Two"), Chapter(450_000, "Three")),
      remoteFileId = "file1",
    )
    assertEquals(listOf(3, 4, 5), tracks.map { it.index })
    assertEquals(listOf("One", "Two", "Three"), tracks.map { it.title })
    assertEquals(listOf(0L, 200_000L, 450_000L), tracks.map { it.startMs })
    assertEquals(listOf(200_000L, 450_000L, null), tracks.map { it.endMs })
    assertEquals(listOf(200_000L, 250_000L, 150_000L), tracks.map { it.durationMs })
    assertTrue(tracks.all { it.isChapter && it.remoteFileId == "file1" })
  }

  @Test
  fun fileWithOneChapterIsOneTrack() {
    val tracks = MediaInstaller.tracksForFile("k", 0, "Movie.m4a", 5_000, listOf(Chapter(0, "Only")), null)
    assertEquals(1, tracks.size)
    assertEquals("Movie", tracks[0].title)
    assertEquals(false, tracks[0].isChapter)
  }
}
