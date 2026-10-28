package app.audioflix.provider.audiovault

import app.audioflix.provider.CatalogItem
import app.audioflix.provider.CatalogPage
import app.audioflix.provider.CatalogSection
import app.audioflix.provider.MediaKind
import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * HTML scraping for audiovault.net. The site has no API: listings are plain `<table>`s of
 * (ID, Name, Download link) rows with Laravel pagination, and the home page carries "featured"
 * collapsibles plus "Recent Movies/Shows" tables.
 */
object AudioVaultParser {
  private val downloadIdRegex = Regex("""/download/(\d+)""")
  private val seasonRegex = Regex("""\bseason\s*\d+""", RegexOption.IGNORE_CASE)

  fun parseListing(html: String, kind: MediaKind, page: Int): CatalogPage {
    val doc = Jsoup.parse(html)
    val table = doc.selectFirst("tbody")
    val items = table?.let { parseRows(it, kind) }.orEmpty()
    val hasNext = doc.selectFirst("a[rel=next]") != null
    return CatalogPage(items, page, hasNext)
  }

  fun parseHome(html: String): List<CatalogSection> {
    val doc = Jsoup.parse(html)
    val sections = mutableListOf<CatalogSection>()

    val featured = doc.select("button.collapsible").mapNotNull { button ->
      val content = button.nextElementSibling() ?: return@mapNotNull null
      val href = content.selectFirst("a[href*=/download/]")?.attr("href") ?: return@mapNotNull null
      val id = downloadIdRegex.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
      val title = button.text().trim()
      CatalogItem(
        providerId = AudioVaultProvider.ID,
        remoteId = id,
        title = title,
        kind = if (seasonRegex.containsMatchIn(title)) MediaKind.SHOW else null,
        description = content.selectFirst("p")?.text()?.trim()?.ifEmpty { null },
      )
    }
    if (featured.isNotEmpty()) sections += CatalogSection("Featured", featured)

    for (heading in doc.select("h5")) {
      val text = heading.text().trim().removeSuffix(":")
      if (!text.startsWith("Recent", ignoreCase = true)) continue
      val kind = when {
        text.contains("movie", ignoreCase = true) -> MediaKind.MOVIE
        text.contains("show", ignoreCase = true) -> MediaKind.SHOW
        else -> continue
      }
      val tbody = nextTableBody(heading) ?: continue
      val items = parseRows(tbody, kind)
      if (items.isNotEmpty()) sections += CatalogSection(text, items)
    }
    return sections
  }

  fun parseCsrfToken(html: String): String? =
    Jsoup.parse(html).selectFirst("input[name=_token]")?.attr("value")?.ifEmpty { null }

  /** True when the page is (or embeds) the sign-in form, i.e. we are not authenticated. */
  fun isLoginForm(html: String): Boolean =
    Jsoup.parse(html).selectFirst("input[type=password]") != null

  /** Extracts the file name from a Content-Disposition header (RFC 6266, incl. `filename*`). */
  fun parseFileName(contentDisposition: String?): String? {
    if (contentDisposition.isNullOrBlank()) return null
    Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
      .find(contentDisposition)
      ?.let { m ->
        val charset = m.groupValues[1].ifBlank { "UTF-8" }
        return runCatching { URLDecoder.decode(m.groupValues[2].trim(), charset) }.getOrNull()
      }
    Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(contentDisposition)
      ?.let { return it.groupValues[1].ifBlank { null } }
    Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)
      ?.let { return it.groupValues[1].trim().ifBlank { null } }
    return null
  }

  private fun parseRows(tbody: Element, kind: MediaKind): List<CatalogItem> =
    tbody.select("tr").mapNotNull { row ->
      val cells = row.select("td")
      if (cells.size < 2) return@mapNotNull null
      val listedId = cells[0].text().trim()
      val title = cells[1].text().trim()
      val href = cells.getOrNull(2)?.selectFirst("a[href]")?.attr("href").orEmpty()
      val id = downloadIdRegex.find(href)?.groupValues?.get(1) ?: listedId.takeIf { it.all(Char::isDigit) }
      if (id.isNullOrEmpty() || title.isEmpty()) return@mapNotNull null
      CatalogItem(AudioVaultProvider.ID, id, title, kind)
    }

  /** The first `<tbody>` that follows [start] in document order (like bs4's `find_next`). */
  private fun nextTableBody(start: Element): Element? {
    var el: Element? = start.nextElementSibling()
    while (el != null) {
      if (el.tagName() == "tbody") return el
      el.selectFirst("tbody")?.let { return it }
      if (el.tagName() == "h5") return null // ran into the next section
      el = el.nextElementSibling()
    }
    return null
  }
}
