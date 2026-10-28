package app.audioflix.provider.audiovault

import android.content.SharedPreferences
import app.audioflix.net.PersistentCookieJar
import app.audioflix.net.await
import app.audioflix.provider.AuthRequiredException
import app.audioflix.provider.CatalogPage
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.CatalogSection
import app.audioflix.provider.DownloadStream
import app.audioflix.provider.LoginResult
import app.audioflix.provider.MediaKind
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * audiovault.net: browsing and search are public, downloads need a signed-in session.
 *
 * We sign in with "remember me" and keep only the resulting cookies (see [PersistentCookieJar]);
 * the password itself is never stored.
 */
class AudioVaultProvider(
  private val client: OkHttpClient,
  private val cookieJar: PersistentCookieJar,
  private val prefs: SharedPreferences,
) : CatalogProvider {

  override val id = ID
  override val displayName = "AudioVault"
  override val supportedKinds = listOf(MediaKind.MOVIE, MediaKind.SHOW)
  override val requiresLogin = true
  override val signUpUrl = "$BASE_URL/register"

  private val _account = MutableStateFlow(prefs.getString(KEY_ACCOUNT, null))
  override val account: StateFlow<String?> = _account.asStateFlow()

  override suspend fun home(): List<CatalogSection> = withContext(Dispatchers.IO) {
    AudioVaultParser.parseHome(fetchHtml(BASE_URL))
  }

  override suspend fun browse(kind: MediaKind, page: Int): CatalogPage = withContext(Dispatchers.IO) {
    val url = listingUrl(kind).newBuilder().addQueryParameter("page", page.toString()).build()
    AudioVaultParser.parseListing(fetchHtml(url), kind, page)
  }

  override suspend fun search(kind: MediaKind, query: String, page: Int): CatalogPage = withContext(Dispatchers.IO) {
    val url = listingUrl(kind).newBuilder()
      .addQueryParameter("search", query)
      .addQueryParameter("page", page.toString())
      .build()
    AudioVaultParser.parseListing(fetchHtml(url), kind, page)
  }

  override suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
    // Start from a clean session so a stale or different account can't leak into this one.
    cookieJar.clear(HOST)
    try {
      val form = fetchHtml("$BASE_URL/login".toHttpUrl())
      val token = AudioVaultParser.parseCsrfToken(form)
        ?: return@withContext LoginResult.Error("Couldn't read the AudioVault sign-in form.")
      val body = FormBody.Builder()
        .add("_token", token)
        .add("email", email)
        .add("password", password)
        .add("remember", "on")
        .build()
      val request = Request.Builder().url("$BASE_URL/login").post(body).build()
      client.newCall(request).await().use { response ->
        val html = response.body?.string().orEmpty()
        // Laravel redirects back to the form on failure and to the home page on success.
        val backOnLogin = response.request.url.encodedPath.trimEnd('/') == "/login"
        if (response.isSuccessful && !backOnLogin && !AudioVaultParser.isLoginForm(html)) {
          setAccount(email)
          LoginResult.Success
        } else if (response.code == 419) {
          LoginResult.Error("The sign-in form expired. Please try again.")
        } else {
          cookieJar.clear(HOST)
          LoginResult.InvalidCredentials
        }
      }
    } catch (e: IOException) {
      LoginResult.Error(e.message ?: "Network error")
    }
  }

  override suspend fun logout() {
    cookieJar.clear(HOST)
    setAccount(null)
  }

  override suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream {
    val request = Request.Builder()
      .url("$BASE_URL/download/$remoteId")
      .header("Accept", "*/*")
      .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
      .build()
    val response = client.newCall(request).await()
    try {
      // Anonymous or expired sessions get redirected to the sign-in page.
      val landedOnLogin = response.request.url.encodedPath.trimEnd('/') == "/login"
      val contentType = response.header("Content-Type")
      if (landedOnLogin || contentType?.startsWith("text/html") == true) {
        setAccount(null)
        throw AuthRequiredException("AudioVault needs you to sign in again before downloading.")
      }
      if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")

      val body = response.body ?: throw IOException("Empty response")
      val partial = response.code == 206
      val contentRange = response.header("Content-Range")
      val offset = if (partial) parseRangeStart(contentRange) ?: 0L else 0L
      val total = when {
        partial -> parseRangeTotal(contentRange) ?: -1L
        else -> body.contentLength()
      }
      val fileName = AudioVaultParser.parseFileName(response.header("Content-Disposition"))
        ?: response.request.url.pathSegments.lastOrNull()?.takeIf { '.' in it }
      return DownloadStream(
        input = body.byteStream(),
        offset = offset,
        totalBytes = total,
        fileName = fileName,
        contentType = contentType,
        onClose = { response.close() },
      )
    } catch (t: Throwable) {
      response.close()
      throw t
    }
  }

  private fun setAccount(email: String?) {
    prefs.edit().apply { if (email == null) remove(KEY_ACCOUNT) else putString(KEY_ACCOUNT, email) }.apply()
    _account.value = email
  }

  private fun listingUrl(kind: MediaKind): HttpUrl = when (kind) {
    MediaKind.MOVIE -> "$BASE_URL/movies"
    MediaKind.SHOW -> "$BASE_URL/shows"
  }.toHttpUrl()

  private suspend fun fetchHtml(url: String) = fetchHtml(url.toHttpUrl())

  private suspend fun fetchHtml(url: HttpUrl): String {
    val response = client.newCall(Request.Builder().url(url).build()).await()
    return response.use {
      if (!it.isSuccessful) throw IOException("AudioVault returned HTTP ${it.code}")
      it.body?.string().orEmpty()
    }
  }

  private fun parseRangeStart(header: String?): Long? =
    header?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

  private fun parseRangeTotal(header: String?): Long? =
    header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

  companion object {
    const val ID = "audiovault"
    private const val HOST = "audiovault.net"
    private const val BASE_URL = "https://$HOST"
    private const val KEY_ACCOUNT = "audiovault.account"
  }
}
