package app.audioflix.provider.web

import app.audioflix.provider.RemoteEntry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures mirror the shape of the index pages Caddy, nginx and Apache serve for a folder.
class IndexParserTest {
  private val caddy = """
    <!DOCTYPE html><html><head><title>GraphicAudio</title></head><body>
    <table><tbody>
      <tr><td></td><td><a href=".."><span class="goup">Go up</span></a></td><td>&mdash;</td></tr>
      <tr class="file"><td></td><td><a href="./Alcatraz/">
        <svg><use xlink:href="#folder"></use></svg><span class="name">Alcatraz/</span></a></td>
        <td data-order="-1">&mdash;</td></tr>
      <tr class="file"><td></td><td><a href="./Dresden%20Files/">
        <span class="name">Dresden Files/</span></a></td><td data-order="-1">&mdash;</td></tr>
      <tr class="file"><td></td><td><a href="./Alcatraz%201%20-%20The%20Evil%20Librarians.zip">
        <span class="name">Alcatraz 1 - The Evil Librarians.zip</span></a></td>
        <td data-order="305381559">291 MiB</td></tr>
      <tr class="file"><td></td><td><a href="./cover.jpg"><span class="name">cover.jpg</span></a></td></tr>
      <tr><td></td><td><a href="?sort=namedirfirst&order=desc">Name</a></td></tr>
    </tbody></table>
    <footer>Served with <a href="https://caddyserver.com">Caddy</a></footer>
    </body></html>
  """.trimIndent()

  @Test
  fun readsFoldersAndFiles() {
    val folder = IndexParser.parse(caddy, "https://slj.io/audio/GraphicAudio/".toHttpUrl())
    assertEquals("GraphicAudio", folder.title)
    assertEquals(
      listOf(
        "Alcatraz" to RemoteEntry.Type.FOLDER,
        "Dresden Files" to RemoteEntry.Type.FOLDER,
        "Alcatraz 1 - The Evil Librarians.zip" to RemoteEntry.Type.FILE,
        "cover.jpg" to RemoteEntry.Type.OTHER,
      ),
      folder.entries.map { it.name to it.type },
    )
  }

  @Test
  fun entryIdsAreTheirAddresses() {
    val folder = IndexParser.parse(caddy, "https://slj.io/audio/GraphicAudio/".toHttpUrl())
    assertEquals(
      "https://slj.io/audio/GraphicAudio/Alcatraz/",
      WebRef.urlOf(folder.entries.first().id)?.toString(),
    )
    assertEquals(
      "https://slj.io/audio/GraphicAudio/Alcatraz%201%20-%20The%20Evil%20Librarians.zip",
      WebRef.urlOf(folder.entries[2].id)?.toString(),
    )
    assertEquals("https://slj.io/audio/GraphicAudio/", WebRef.urlOf(folder.id)?.toString())
  }

  @Test
  fun leavesOutParentAndOutsideLinks() {
    val html = """
      <html><body><pre>
      <a href="../">../</a>
      <a href="/">root</a>
      <a href="https://example.org/elsewhere/">another site</a>
      <a href="mailto:someone@example.com">mail</a>
      <a href="/audio/Other/">a sibling folder</a>
      <a href="Episode%201.mp3">Episode 1.mp3</a>
      <a href="Season%202/Episode%201.mp3">deeper</a>
      </pre></body></html>
    """.trimIndent()
    val folder = IndexParser.parse(html, "https://example.com/audio/Show/".toHttpUrl())
    assertEquals(listOf("Episode 1.mp3"), folder.entries.map { it.name })
  }

  @Test
  fun readsApacheListingWithShortenedNames() {
    // Apache cuts long names in the link text, so names come from the address instead.
    val html = """
      <html><head><title>Index of /audio/Show</title></head><body>
      <h1>Index of /audio/Show</h1>
      <table><tr><th><a href="?C=N;O=D">Name</a></th><th><a href="?C=S;O=A">Size</a></th></tr>
      <tr><td><a href="/audio/">Parent Directory</a></td></tr>
      <tr><td><a href="Season%201/">Season 1/</a></td><td>-</td></tr>
      <tr><td><a href="A%20Very%20Long%20Episode%20Name%20Indeed.mp3">A Very Long Episode Nam..&gt;</a></td>
      <td>31M</td></tr>
      </table></body></html>
    """.trimIndent()
    val folder = IndexParser.parse(html, "https://example.com/audio/Show/".toHttpUrl())
    assertEquals(
      listOf(
        "Season 1" to RemoteEntry.Type.FOLDER,
        "A Very Long Episode Name Indeed.mp3" to RemoteEntry.Type.FILE,
      ),
      folder.entries.map { it.name to it.type },
    )
  }

  @Test
  fun worksWithoutATrailingSlash() {
    val html = """<html><body><a href="/audio/Show/track.mp3">track.mp3</a></body></html>"""
    val folder = IndexParser.parse(html, "https://example.com/audio/Show".toHttpUrl())
    assertEquals("Show", folder.title)
    assertEquals("https://example.com/audio/Show/", WebRef.urlOf(folder.id)?.toString())
    assertEquals(listOf("track.mp3"), folder.entries.map { it.name })
  }

  @Test
  fun namesTheWholeSiteAfterItsHost() {
    assertEquals("example.com", IndexParser.folderTitle("https://example.com/".toHttpUrl()))
    assertEquals("audio", IndexParser.folderTitle("https://example.com/audio/?sort=name".toHttpUrl()))
  }

  @Test
  fun folderAddressesEndWithASlash() {
    assertEquals(
      "https://example.com/audio/",
      IndexParser.folderUrl("https://example.com/audio?sort=name#top".toHttpUrl()).toString(),
    )
    assertEquals(
      "https://example.com/audio/",
      IndexParser.folderUrl("https://example.com/audio/".toHttpUrl()).toString(),
    )
  }

  @Test
  fun storesTitlesAsRemoteIds() {
    val url = "https://slj.io/audio/GraphicAudio/".toHttpUrl()
    val id = WebRef.idOf(url)
    assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    assertEquals(WebRef.Folder(id), WebRef.parse("dir-$id"))
    assertEquals(WebRef.File(id), WebRef.parse("url-$id"))
    assertEquals(url, WebRef.urlOf(WebRef.parse("dir-$id")!!.id))
    assertNull(WebRef.parse(id))
    assertNull(WebRef.urlOf("not base64 at all !!"))
  }
}
