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
import app.audioflix.provider.RemoteFile
import app.audioflix.provider.RemoteFolder
import app.audioflix.provider.SavedFolder
import app.audioflix.provider.SavedFolderStore
import app.audioflix.provider.collectFolderFiles
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
      "https://example.com/audio/. You can also paste the address of a single audio or zip file. " +
      "Nothing to sign in to."

  private val saved = SavedFolderStore(prefs, KEY_SAVED)
  override val savedFolders: StateFlow<List<SavedFolder>> = saved.folders

  override fun saveFolder(folder: SavedFolder) = saved.save(folder)

  override fun removeFolder(folderId: String) = saved.remove(folderId)

  override suspend fun listFolder(folderId: String): RemoteFolder = withContext(Dispatchers.IO) {
    val url = folderUrl(folderId)
    val request = Request.Builder().url(url).header("Accept", "text/html,*/*").build()
    client.newCall(request).await().use { response ->
      when {
        response.code == 401 || response.code == 403 -> throw FolderUnavailableException(
          "${url.host} doesn't let AudioFlix read this folder. It may need a sign-in that AudioFlix can't do.",
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
      // Parsed against the address we ended up at, so a redirect to the real folder still works.
      IndexParser.parse(response.body?.string().orEmpty(), response.request.url)
    }
  }

  /** A folder address, or the address of a single audio file or zip. */
  override suspend fun resolveLink(text: String): LinkTarget {
    val url = parseUrl(text) ?: throw InvalidLinkException(
      "That doesn't look like a web address. It should look like https://example.com/audio/.",
    )
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

  /**
   * Audio files and zips of a folder title, subfolders included, with paths that mirror the folder
   * layout. A file title is just that file.
   */
  override suspend fun files(remoteId: String): List<RemoteFile> = when (val ref = WebRef.parse(remoteId)) {
    is WebRef.Folder -> collectFolderFiles(ref.id, maxDepth = MAX_DEPTH, maxFiles = MAX_FILES)
    is WebRef.File -> listOf(RemoteFile(ref.id, MediaInstaller.sanitizeFileName(fileName(fileUrl(ref.id)))))
    null -> throw IllegalArgumentException("Not a website folder title: $remoteId")
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
