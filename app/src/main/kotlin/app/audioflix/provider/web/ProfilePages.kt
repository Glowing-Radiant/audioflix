package app.audioflix.provider.web

import app.audioflix.provider.RemoteEntry
import app.audioflix.provider.RemoteFolder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/**
 * Sites where each person has a profile page listing their uploads, and each upload has a page of
 * its own with a player. A profile is browsed like a folder, with each upload as an audio file. The
 * audio's own address is only on the upload's page, so it's looked up when a title is added.
 */
object ProfilePages {
  private const val HOST = "soundgasm.net"
  private const val PROFILE_SEGMENT = "u"

  /** The extension the sites' audio has; used to name uploads before their pages are read. */
  private const val AUDIO_EXTENSION = "m4a"

  /** The player's setup on an upload's page names the audio file, e.g. `m4a: "https://…"`. */
  private val MEDIA = Regex("""\b(?:m4a|mp3|oga|wav)\s*:\s*["']([^"']+)["']""")

  private const val MAX_ENTRIES = 5_000

  sealed interface Page {
    val url: HttpUrl
    val user: String

    data class Profile(override val url: HttpUrl, override val user: String) : Page
    data class Upload(override val url: HttpUrl, override val user: String, val slug: String) : Page
  }

  /** One upload's page, read: what it's called and where its audio is. */
  data class Audio(val title: String, val mediaUrl: HttpUrl)

  /** What [url] is on one of these sites, as an https address; null if it isn't one of their pages. */
  fun classify(url: HttpUrl): Page? {
    val host = url.host.lowercase()
    if (host != HOST && host != "www.$HOST") return null
    val segments = url.pathSegments.filter { it.isNotEmpty() }
    if (segments.size !in 2..3 || segments[0] != PROFILE_SEGMENT) return null
    val profile = HttpUrl.Builder().scheme("https").host(HOST).addPathSegment(PROFILE_SEGMENT).addPathSegment(segments[1])
    return if (segments.size == 2) {
      Page.Profile(profile.build(), segments[1])
    } else {
      Page.Upload(profile.addPathSegment(segments[2]).build(), segments[1], segments[2])
    }
  }

  /** The uploads a profile page lists, as audio files named after their titles. */
  fun parseProfile(html: String, profile: Page.Profile): RemoteFolder {
    val doc = Jsoup.parse(html, profile.url.toString())
    val entries = mutableListOf<RemoteEntry>()
    val seen = HashSet<String>()
    for (link in doc.select(".sound-details a[href]")) {
      if (entries.size >= MAX_ENTRIES) break
      val upload = link.attr("abs:href").toHttpUrlOrNull()?.let(::classify) as? Page.Upload ?: continue
      if (!seen.add(upload.url.toString())) continue
      val title = link.text().trim().ifBlank { titleFromSlug(upload.slug) }
      entries += RemoteEntry(WebRef.idOf(upload.url), fileName(title), RemoteEntry.Type.FILE, label = title)
    }
    return RemoteFolder(WebRef.idOf(profile.url), profile.user, entries)
  }

  /** Reads an upload's page, or null when it has no audio to play (removed, or not an upload). */
  fun parseUpload(html: String, page: Page.Upload): Audio? {
    val media = MEDIA.find(html)?.groupValues?.get(1)
      ?.let { page.url.resolve(it) }
      ?.takeIf { it.scheme == "https" }
      ?: return null
    val doc = Jsoup.parse(html, page.url.toString())
    val title = doc.selectFirst(".jp-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
      ?: titleFromSlug(page.slug)
    return Audio(title, media)
  }

  /** The file name an upload called [title] has in AudioFlix, the same whether found on its profile or by its own link. */
  fun fileName(title: String): String = "$title.$AUDIO_EXTENSION"

  private fun titleFromSlug(slug: String): String = slug.replace('-', ' ').trim().ifEmpty { "Audio" }
}
