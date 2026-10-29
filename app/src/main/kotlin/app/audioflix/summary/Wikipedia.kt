package app.audioflix.summary

import app.audioflix.net.await
import app.audioflix.provider.MediaKind
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** A short Wikipedia summary of a film or show. */
@Serializable
data class TitleSummary(
  val articleTitle: String,
  /** Wikipedia's one-line description, e.g. "1999 film by the Wachowskis". */
  val description: String?,
  /** The first few sentences of the article. */
  val extract: String,
  val url: String?,
  /** The opening of the article's Plot or Premise section, when it has one. */
  val plot: String? = null,
  /** That section's name, e.g. "Plot" or "Premise". */
  val plotHeading: String? = null,
  /** Bumped when summaries change shape, so older cached ones are looked up again. */
  val version: Int = 1,
)

internal const val SUMMARY_VERSION = 2

/**
 * Finds the English Wikipedia article for a title and summarises it from the start of its Plot or
 * Premise section, falling back to the start of its introduction.
 */
class WikipediaClient(private val http: OkHttpClient) {
  suspend fun lookup(query: TitleQuery): TitleSummary? {
    val article = findArticle(query) ?: return null
    val plot = plotOf(article.articleTitle, query.kind)
    return article.copy(plot = plot?.second, plotHeading = plot?.first, version = SUMMARY_VERSION)
  }

  /** The (heading, condensed text) of the article's plot section, or null when it has none. */
  private suspend fun plotOf(title: String, kind: MediaKind?): Pair<String, String>? {
    val section = plotSection(get(parseUrl(title).addQueryParameter("prop", "sections")), kind) ?: return null
    val html = get(parseUrl(title).addQueryParameter("prop", "text").addQueryParameter("section", section.index))
    val text = condensePlot(plotParagraphs(html)) ?: return null
    return section.heading to text
  }

  private suspend fun findArticle(query: TitleQuery): TitleSummary? {
    if (query.name.isBlank()) return null
    // Likely article names first ("Oppenheimer (film)"); search only when none of them fits.
    val direct = get(baseUrl().addQueryParameter("titles", candidateTitles(query).joinToString("|")))
    bestSummary(direct, query)?.let { return it }
    val kindWords = when (query.kind) {
      MediaKind.MOVIE -> "film"
      MediaKind.SHOW -> "television series"
      null -> null
    }
    val search = listOfNotNull(query.name, query.year?.toString(), kindWords).joinToString(" ")
    val found = get(
      baseUrl()
        .addQueryParameter("generator", "search")
        .addQueryParameter("gsrsearch", search)
        .addQueryParameter("gsrlimit", "10"),
    )
    return bestSummary(found, query)
  }

  private fun baseUrl(): HttpUrl.Builder = API.toHttpUrl().newBuilder()
    .addQueryParameter("action", "query")
    .addQueryParameter("format", "json")
    .addQueryParameter("formatversion", "2")
    .addQueryParameter("redirects", "1")
    .addQueryParameter("prop", "extracts|description|pageprops|info")
    .addQueryParameter("inprop", "url")
    .addQueryParameter("ppprop", "disambiguation")
    .addQueryParameter("exintro", "1")
    .addQueryParameter("explaintext", "1")
    .addQueryParameter("exsentences", "3")
    .addQueryParameter("exlimit", "max")

  private fun parseUrl(title: String): HttpUrl.Builder = API.toHttpUrl().newBuilder()
    .addQueryParameter("action", "parse")
    .addQueryParameter("format", "json")
    .addQueryParameter("formatversion", "2")
    .addQueryParameter("redirects", "1")
    .addQueryParameter("page", title)
    .addQueryParameter("disableeditsection", "1")
    .addQueryParameter("disablelimitreport", "1")

  private suspend fun get(url: HttpUrl.Builder): String =
    http.newCall(Request.Builder().url(url.build()).build()).await().use { response ->
      if (!response.isSuccessful) throw IOException("Wikipedia answered with HTTP ${response.code}.")
      withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
    }

  private companion object {
    const val API = "https://en.wikipedia.org/w/api.php"
  }
}

internal fun candidateTitles(query: TitleQuery): List<String> {
  val name = query.name
  val year = query.year
  val films = listOfNotNull(year?.let { "$name ($it film)" }, "$name (film)")
  val shows = listOfNotNull(
    year?.let { "$name ($it TV series)" },
    "$name (TV series)",
    "$name (American TV series)",
    "$name (British TV series)",
  )
  val byKind = when (query.kind) {
    MediaKind.MOVIE -> films
    MediaKind.SHOW -> shows
    null -> films + shows
  }
  return byKind + name
}

@Serializable
private data class QueryResponse(val query: QueryResult? = null)

@Serializable
private data class QueryResult(val pages: List<WikiPage> = emptyList())

@Serializable
private data class WikiPage(
  val title: String,
  /** Search rank, present only for search results. */
  val index: Int? = null,
  val missing: Boolean = false,
  val extract: String? = null,
  val description: String? = null,
  val pageprops: PageProps? = null,
  val fullurl: String? = null,
)

@Serializable
private data class PageProps(val disambiguation: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/** Picks the page that is about this film or show, or null when none clearly is. */
internal fun bestSummary(responseJson: String, query: TitleQuery): TitleSummary? {
  val pages = json.decodeFromString<QueryResponse>(responseJson).query?.pages.orEmpty()
  val best = pages
    .mapNotNull { page -> score(page, query)?.let { page to it } }
    // Highest score wins; on a tie, the better search rank.
    .maxWithOrNull(compareBy<Pair<WikiPage, Int>> { it.second }.thenByDescending { it.first.index ?: 0 })
    ?.first
    ?: return null
  return TitleSummary(
    articleTitle = best.title,
    description = best.description?.takeIf { it.isNotBlank() },
    extract = best.extract.orEmpty().trim(),
    url = best.fullurl,
  )
}

private fun score(page: WikiPage, query: TitleQuery): Int? {
  val extract = page.extract?.trim().orEmpty()
  if (page.missing || page.pageprops?.disambiguation != null || extract.isEmpty()) return null
  // "The Matrix (1999 film)" and "The Matrix" both name "The Matrix".
  // Anything looser matches sequels and namesakes ("Up" for "Up in the Air").
  val pageName = normalize(page.title.replace(Regex("""\s*\([^)]*\)$"""), ""))
  if (pageName != normalize(query.name)) return null
  var score = 10
  val lead = (page.description.orEmpty() + " " + extract.substringBefore(". ")).lowercase()
  val isFilm = "film" in lead || "movie" in lead
  val isShow = "television" in lead || "tv series" in lead || "sitcom" in lead || "miniseries" in lead ||
    ("series" in lead && "film series" !in lead)
  if (!isFilm && !isShow) return null // a person, book, place...
  score += when (query.kind) {
    MediaKind.MOVIE -> if (isFilm) 4 else -4
    MediaKind.SHOW -> if (isShow) 4 else -4
    null -> 0
  }
  if ("franchise" in lead || page.title.lowercase().startsWith("list of")) score -= 5
  val year = query.year?.toString()
  if (year != null && (year in page.title || year in lead || year in extract.take(300))) score += 3
  return score.takeIf { it >= 8 }
}

private fun normalize(text: String): String =
  text.lowercase()
    .replace("&", " and ")
    .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
    .trim()
    .removePrefix("the ")

// The plot section --------------------------------------------------------------------------

internal data class PlotSection(val index: String, val heading: String)

@Serializable
private data class ParseResponse(val parse: ParseResult? = null)

@Serializable
private data class ParseResult(val sections: List<WikiSection> = emptyList(), val text: String? = null)

@Serializable
private data class WikiSection(val line: String, val index: String)

/** Section names that tell the story, most useful first. */
private fun plotHeadings(kind: MediaKind?): List<String> = when (kind) {
  MediaKind.SHOW -> listOf("premise", "synopsis", "plot", "plot summary", "overview")
  else -> listOf("plot", "plot summary", "synopsis", "premise", "story")
}

/** Picks the Plot, Premise or Synopsis section from a `prop=sections` parse response. */
internal fun plotSection(responseJson: String, kind: MediaKind?): PlotSection? {
  val sections = json.decodeFromString<ParseResponse>(responseJson).parse?.sections.orEmpty()
    // Sections pulled in from templates have indexes like "T-1" and can't be fetched by number.
    .filter { it.index.all(Char::isDigit) }
    .map { PlotSection(it.index, it.line.replace(Regex("<[^>]+>"), "").trim()) }
  return plotHeadings(kind).firstNotNullOfOrNull { wanted ->
    sections.firstOrNull { it.heading.lowercase() == wanted }
  }
}

/** The paragraphs of a `prop=text` parse response, without footnote markers. */
internal fun plotParagraphs(responseJson: String): List<String> {
  val html = json.decodeFromString<ParseResponse>(responseJson).parse?.text ?: return emptyList()
  val doc = Jsoup.parse(html)
  doc.select("sup, style, table, .hatnote, .mw-editsection, .mw-empty-elt").remove()
  return doc.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
}

/**
 * The first few sentences of the plot: enough to say what it's about, stopping before the later
 * paragraphs where the spoilers are.
 */
internal fun condensePlot(paragraphs: List<String>, maxSentences: Int = 4, enoughChars: Int = 250): String? {
  val sentences = mutableListOf<String>()
  for (paragraph in paragraphs.take(2)) {
    for (sentence in splitSentences(paragraph)) {
      if (sentences.size >= maxSentences) break
      sentences += sentence
    }
    if (sentences.size >= maxSentences || sentences.sumOf { it.length } >= enoughChars) break
  }
  return sentences.joinToString(" ").takeIf { it.isNotBlank() }
}

private val sentenceEnd = Regex("""[.!?]["\u201D\u2019)\]]*\s+(?=["\u201C\u2018(\[]?[A-Z0-9])""")
private val abbreviations = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "lt", "gen", "col", "capt", "sgt", "prof", "vs", "no", "u.s", "u.k")

/** Splits prose into sentences without breaking after initials ("J. Robert") or "Dr.". */
internal fun splitSentences(text: String): List<String> {
  val sentences = mutableListOf<String>()
  var start = 0
  for (match in sentenceEnd.findAll(text)) {
    val end = match.range.first + match.value.trimEnd().length
    val candidate = text.substring(start, end)
    if (match.value.startsWith(".")) {
      val lastWord = candidate.trimEnd('.', '"', '\u201D', '\u2019', ')', ']').substringAfterLast(' ').lowercase()
      if ((lastWord.length == 1 && lastWord[0].isLetter()) || lastWord in abbreviations) continue
    }
    sentences += candidate.trim()
    start = match.range.last + 1
  }
  text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
  return sentences
}
