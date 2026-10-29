package app.audioflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadQuotaTest {
  private val hour = 60 * 60 * 1000L
  private val now = 100 * 24 * hour

  @Test fun keepsOnlyStartsFromTheLast24Hours() {
    val stored = listOf(now - 25 * hour, now - 24 * hour, now - 23 * hour, now - hour, now).joinToString(",")
    assertEquals(listOf(now - 23 * hour, now - hour, now), recentStarts(stored, now))
    assertEquals(emptyList<Long>(), recentStarts(null, now))
    assertEquals(listOf(now), recentStarts("junk,$now,", now))
  }

  @Test fun reportsWhatIsLeftAndWhenTheNextDownloadFreesUp() {
    val status = quotaStatus(25, listOf(now - 20 * hour, now - 2 * hour), now)
    assertEquals(2, status.used)
    assertEquals(23, status.remaining)
    assertEquals(now + 4 * hour, status.nextFreeAt)

    val full = quotaStatus(2, listOf(now - 3 * hour, now - hour, now), now)
    assertEquals(0, full.remaining)

    assertNull(quotaStatus(25, emptyList(), now).nextFreeAt)
  }
}
