package app.audioflix.provider.web

import app.audioflix.download.MediaInstaller
import app.audioflix.provider.RemoteEntry
import app.audioflix.provider.RemoteFolder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/**
 * Reads the file list a web server sends for a folder it serves: the automatic index pages of
 * Caddy, nginx, Apache and the like, and any other page that simply links to the files next to it.
 *
 * Only links that stay inside the folder count, so navigation, sorting and "parent directory"
 * links are left out. A link ending in "/" is a subfolder; everything else is a file, named after
 * its address rather than its link text, which some servers shorten.
 */
object IndexParser {
  private const val MAX_ENTRIES = 5_000

  /** Parses the listing [html] a server sent for [url]. */
  fun parse(html: String, url: HttpUrl): RemoteFolder {
    val base = folderUrl(url)
    val doc = Jsoup.parse(html, base.toString())
    val entries = mutableListOf<RemoteEntry>()
    val seen = HashSet<String>()
    for (link in doc.select("a[href]")) {
      if (entries.size >= MAX_ENTRIES) break
      val target = link.attr("abs:href").toHttpUrlOrNull() ?: continue
      if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) continue
      // The listing's own sorting and filtering links point back at the folder with a query.
      if (target.querySize > 0) continue
      val path = target.encodedPath
      if (!path.startsWith(base.encodedPath)) continue
      val rest = path.removePrefix(base.encodedPath)
      // Only what's directly in this folder: nothing deeper, and not the folder itself.
      if (rest.isEmpty() || '/' in rest.trimEnd('/')) continue
      if (!seen.add(path)) continue
      val name = target.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
      val type = when {
        rest.endsWith("/") -> RemoteEntry.Type.FOLDER
        MediaInstaller.isAudioName(name) || MediaInstaller.isArchiveName(name) -> RemoteEntry.Type.FILE
        else -> RemoteEntry.Type.OTHER
      }
      entries += RemoteEntry(WebRef.idOf(target), name, type)
    }
    return RemoteFolder(WebRef.idOf(base), folderTitle(base), entries)
  }

  /** [url] as a folder address: no query or fragment, and ending in "/". */
  fun folderUrl(url: HttpUrl): HttpUrl {
    val builder = url.newBuilder().query(null).fragment(null)
    if (!url.encodedPath.endsWith("/")) builder.addPathSegment("")
    return builder.build()
  }

  /** What to call the folder at [url]: its own name, or the website's when it's the whole site. */
  fun folderTitle(url: HttpUrl): String =
    url.pathSegments.lastOrNull { it.isNotEmpty() }?.takeIf { it.isNotBlank() } ?: url.host
}
