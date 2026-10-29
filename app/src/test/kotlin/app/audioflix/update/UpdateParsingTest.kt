package app.audioflix.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParsingTest {
  @Test
  fun parsesVersionsAndComparesNumerically() {
    assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
    assertEquals(AppVersion(0, 10, 0), AppVersion.parse("0.10.0"))
    assertNull(AppVersion.parse("1.2"))
    assertNull(AppVersion.parse("1.2.3-beta"))
    assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
  }

  @Test
  fun offersOnlyNewerStableReleasesWithAnApk() {
    val json = """
      [
        {"tag_name": "v0.3.0", "name": "", "body": "Third", "draft": true, "prerelease": false,
         "assets": [{"name": "audioflix-0.3.0.apk", "size": 1, "browser_download_url": "https://x/3.apk"}]},
        {"tag_name": "v0.2.1", "name": "AudioFlix 0.2.1", "body": "Fix", "prerelease": false,
         "published_at": "2026-10-29T10:00:00Z",
         "assets": [{"name": "audioflix-0.2.1.apk", "size": 42, "digest": "sha256:ABCDEF",
                     "browser_download_url": "https://x/21.apk"}]},
        {"tag_name": "v0.2.0", "body": "Second", "prerelease": false,
         "assets": [{"name": "audioflix-0.2.0.apk", "size": 40, "browser_download_url": "https://x/20.apk"}]},
        {"tag_name": "v0.2.2-rc1", "prerelease": true, "assets": []},
        {"tag_name": "v0.1.5", "body": "No apk", "assets": [{"name": "notes.txt", "browser_download_url": "https://x/n"}]},
        {"tag_name": "v0.1.0", "body": "Installed", "assets": [{"name": "a.apk", "browser_download_url": "https://x/10.apk"}]}
      ]
    """.trimIndent()

    val releases = newerReleases(json, AppVersion(0, 1, 0))

    assertEquals(listOf("0.2.1", "0.2.0"), releases.map { it.version.toString() })
    val latest = releases.first()
    assertEquals("https://x/21.apk", latest.apkUrl)
    assertEquals(42L, latest.apkSize)
    assertEquals("abcdef", latest.apkSha256)
    assertEquals("AudioFlix 0.2.0", releases[1].title)
    assertNull(releases[1].apkSha256)
  }

  @Test
  fun releaseNotesKeepStructureAndDropMarkdown() {
    val notes = """
      ### Added
      - **Updater**: checks [GitHub](https://github.com) for new versions
        and installs them.
      * Uses `PackageInstaller`

      ### Fixed
      Plain paragraph
      continues here.
    """.trimIndent()

    assertEquals(
      listOf(
        NotesBlock.Heading("Added"),
        NotesBlock.Bullet("Updater: checks GitHub for new versions and installs them."),
        NotesBlock.Bullet("Uses PackageInstaller"),
        NotesBlock.Heading("Fixed"),
        NotesBlock.Paragraph("Plain paragraph continues here."),
      ),
      parseReleaseNotes(notes),
    )
  }
}
