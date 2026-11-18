package app.audioflix.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRewindTest {
  private val minute = 60_000L

  @Test fun doesNothingWhenOff() {
    assertEquals(0L, AutoRewind(enabled = false).rewindMs(2 * 60 * minute))
  }

  @Test fun goesBackALittleAfterAShortPause() {
    val rewind = AutoRewind(enabled = true)
    assertEquals(5_000L, rewind.rewindMs(10_000))
    assertEquals(5_000L, rewind.rewindMs(29 * minute))
  }

  @Test fun goesBackFurtherAfterALongPause() {
    val rewind = AutoRewind(enabled = true)
    assertEquals(30_000L, rewind.rewindMs(30 * minute))
    assertEquals(30_000L, rewind.rewindMs(24 * 60 * minute))
  }

  @Test fun ignoresAQuickPauseAndPlay() {
    assertEquals(0L, AutoRewind(enabled = true).rewindMs(400))
  }

  @Test fun usesTheChosenAmounts() {
    val rewind = AutoRewind(enabled = true, shortSeconds = 10, longSeconds = 60, longPauseMinutes = 5)
    assertEquals(10_000L, rewind.rewindMs(4 * minute))
    assertEquals(60_000L, rewind.rewindMs(5 * minute))
  }
}
