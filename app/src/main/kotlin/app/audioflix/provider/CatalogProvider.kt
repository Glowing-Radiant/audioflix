package app.audioflix.provider

import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

enum class MediaKind { MOVIE, SHOW }

/** A title as listed by a provider, before it is in the local library. */
data class CatalogItem(
  val providerId: String,
  val remoteId: String,
  val title: String,
  /** Null when the provider listing doesn't say (e.g. mixed "featured" lists). */
  val kind: MediaKind?,
  val description: String? = null,
) {
  val key: String get() = libraryKey(providerId, remoteId)
}

fun libraryKey(providerId: String, remoteId: String) = "$providerId:$remoteId"

data class CatalogPage(val items: List<CatalogItem>, val page: Int, val hasNext: Boolean)

data class CatalogSection(val title: String, val items: List<CatalogItem>)

/** A link to the people behind a provider, such as a donation page or community chat. */
data class ProviderLink(val label: String, val url: String)

sealed interface LoginResult {
  data object Success : LoginResult
  data object InvalidCredentials : LoginResult
  data class Error(val message: String) : LoginResult
}

/** Thrown when a provider needs the user to sign in (again) before it can serve a request. */
class AuthRequiredException(message: String = "Sign in required") : Exception(message)

/**
 * An open download body. [offset] is where this stream starts within the file: non-zero only when
 * the server honoured a resume request.
 */
class DownloadStream(
  val input: InputStream,
  val offset: Long,
  /** Total file size, or -1 if unknown. */
  val totalBytes: Long,
  val fileName: String?,
  val contentType: String?,
  private val onClose: () -> Unit,
) : Closeable {
  override fun close() {
    input.close()
    onClose()
  }
}

/**
 * A source of audio titles. AudioVault is the only one today; anything that can list, search and
 * hand out a downloadable file (mp3 or a zip of mp3s) can implement this.
 */
interface CatalogProvider {
  val id: String
  val displayName: String
  val supportedKinds: List<MediaKind>
  val requiresLogin: Boolean

  /** Where users can create an account, if the provider has accounts. */
  val signUpUrl: String?

  /** How many new downloads the provider allows in a day, or null for no limit. */
  val dailyDownloadLimit: Int? get() = null

  /** Ways to support or reach the provider, shown on the Account screen. */
  val supportLinks: List<ProviderLink> get() = emptyList()

  /** Signed-in account name (e.g. email), or null when signed out. */
  val account: StateFlow<String?>

  /** Curated sections for the provider's landing page (featured, recently added, ...). */
  suspend fun home(): List<CatalogSection>

  /** [page] is 1-based. */
  suspend fun browse(kind: MediaKind, page: Int): CatalogPage

  suspend fun search(kind: MediaKind, query: String, page: Int): CatalogPage

  suspend fun login(email: String, password: String): LoginResult

  suspend fun logout()

  /**
   * Opens the file for [remoteId], resuming from [resumeFrom] bytes when the server supports it.
   * Throws [AuthRequiredException] if the session is missing or expired.
   */
  suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream
}
