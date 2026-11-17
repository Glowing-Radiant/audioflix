package app.audioflix.provider.gdrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures mirror the markup drive.google.com served in November 2026.
class DriveParserTest {
  private val folderView = """
    <!DOCTYPE html><html><head><title>Radio Plays</title></head>
    <body class="flip-embedded"><div id="flip-contents" class="flip-contents flip-list-view">
    <div class="flip-entries">
      <div class="flip-entry" id="entry-1Qxkb1CC6cFpFVKLIfap5Or72VjSaSHwP" tabindex="0" role="link">
        <div class="flip-entry-info"><a href="https://drive.google.com/drive/folders/1Qxkb1CC6cFpFVKLIfap5Or72VjSaSHwP" target="_blank">
          <div class="flip-entry-list-icon"><div aria-label="Folder" class="drive-sprite-folder-list-shared-icon"></div></div>
          <div class="flip-entry-title">Season 1</div></a></div>
        <div class="flip-entry-last-modified"><div>Jun 2</div></div>
      </div>
      <div class="flip-entry" id="entry-1v_tvVe93cJ7KNMHdR1U7N5_6Oq_fXd0g" tabindex="0" role="link">
        <div class="flip-entry-info"><a href="https://drive.google.com/file/d/1v_tvVe93cJ7KNMHdR1U7N5_6Oq_fXd0g/view?usp=drive_web" target="_blank">
          <div class="flip-entry-title">The Hero&#39;s Return (Part 1 of 2).m4b</div></a></div>
        <div class="flip-entry-last-modified"><div>May 27</div></div>
      </div>
      <div class="flip-entry" id="entry-1abcDEFghiJKLmnoPQRstuVWXyz0123456789" tabindex="0" role="link">
        <div class="flip-entry-info"><a href="https://docs.google.com/document/d/1abcDEFghiJKLmnoPQRstuVWXyz0123456789/edit" target="_blank">
          <div class="flip-entry-title">Notes</div></a></div>
      </div>
    </div></div></body></html>
  """.trimIndent()

  @Test
  fun parsesFolderEntries() {
    val folder = DriveParser.parseFolder(folderView, "root123456789")
    assertEquals("Radio Plays", folder.title)
    assertEquals(
      listOf(
        DriveEntry("1Qxkb1CC6cFpFVKLIfap5Or72VjSaSHwP", "Season 1", DriveEntry.Type.FOLDER),
        DriveEntry("1v_tvVe93cJ7KNMHdR1U7N5_6Oq_fXd0g", "The Hero's Return (Part 1 of 2).m4b", DriveEntry.Type.FILE),
        DriveEntry("1abcDEFghiJKLmnoPQRstuVWXyz0123456789", "Notes", DriveEntry.Type.OTHER),
      ),
      folder.entries,
    )
  }

  @Test
  fun emptyFolderHasNoEntries() {
    val folder = DriveParser.parseFolder("<html><head><title>Empty</title></head><body></body></html>", "x")
    assertEquals("Empty", folder.title)
    assertTrue(folder.entries.isEmpty())
  }

  @Test
  fun parsesLinks() {
    val id = "1UqPWOsdCDzGiJwAcZfEla756oCPMc4Y7"
    assertEquals(DriveLink(id, DriveLink.Type.FOLDER), DriveParser.parseLink("https://drive.google.com/drive/folders/$id"))
    assertEquals(DriveLink(id, DriveLink.Type.FOLDER), DriveParser.parseLink(" https://drive.google.com/drive/u/0/folders/$id?usp=sharing "))
    assertEquals(DriveLink(id, DriveLink.Type.FILE), DriveParser.parseLink("https://drive.google.com/file/d/$id/view?usp=drive_link"))
    assertEquals(DriveLink(id, DriveLink.Type.UNKNOWN), DriveParser.parseLink("https://drive.google.com/open?id=$id"))
    assertEquals(DriveLink(id, DriveLink.Type.UNKNOWN), DriveParser.parseLink(id))
    assertNull(DriveParser.parseLink("https://example.com/something"))
    assertNull(DriveParser.parseLink("not a link"))
  }

  @Test
  fun followsVirusScanWarning() {
    val html = """
      <html><head><title>Google Drive - Virus scan warning</title></head><body>
      <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
        <input type="submit" id="uc-download-link" value="Download anyway"/>
        <input type="hidden" name="id" value="1gStLemDcnRhQh8myV9vvSE_MNg6w0uDf">
        <input type="hidden" name="export" value="download">
        <input type="hidden" name="confirm" value="t">
        <input type="hidden" name="uuid" value="511b0c19-92ab-4606-9f3d-b99921c432b2">
      </form></body></html>
    """.trimIndent()
    assertEquals(
      "https://drive.usercontent.google.com/download?id=1gStLemDcnRhQh8myV9vvSE_MNg6w0uDf&export=download" +
        "&confirm=t&uuid=511b0c19-92ab-4606-9f3d-b99921c432b2",
      DriveParser.parseDownloadForm(html),
    )
    assertNull(DriveParser.parseDownloadForm("<html><body>Hello</body></html>"))
  }

  @Test
  fun explainsDownloadLimits() {
    val html = "<html><body><p>Sorry, you can't view or download this file at this time.</p>" +
      "<p>Too many users have viewed or downloaded this file recently.</p></body></html>"
    assertTrue(DriveParser.describeErrorPage(html).contains("Try again in a day"))
  }

  @Test
  fun makesDuplicatePathsUnique() {
    val used = HashSet<String>()
    assertEquals("Season 1/Episode.mp3", uniquePath("Season 1/Episode.mp3", used))
    assertEquals("Season 1/Episode (2).mp3", uniquePath("Season 1/Episode.mp3", used))
    assertEquals("Season 1/episode (3).MP3", uniquePath("Season 1/episode.MP3", used))
    assertEquals("Other.mp3", uniquePath("Other.mp3", used))
  }

  @Test
  fun storesTitlesAsRemoteIds() {
    assertEquals(DriveRef.Folder("abc"), DriveRef.parse("folder-abc"))
    assertEquals(DriveRef.File("a-b_c"), DriveRef.parse("file-a-b_c"))
    assertNull(DriveRef.parse("12345"))
  }
}
