package app.audioflix.download

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
  @Test fun sortsEpisodeNumbersNumerically() {
    val files = listOf("Show/Episode 10.mp3", "Show/Episode 2.mp3", "Show/Episode 1.mp3", "Show/episode 03.mp3")
    assertEquals(
      listOf("Show/Episode 1.mp3", "Show/Episode 2.mp3", "Show/episode 03.mp3", "Show/Episode 10.mp3"),
      files.sortedWith(NaturalOrder),
    )
  }

  @Test fun sortsSeasonFoldersBeforeEpisodes() {
    val files = listOf("S10/E1.mp3", "S2/E10.mp3", "S2/E9.mp3", "S1/E1.mp3")
    assertEquals(listOf("S1/E1.mp3", "S2/E9.mp3", "S2/E10.mp3", "S10/E1.mp3"), files.sortedWith(NaturalOrder))
  }
}
