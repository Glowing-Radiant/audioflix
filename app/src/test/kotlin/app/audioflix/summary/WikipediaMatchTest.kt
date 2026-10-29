package app.audioflix.summary

import app.audioflix.provider.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikipediaMatchTest {
  @Test fun cleansYearsAndSeasonsFromProviderTitles() {
    assertEquals(TitleQuery("The Matrix", 1999, MediaKind.MOVIE), titleQueryOf("The Matrix (1999)", MediaKind.MOVIE))
    assertEquals(TitleQuery("The Bear", null, MediaKind.SHOW), titleQueryOf("The Bear - Season 3", null))
    assertEquals(TitleQuery("Shrinking", null, MediaKind.SHOW), titleQueryOf("Shrinking Season 2", MediaKind.SHOW))
    assertEquals(TitleQuery("Dune: Part Two", 2024, null), titleQueryOf("Dune: Part Two (2024)", null))
  }

  @Test fun triesSpecificArticleNamesBeforeTheBareTitle() {
    assertEquals(
      listOf("Oppenheimer (2023 film)", "Oppenheimer (film)", "Oppenheimer"),
      candidateTitles(TitleQuery("Oppenheimer", 2023, MediaKind.MOVIE)),
    )
  }

  @Test fun prefersTheFilmOverTheFranchiseAndOtherFilms() {
    val json = """
      {"query": {"pages": [
        {"title": "The Matrix (franchise)", "index": 3, "description": "American cyberpunk media franchise",
         "extract": "The Matrix is an American cyberpunk media franchise consisting of four feature films, beginning with The Matrix (1999)."},
        {"title": "The Matrix", "index": 1, "description": "1999 film by the Wachowskis",
         "extract": "The Matrix is a 1999 science fiction action film written and directed by the Wachowskis.",
         "fullurl": "https://en.wikipedia.org/wiki/The_Matrix"},
        {"title": "The Matrix Reloaded", "index": 2, "description": "2003 film by the Wachowskis",
         "extract": "The Matrix Reloaded is a 2003 science fiction action film."}
      ]}}
    """.trimIndent()

    val summary = bestSummary(json, TitleQuery("The Matrix", 1999, MediaKind.MOVIE))

    assertEquals("The Matrix", summary?.articleTitle)
    assertEquals("1999 film by the Wachowskis", summary?.description)
    assertEquals("https://en.wikipedia.org/wiki/The_Matrix", summary?.url)
  }

  @Test fun picksTheShowAndSkipsMissingAndDisambiguationPages() {
    val json = """
      {"query": {"pages": [
        {"title": "The Bear (American TV series)", "missing": true},
        {"title": "The Bear", "pageprops": {"disambiguation": ""}, "extract": "The Bear may refer to:"},
        {"title": "The Bear (1988 film)", "description": "1988 film by Jean-Jacques Annaud",
         "extract": "The Bear is a 1988 French adventure drama film."},
        {"title": "The Bear (TV series)", "description": "American comedy-drama television series",
         "extract": "The Bear is an American comedy-drama television series created by Christopher Storer."}
      ]}}
    """.trimIndent()

    assertEquals("The Bear (TV series)", bestSummary(json, TitleQuery("The Bear", null, MediaKind.SHOW))?.articleTitle)
  }

  @Test fun rejectsPagesThatAreNotAboutAFilmOrShow() {
    val json = """
      {"query": {"pages": [
        {"title": "Oppenheimer", "description": "Surname", "extract": "Oppenheimer is a surname."},
        {"title": "Alan Oppenheimer", "index": 1, "description": "American actor",
         "extract": "Alan Oppenheimer is an American actor."}
      ]}}
    """.trimIndent()

    assertNull(bestSummary(json, TitleQuery("Oppenheimer", 2023, MediaKind.MOVIE)))
  }

  @Test fun picksPlotForFilmsAndPremiseForShows() {
    val json = """
      {"parse": {"sections": [
        {"line": "Synopsis", "index": "T-1"},
        {"line": "<i>Plot</i>", "index": "1"},
        {"line": "Premise", "index": "2"},
        {"line": "Cast", "index": "3"}
      ]}}
    """.trimIndent()

    assertEquals(PlotSection("1", "Plot"), plotSection(json, MediaKind.MOVIE))
    assertEquals(PlotSection("2", "Premise"), plotSection(json, MediaKind.SHOW))
    assertNull(plotSection("""{"parse": {"sections": [{"line": "Cast", "index": "1"}]}}""", null))
    assertNull(plotSection("""{"error": {"code": "missingtitle"}}""", null))
  }

  @Test fun readsSectionParagraphsWithoutFootnotes() {
    val html = "<div><h2>Plot</h2><p>Neo meets Trinity.<sup class=\\\"reference\\\">[1]</sup> </p><p></p><p>He wakes up.</p></div>"
    val json = """{"parse": {"text": "$html"}}"""

    assertEquals(listOf("Neo meets Trinity.", "He wakes up."), plotParagraphs(json))
  }

  @Test fun keepsTheOpeningSentencesOfThePlot() {
    val first = "In the 1930s, J. Robert Oppenheimer teaches at Berkeley. Dr. Lawrence warns him. He moves to the U.S. Army project."
    assertEquals(
      listOf("In the 1930s, J. Robert Oppenheimer teaches at Berkeley.", "Dr. Lawrence warns him.", "He moves to the U.S. Army project."),
      splitSentences(first),
    )

    // A short first paragraph continues into the second, up to four sentences.
    assertEquals(
      "$first It begins. It ends.".substringBefore(" It ends."),
      condensePlot(listOf(first, "It begins. It ends. Credits roll.")),
    )
    // A long first paragraph is enough on its own.
    val long = "A".repeat(300) + " is long."
    assertEquals(long, condensePlot(listOf(long, "Spoiler.")))
    assertNull(condensePlot(emptyList()))
  }
}
