package app.audioflix.provider.web

import android.content.SharedPreferences
import app.audioflix.download.MediaInstaller
import app.audioflix.net.await
import app.audioflix.net.contentDispositionFileName
import app.audioflix.net.contentRangeStart
import app.audioflix.net.contentRangeTotal
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.DownloadStream
import app.audioflix.provider.FolderProvider
import app.audioflix.provider.FolderUnavailableException
import app.audioflix.provider.InvalidLinkException
import app.audioflix.provider.LinkTarget
import app.audioflix.provider.MediaKind
import app.audioflix.provider.RemoteEntry
import app.audioflix.provider.RemoteFile
import app.audioflix.provider.RemoteFolder
import app.audioflix.provider.SavedFolder
import app.audioflix.provider.SavedFolderStore
import app.audioflix.provider.collectFolderFiles
import app.audioflix.provider.uniquePath
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Folders a web server simply serves as files, with the file list it sends for them: no account, no
 * API, nothing but the address. A library title is either a whole folder (with its subfolders) or a
 * single file, and its audio can be streamed straight from the website or downloaded.
 */
class WebFolderProvider(
  private val client: OkHttpClient,
  prefs: SharedPreferences,
) : FolderProvider {
  override val id = ID
  override val displayName = "Website folder"
  override val supportedKinds = emptyList<MediaKind>()
  override val requiresLogin = false
  override val account: StateFlow<String?> = MutableStateFlow(null)

  override val linkLabel = "Folder address"
  override val linkHelp =
    "Paste the address of a folder on a website that lists the files in it, like " +
      "https://example.com/audio/. You can also paste the address of a single audio or zip file, " +
      "or, on some sites where people share recordings, a person's profile page to browse what " +
      "they've shared, or the page of one recording. Nothing to sign in to."

  override val supportsFastDownloads = true

  override val fastDownloadsNote =
    "Downloads from websites use several connections at once, a piece of the file on each, which is " +
      "usually faster. Some small sites allow only a few connections; if downloads fail, turn this off."

  override suspend fun rangedDownloadUrl(fileId: String): String = fileUrl(fileId).toString()

  private val saved = SavedFolderStore(prefs, KEY_SAVED)
  override val savedFolders: StateFlow<List<SavedFolder>> = saved.folders

  override fun saveFolder(folder: SavedFolder) = saved.save(folder)

  override fun removeFolder(folderId: String) = saved.remove(folderId)

  override suspend fun listFolder(folderId: String): RemoteFolder {
    val profile = WebRef.urlOf(folderId)?.let(ProfilePages::classify) as? ProfilePages.Page.Profile
    if (profile != null) {
      val (html, _) = fetchPage(profile.url)
      return ProfilePages.parseProfile(html, profile)
    }
    val (html, finalUrl) = fetchPage(folderUrl(folderId))
    // Parsed against the address we ended up at, so a redirect to the real folder still works.
    return IndexParser.parse(html, finalUrl)
  }

  /** The web page at [url] and the address it finally came from. */
  private suspend fun fetchPage(url: HttpUrl): Pair<String, HttpUrl> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("Accept", "text/html,*/*").build()
    client.newCall(request).await().use { response ->
      when {
        response.code == 401 || response.code == 403 -> throw FolderUnavailableException(
          "${url.host} doesn't let AudioFlix read this page. It may need a sign-in that AudioFlix can't do.",
        )
        response.code == 404 || response.code == 410 ->
          throw FolderUnavailableException("There's nothing at that address any more.")
        !response.isSuccessful -> throw IOException("${url.host} returned HTTP ${response.code}")
      }
      val contentType = response.header("Content-Type").orEmpty()
      if (contentType.isNotEmpty() && !contentType.startsWith("text/html") && !contentType.startsWith("application/xhtml")) {
        throw FolderUnavailableException(
          "That address isn't a folder listing: ${url.host} sent a file instead of a list of files.",
        )
      }
      response.body?.string().orEmpty() to response.request.url
    }
  }

  /** Reads an upload's page for its title and the address of its audio. */
  private suspend fun readUpload(page: ProfilePages.Page.Upload): ProfilePages.Audio {
    val (html, _) = fetchPage(page.url)
    return ProfilePages.parseUpload(html, page)
      ?: throw FolderUnavailableException("There's no audio on that page. It may have been removed.")
  }

  /** A folder address, the address of a single audio file or zip, or a profile or upload page. */
  override suspend fun resolveLink(text: String): LinkTarget {
    val url = parseUrl(text) ?: throw InvalidLinkException(
      "That doesn't look like a web address. It should look like https://example.com/audio/.",
    )
    when (val page = ProfilePages.classify(url)) {
      is ProfilePages.Page.Profile -> return LinkTarget.Folder(WebRef.idOf(page.url))
      is ProfilePages.Page.Upload ->
        return LinkTarget.File(WebRef.idOf(page.url), ProfilePages.fileName(readUpload(page).title))
      null -> {}
    }
    if (url.scheme != "https") throw InvalidLinkException(
      "AudioFlix only opens addresses that start with https://, so what you listen to can't be " +
        "read or changed on the way.",
    )
    val name = url.pathSegments.lastOrNull { it.isNotEmpty() }
    val isFile = name != null && !url.encodedPath.endsWith("/") &&
      (MediaInstaller.isAudioName(name) || MediaInstaller.isArchiveName(name))
    // Folders are often linked without the trailing slash; the server sends us on to the listing.
    return if (isFile) LinkTarget.File(WebRef.idOf(url), name) else LinkTarget.Folder(WebRef.idOf(IndexParser.folderUrl(url)))
  }

  override fun folderItem(folderId: String, title: String) =
    CatalogItem(ID, WebRef.Folder(folderId).remoteId, title, kind = null)

  override fun fileItem(fileId: String, name: String) =
    CatalogItem(ID, WebRef.File(fileId).remoteId, name.substringBeforeLast('.').ifBlank { name }, kind = null)

  override fun folderIdOf(remoteId: String): String? = (WebRef.parse(remoteId) as? WebRef.Folder)?.id

  /**
   * Audio files and zips of a folder title, subfolders included, with paths that mirror the folder
   * layout. A file title is just that file.
   */
  override suspend fun files(remoteId: String): List<RemoteFile> {
    val ref = WebRef.parse(remoteId) ?: throw IllegalArgumentException("Not a website folder title: $remoteId")
    return when (val page = WebRef.urlOf(ref.id)?.let(ProfilePages::classify)) {
      is ProfilePages.Page.Profile -> profileFiles(page)
      is ProfilePages.Page.Upload -> {
        val audio = readUpload(page)
        listOf(RemoteFile(WebRef.idOf(audio.mediaUrl), MediaInstaller.sanitizeFileName(ProfilePages.fileName(audio.title))))
      }
      null -> when (ref) {
        is WebRef.Folder -> collectFolderFiles(ref.id, maxDepth = MAX_DEPTH, maxFiles = MAX_FILES)
        is WebRef.File -> listOf(RemoteFile(ref.id, MediaInstaller.sanitizeFileName(fileName(fileUrl(ref.id)))))
      }
    }
  }

  /**
   * Every upload of a profile, by its audio's address, which means reading each upload's page. An
   * upload whose page has gone is left out; if they all fail, the first failure is reported.
   */
  private suspend fun profileFiles(profile: ProfilePages.Page.Profile): List<RemoteFile> = coroutineScope {
    val uploads = listFolder(WebRef.idOf(profile.url)).entries
      .filter { it.type == RemoteEntry.Type.FILE }
      .take(MAX_FILES)
    val permits = Semaphore(PAGE_READS)
    val results = uploads.map { entry ->
      async {
        permits.withPermit {
          runCatching {
            val page = WebRef.urlOf(entry.id)?.let(ProfilePages::classify) as? ProfilePages.Page.Upload
              ?: throw IllegalArgumentException("Not an upload page")
            entry to readUpload(page).mediaUrl
          }
        }
      }
    }.awaitAll()
    results.firstOrNull { it.exceptionOrNull() is CancellationException }?.exceptionOrNull()?.let { throw it }
    val found = results.mapNotNull { it.getOrNull() }
    if (found.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    val used = HashSet<String>()
    found.map { (entry, media) ->
      RemoteFile(WebRef.idOf(media), uniquePath(MediaInstaller.sanitizeFileName(entry.name), used))
    }
  }

  override fun streamUrl(fileId: String): String? = WebRef.urlOf(fileId)?.toString()

  /** From a HEAD request, so downloads of a folder can show progress for the whole title. */
  override suspend fun fileSize(fileId: String): Long = withContext(Dispatchers.IO) {
    val url = WebRef.urlOf(fileId) ?: return@withContext -1L
    runCatching {
      client.newCall(Request.Builder().url(url).head().build()).await().use { response ->
        if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
      }
    }.getOrDefault(-1L)
  }

  override suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream {
    val url = fileUrl(remoteId)
    val request = Request.Builder()
      .url(url)
      .header("Accept", "*/*")
      .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
      .build()
    val response = client.newCall(request).await()
    try {
      when {
        response.code == 401 || response.code == 403 -> throw FolderUnavailableException(
          "${url.host} doesn't let AudioFlix download this file.",
        )
        response.code == 404 || response.code == 410 ->
          throw FolderUnavailableException("This file isn't on ${url.host} any more.")
        !response.isSuccessful -> throw IOException("${url.host} returned HTTP ${response.code}")
      }
      val body = response.body ?: throw IOException("Empty response")
      val partial = response.code == 206
      val contentRange = response.header("Content-Range")
      return DownloadStream(
        input = body.byteStream(),
        offset = if (partial) contentRangeStart(contentRange) ?: 0L else 0L,
        totalBytes = if (partial) contentRangeTotal(contentRange) ?: -1L else body.contentLength(),
        fileName = contentDispositionFileName(response.header("Content-Disposition")) ?: fileName(url),
        contentType = response.header("Content-Type"),
        onClose = { response.close() },
      )
    } catch (t: Throwable) {
      response.close()
      throw t
    }
  }

  private fun folderUrl(folderId: String): HttpUrl =
    WebRef.urlOf(folderId)?.let(IndexParser::folderUrl)
      ?: throw IllegalArgumentException("Not a website address: $folderId")

  /** The address of a file, whether its id came from a listing or from a library title. */
  private fun fileUrl(fileId: String): HttpUrl =
    WebRef.urlOf(fileId)
      ?: WebRef.parse(fileId)?.let { WebRef.urlOf(it.id) }
      ?: throw IllegalArgumentException("Not a website address: $fileId")

  private fun fileName(url: HttpUrl): String = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: "audio"

  private fun parseUrl(text: String): HttpUrl? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.toHttpUrlOrNull() ?: "https://$trimmed".toHttpUrlOrNull()
  }

  companion object {
    const val ID = "web"
    private const val KEY_SAVED = "web.savedFolders"
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 2_000

    /** How many upload pages of a profile are read at once. */
    private const val PAGE_READS = 4
  }
}

/**
 * How a website folder or file is named: its address, encoded so it can also be a directory name on
 * the phone. A library title's remote id adds "dir-" or "url-", so a whole folder and a single file
 * are told apart.
 */
sealed interface WebRef {
  val id: String
  val remoteId: String

  data class Folder(override val id: String) : WebRef {
    override val remoteId: String get() = "dir-$id"
  }

  data class File(override val id: String) : WebRef {
    override val remoteId: String get() = "url-$id"
  }

  companion object {
    fun idOf(url: HttpUrl): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(url.toString().toByteArray(Charsets.UTF_8))

    fun urlOf(id: String): HttpUrl? = runCatching {
      String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8).toHttpUrlOrNull()
    }.getOrNull()

    fun parse(remoteId: String): WebRef? = when {
      remoteId.startsWith("dir-") -> Folder(remoteId.removePrefix("dir-"))
      remoteId.startsWith("url-") -> File(remoteId.removePrefix("url-"))
      else -> null
    }
  }
}
