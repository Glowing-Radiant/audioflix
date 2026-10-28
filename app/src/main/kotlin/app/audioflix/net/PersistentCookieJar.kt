package app.audioflix.net

import java.io.File
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Cookie jar persisted to a small text file, so provider sessions ("remember me" cookies) survive
 * app restarts without us ever storing the user's password.
 */
class PersistentCookieJar(private val file: File) : CookieJar {
  private val cookies = LinkedHashMap<String, Cookie>()

  init {
    load()
  }

  @Synchronized
  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    for (cookie in cookies) this.cookies[id(cookie)] = cookie
    pruneExpired()
    persist()
  }

  @Synchronized
  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    pruneExpired()
    return cookies.values.filter { it.matches(url) }
  }

  @Synchronized
  fun clear(domain: String) {
    cookies.values.removeAll { it.domain == domain || it.domain.endsWith(".$domain") }
    persist()
  }

  private fun id(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

  private fun pruneExpired() {
    val now = System.currentTimeMillis()
    cookies.values.removeAll { it.expiresAt < now }
  }

  // One cookie per line: "<origin url>\t<Set-Cookie header value>".
  private fun persist() {
    val text = cookies.values.joinToString("\n") { c ->
      val scheme = if (c.secure) "https" else "http"
      "$scheme://${c.domain}${c.path}\t$c"
    }
    runCatching {
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(text)
      tmp.renameTo(file)
    }
  }

  private fun load() {
    if (!file.exists()) return
    runCatching {
      file.readLines().forEach { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@forEach
        val url = runCatching { line.substring(0, tab).toHttpUrl() }.getOrNull() ?: return@forEach
        val cookie = Cookie.parse(url, line.substring(tab + 1)) ?: return@forEach
        cookies[id(cookie)] = cookie
      }
    }
    pruneExpired()
  }
}
