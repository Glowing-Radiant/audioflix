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
  /** The address the bytes finally came from, after redirects and warning pages. */
  val url: String? = null,
) : Closeable {
  override fun close() {
    input.close()
    onClose()
  }
}

/**
 * One file of a title made of several (a folder of episodes, say). [relativePath] is where it goes
 * inside the title's directory, with "/" between folders.
 */
data class RemoteFile(val id: String, val relativePath: String)

/**
 * A source of audio titles: anything that can list, search and hand out a downloadable file (mp3 or
 * a zip of mp3s) can implement this. Providers that aren't a searchable catalog, such as shared
 * Google Drive folders, keep the defaults for the parts they don't have.
 */
interface CatalogProvider {
  val id: String
  val displayName: String
  val supportedKinds: List<MediaKind>
  val requiresLogin: Boolean

  /** Where users can create an account, if the provider has accounts. */
  val signUpUrl: String? get() = null

  /** How many new downloads the provider allows in a day, or null for no limit. */
  val dailyDownloadLimit: Int? get() = null

  /** Ways to support or reach the provider, shown on the Account screen. */
  val supportLinks: List<ProviderLink> get() = emptyList()

  /**
   * Whether files can be downloaded over several connections at once, a piece on each, which is
   * often much faster. The user turns it on or off per provider in Settings.
   */
  val supportsFastDownloads: Boolean get() = false

  /** Whether faster downloads are on until the user changes it. */
  val fastDownloadsDefault: Boolean get() = true

  /** What turning faster downloads on means for this provider, said under its switch. */
  val fastDownloadsNote: String get() = ""

  /**
   * An address that serves the bytes of [fileId] itself (no warning or sign-in page in between) and
   * accepts byte ranges, for downloading over several connections. Null to download the usual way.
   */
  suspend fun rangedDownloadUrl(fileId: String): String? = null

  /** False for providers whose files are already on the phone, so copying them needs no network. */
  val needsNetwork: Boolean get() = true

  /** Signed-in account name (e.g. email), or null when signed out. */
  val account: StateFlow<String?>

  /** Curated sections for the provider's landing page (featured, recently added, ...). */
  suspend fun home(): List<CatalogSection> = emptyList()

  /** [page] is 1-based. */
  suspend fun browse(kind: MediaKind, page: Int): CatalogPage = CatalogPage(emptyList(), page, hasNext = false)

  suspend fun search(kind: MediaKind, query: String, page: Int): CatalogPage =
    CatalogPage(emptyList(), page, hasNext = false)

  suspend fun login(email: String, password: String): LoginResult =
    LoginResult.Error("$displayName doesn't use accounts.")

  suspend fun logout() {}

  /**
   * The files [remoteId] is made of, or null when it is a single download (then [openDownload] is
   * called with [remoteId] itself). Each file is then opened with [openDownload] by its own id.
   */
  suspend fun files(remoteId: String): List<RemoteFile>? = null

  /** The size of the file [fileId] in bytes, or -1 if unknown. Used for overall download progress. */
  suspend fun fileSize(fileId: String): Long = -1

  /** A URL the player can stream [fileId] from without signing in, or null if it can't be streamed. */
  fun streamUrl(fileId: String): String? = null

  /**
   * Opens the file for [remoteId], resuming from [resumeFrom] bytes when the server supports it.
   * Throws [AuthRequiredException] if the session is missing or expired.
   */
  suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream
}
