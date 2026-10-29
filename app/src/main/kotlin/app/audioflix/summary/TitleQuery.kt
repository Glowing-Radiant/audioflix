package app.audioflix.summary

import app.audioflix.provider.MediaKind

/** What to look a catalog title up as: its bare name, release year and kind when known. */
data class TitleQuery(val name: String, val year: Int?, val kind: MediaKind?)

private val seasonPattern = Regex("""[\s:,(\[-]*\bseasons?\s*\d+.*$""", RegexOption.IGNORE_CASE)
private val yearPattern = Regex("""\s*[(\[](\d{4})[)\]]\s*$""")

/** Turns provider titles like "The Matrix (1999)" or "The Bear - Season 3" into a query. */
fun titleQueryOf(title: String, kind: MediaKind?): TitleQuery {
  var name = title.trim()
  var resolvedKind = kind
  if (seasonPattern.containsMatchIn(name)) {
    name = name.replace(seasonPattern, "")
    resolvedKind = MediaKind.SHOW
  }
  val year = yearPattern.find(name)?.groupValues?.get(1)?.toInt()
  name = name.replace(yearPattern, "").trim().trimEnd('-', ':', ',').trim()
  return TitleQuery(name, year, resolvedKind)
}
