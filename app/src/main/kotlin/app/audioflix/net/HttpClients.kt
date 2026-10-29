package app.audioflix.net

import app.audioflix.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Callback
import java.io.IOException

// Cloudflare in front of AudioVault challenges non-browser user agents, so every request has to
// look like a regular browser.
private const val BROWSER_USER_AGENT =
  "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/151.0.0.0 Mobile Safari/537.36"

fun createHttpClient(cookieJar: PersistentCookieJar): OkHttpClient =
  OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain ->
      val request = chain.request().newBuilder()
        .header("User-Agent", BROWSER_USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .apply {
          if (chain.request().header("Accept") == null) {
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          }
        }
        .build()
      chain.proceed(request)
    }
    .build()

/**
 * A client for public APIs (GitHub, Wikipedia). It names the app honestly in its User-Agent, as
 * both services ask, and sends no cookies.
 */
fun createApiClient(): OkHttpClient {
  val userAgent = "AudioFlix/${BuildConfig.VERSION_NAME} (https://github.com/${BuildConfig.UPDATE_REPOSITORY})"
  return OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
    .build()
}

/** Executes the call without blocking a thread, cancelling it if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
  cont.invokeOnCancellation { runCatching { cancel() } }
  enqueue(object : Callback {
    override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }

    override fun onFailure(call: Call, e: IOException) {
      if (!cont.isCancelled) cont.resumeWithException(e)
    }
  })
}
