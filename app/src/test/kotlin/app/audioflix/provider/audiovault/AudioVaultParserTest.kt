package app.audioflix.provider.audiovault

import app.audioflix.provider.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures mirror the markup audiovault.net served in October 2026.
class AudioVaultParserTest {
  private val listing = """
    <table><thead><tr><th>ID</th><th>Name</th><th>Download</th></tr></thead>
    <tbody>
      <tr><td> 9501 </td><td> The Matrix (1999) </td>
        <td><a href="https://audiovault.net/download/9501" target="_blank">Download</a></td></tr>
      <tr><td>9502</td><td>The Matrix Reloaded (2003)</td>
        <td><a href="https://audiovault.net/download/9502">Download</a></td></tr>
    </tbody></table>
    <ul class="pagination" role="navigation">
      <li class="page-item"><a class="page-link" href="https://audiovault.net/movies?page=2">2</a></li>
      <li class="page-item"><a class="page-link" href="https://audiovault.net/movies?page=2" rel="next">&rsaquo;</a></li>
    </ul>
  """.trimIndent()

  private val home = """
    <h2>Featured Movies and TVShows</h2>
    <button class="collapsible"> Lioness - Season 3 (2026) </button>
    <div class="content"><p>CIA operative Joe McNamara and her team.</p>
      <a href="https://www.audiovault.net/download/27670">download it here</a></div>
    <button class="collapsible"> POWER BALLAD (2026) </button>
    <div class="content"><p>A wedding singer.</p>
      <a href="https://www.audiovault.net/download/27622">download it here</a></div>
    <h5> Recent Shows: </h5>
    <table><thead><tr><th>ID</th></tr></thead><tbody>
      <tr><td>27700</td><td>Reacher - Season 4</td><td><a href="https://audiovault.net/download/27700">Download</a></td></tr>
    </tbody></table>
    <h5> Recent Movies: </h5>
    <table><tbody>
      <tr><td>27701</td><td>Some Movie (2026)</td><td><a href="https://audiovault.net/download/27701">Download</a></td></tr>
    </tbody></table>
  """.trimIndent()

  @Test fun parsesListingRowsAndPagination() {
    val page = AudioVaultParser.parseListing(listing, MediaKind.MOVIE, 1)
    assertEquals(listOf("9501", "9502"), page.items.map { it.remoteId })
    assertEquals("The Matrix (1999)", page.items[0].title)
    assertEquals(MediaKind.MOVIE, page.items[0].kind)
    assertTrue(page.hasNext)
  }

  @Test fun lastPageHasNoNext() {
    val page = AudioVaultParser.parseListing(listing.substringBefore("<ul"), MediaKind.SHOW, 3)
    assertFalse(page.hasNext)
  }

  @Test fun emptyListing() {
    val page = AudioVaultParser.parseListing("<table><tbody></tbody></table>", MediaKind.MOVIE, 1)
    assertTrue(page.items.isEmpty())
    assertFalse(page.hasNext)
  }

  @Test fun parsesHomeSections() {
    val sections = AudioVaultParser.parseHome(home)
    assertEquals(listOf("Featured", "Recent Shows", "Recent Movies"), sections.map { it.title })

    val featured = sections[0].items
    assertEquals(listOf("27670", "27622"), featured.map { it.remoteId })
    assertEquals(MediaKind.SHOW, featured[0].kind)
    assertNull(featured[1].kind)
    assertEquals("A wedding singer.", featured[1].description)

    assertEquals(MediaKind.SHOW, sections[1].items.single().kind)
    assertEquals("27701", sections[2].items.single().remoteId)
  }

  @Test fun detectsLoginFormAndToken() {
    val form = """<form method="POST" action="https://audiovault.net/login">
      <input type="hidden" name="_token" value="abc123">
      <input id="password" type="password" name="password" required /></form>"""
    assertEquals("abc123", AudioVaultParser.parseCsrfToken(form))
    assertTrue(AudioVaultParser.isLoginForm(form))
    assertFalse(AudioVaultParser.isLoginForm(home))
  }

  @Test fun parsesContentDispositionFileNames() {
    assertEquals("Show S01.zip", AudioVaultParser.parseFileName("attachment; filename=\"Show S01.zip\""))
    assertEquals("movie.mp3", AudioVaultParser.parseFileName("attachment; filename=movie.mp3"))
    assertEquals("café.mp3", AudioVaultParser.parseFileName("attachment; filename=x.mp3; filename*=UTF-8''caf%C3%A9.mp3"))
    assertNull(AudioVaultParser.parseFileName(null))
  }
}
