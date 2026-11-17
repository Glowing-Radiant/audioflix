package app.audioflix.provider.gdrive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** What a pasted Drive link points at. */
data class DriveLink(val id: String, val type: Type) {
  enum class Type {
    FOLDER,
    FILE,

    /** `open?id=` links and bare ids can be either. */
    UNKNOWN,
  }
}

data class DriveEntry(val id: String, val name: String, val type: Type) {
  enum class Type {
    FOLDER,
    FILE,

    /** Google Docs, Sheets and the like: nothing that can be downloaded as audio. */
    OTHER,
  }
}

data class DriveFolder(val id: String, val title: String, val entries: List<DriveEntry>)

/**
 * Reads shared ("anyone with the link") Google Drive folders without an account or API key, from
 * the embeddable folder view Drive serves for websites, and the pages its download links return.
 */
object DriveParser {
  private const val ID = "[A-Za-z0-9_-]{10,}"
  private val folderPath = Regex("""/folders/($ID)""")
  private val filePath = Regex("""/file/d/($ID)""")
  private val idParam = Regex("""[?&]id=($ID)""")
  private val bareId = Regex("^$ID$")

  /** Finds the folder or file id in anything a user might paste: a share link or just the id. */
  fun parseLink(text: String): DriveLink? {
    val t = text.trim()
    folderPath.find(t)?.let { return DriveLink(it.groupValues[1], DriveLink.Type.FOLDER) }
    filePath.find(t)?.let { return DriveLink(it.groupValues[1], DriveLink.Type.FILE) }
    if ("google.com" in t) idParam.find(t)?.let { return DriveLink(it.groupValues[1], DriveLink.Type.UNKNOWN) }
    if (bareId.matches(t)) return DriveLink(t, DriveLink.Type.UNKNOWN)
    return null
  }

  /** Parses `drive.google.com/embeddedfolderview?id=<id>`. */
  fun parseFolder(html: String, folderId: String): DriveFolder {
    val doc = Jsoup.parse(html)
    val entries = doc.select("div.flip-entry").mapNotNull { el ->
      val id = el.id().removePrefix("entry-").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val name = el.selectFirst(".flip-entry-title")?.text()?.trim().orEmpty()
      if (name.isEmpty()) return@mapNotNull null
      val href = el.selectFirst("a[href]")?.attr("href").orEmpty()
      val type = when {
        folderPath.containsMatchIn(href) -> DriveEntry.Type.FOLDER
        filePath.containsMatchIn(href) && "drive.google.com" in href -> DriveEntry.Type.FILE
        else -> DriveEntry.Type.OTHER
      }
      DriveEntry(id, name, type)
    }
    val title = doc.title().trim().ifEmpty { "Shared folder" }
    return DriveFolder(folderId, title, entries)
  }

  /**
   * For large files Drive answers with a "can't scan this file for viruses" page instead of the
   * file. Returns the URL its "Download anyway" button leads to, or null if [html] isn't that page.
   */
  fun parseDownloadForm(html: String): String? {
    val form = Jsoup.parse(html).selectFirst("form#download-form") ?: return null
    val url = form.attr("action").toHttpUrlOrNull() ?: return null
    val builder = url.newBuilder()
    form.select("input[type=hidden][name]").forEach { builder.addQueryParameter(it.attr("name"), it.attr("value")) }
    return builder.build().toString()
  }

  /** A message for listeners when Drive sent a web page instead of the file. */
  fun describeErrorPage(html: String): String {
    val text = Jsoup.parse(html).text()
    return when {
      text.contains("Too many users have viewed or downloaded", ignoreCase = true) ||
        text.contains("quota", ignoreCase = true) ->
        "Google Drive is limiting downloads of this file because many people downloaded it recently. " +
          "Try again in a day."
      text.contains("Sign in", ignoreCase = true) || text.contains("Request access", ignoreCase = true) ->
        NOT_SHARED
      else -> "Google Drive didn't send the file. Try again later."
    }
  }

  const val NOT_SHARED =
    "This isn't shared publicly. Ask the owner to share it as \"Anyone with the link\"."
}
