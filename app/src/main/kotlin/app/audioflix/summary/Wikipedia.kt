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

/** A short Wikipedia summary of a film or show. */
@Serializable
data class TitleSummary(
  val articleTitle: String,
  /** Wikipedia's one-line description, e.g. "1999 film by the Wachowskis". */
  val description: String?,
  /** The first few sentences of the article. */
  val extract: String,
  val url: String?,
)

/** Finds the English Wikipedia article for a title and returns the start of its introduction. */
class WikipediaClient(private val http: OkHttpClient) {
  suspend fun lookup(query: TitleQuery): TitleSummary? {
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
