package app.audioflix.provider.gdrive

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Drive said no in a way retrying won't fix soon: not shared, removed, or download-limited. */
class DriveUnavailableException(message: String) : FolderUnavailableException(message)

data class DriveFileInfo(val name: String?, val sizeBytes: Long)

/**
 * Shared Google Drive folders and files, read without an account: whatever the owner shared as
 * "Anyone with the link". A library title is either a whole folder (with its subfolders) or a
 * single file, and its audio can be streamed straight from Drive or downloaded.
 */
class GoogleDriveProvider(
  private val client: OkHttpClient,
  prefs: SharedPreferences,
) : FolderProvider {
  override val id = ID
  override val displayName = "Google Drive"
  override val supportedKinds = emptyList<MediaKind>()
  override val requiresLogin = false
  override val account: StateFlow<String?> = MutableStateFlow(null)

  override val linkLabel = "Google Drive link"
  override val linkHelp =
    "Paste a link to a Google Drive folder or file that's shared as \"Anyone with the link\". " +
      "No Google account needed."

  override val supportsFastDownloads = true

  override val fastDownloadsNote =
    "Downloads from Google Drive use several connections at once, a piece of the file on each, " +
      "which is usually several times faster. If Drive starts refusing a file, turn this off and try again."

  /** Where the file itself is served, past the virus-scan warning Drive shows for big files. */
  override suspend fun rangedDownloadUrl(fileId: String): String? = open(fileId, range = "bytes=0-0").use { it.url }

  private val saved = SavedFolderStore(prefs, KEY_SAVED)
  override val savedFolders: StateFlow<List<SavedFolder>> = saved.folders

  override fun saveFolder(folder: SavedFolder) = saved.save(folder)

  override fun removeFolder(folderId: String) = saved.remove(folderId)

  override suspend fun listFolder(folderId: String): RemoteFolder = withContext(Dispatchers.IO) {
    val url = "$DRIVE/embeddedfolderview".toHttpUrl().newBuilder().addQueryParameter("id", folderId).build()
    client.newCall(Request.Builder().url(url).build()).await().use { response ->
      // Folders that aren't shared publicly answer 404, or send us to the sign-in page.
      if (response.code == 404 || response.code == 403 || response.request.url.host != DRIVE_HOST) {
        throw DriveUnavailableException(NOT_SHARED_FOLDER)
      }
      if (!response.isSuccessful) throw IOException("Google Drive returned HTTP ${response.code}")
      DriveParser.parseFolder(response.body?.string().orEmpty(), folderId)
    }
  }

  /** Works out whether a link is a folder or a file, asking Drive when the link doesn't say. */
  override suspend fun resolveLink(text: String): LinkTarget {
    val link = DriveParser.parseLink(text)
      ?: throw InvalidLinkException("That doesn't look like a Google Drive link.")
    return when (link.type) {
      DriveLink.Type.FOLDER -> LinkTarget.Folder(link.id)
      DriveLink.Type.FILE -> fileTarget(link.id)
      DriveLink.Type.UNKNOWN -> {
        val isFolder = try {
          listFolder(link.id)
          true
        } catch (e: DriveUnavailableException) {
          false
        }
        if (isFolder) LinkTarget.Folder(link.id) else fileTarget(link.id)
      }
    }
  }

  private suspend fun fileTarget(fileId: String) =
    LinkTarget.File(fileId, fileInfo(fileId).name ?: "Shared file")

  override fun folderItem(folderId: String, title: String) =
    CatalogItem(ID, DriveRef.Folder(folderId).remoteId, title, kind = null)

  override fun fileItem(fileId: String, name: String) =
    CatalogItem(ID, DriveRef.File(fileId).remoteId, name.substringBeforeLast('.').ifBlank { name }, kind = null)

  override fun folderIdOf(remoteId: String): String? = (DriveRef.parse(remoteId) as? DriveRef.Folder)?.id

  /** The name and size of a shared file, from the first byte of its download. */
  suspend fun fileInfo(fileId: String): DriveFileInfo =
    open(fileId, range = "bytes=0-0").use { DriveFileInfo(it.fileName, it.totalBytes) }

  override suspend fun fileSize(fileId: String): Long = runCatching { fileInfo(fileId).sizeBytes }.getOrDefault(-1)

  /**
   * Audio files and zips of a folder title, subfolders included, with paths that mirror the folder
   * layout. A file title is just that file.
   */
  override suspend fun files(remoteId: String): List<RemoteFile> = when (val ref = DriveRef.parse(remoteId)) {
    is DriveRef.Folder -> collectFolderFiles(ref.id, maxDepth = MAX_DEPTH, maxFiles = MAX_FILES)
    is DriveRef.File -> {
      val name = fileInfo(ref.id).name ?: "audio"
      listOf(RemoteFile(ref.id, MediaInstaller.sanitizeFileName(name)))
    }
    null -> throw IllegalArgumentException("Not a Google Drive title: $remoteId")
  }

  override fun streamUrl(fileId: String): String = downloadUrl(fileId).toString()

  override suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream =
    open(remoteId, range = if (resumeFrom > 0) "bytes=$resumeFrom-" else null)

  private suspend fun open(fileId: String, range: String?): DownloadStream {
    var url = downloadUrl(fileId)
    // At most one hop: from the virus-scan warning to the file itself.
    for (attempt in 0..1) {
      val request = Request.Builder()
        .url(url)
        .header("Accept", "*/*")
        .apply { if (range != null) header("Range", range) }
        .build()
      val response = client.newCall(request).await()
      val contentType = response.header("Content-Type")
      if (contentType?.startsWith("text/html") == true || response.request.url.host != USERCONTENT_HOST) {
        val html = response.use { it.body?.string().orEmpty() }
        val next = DriveParser.parseDownloadForm(html)?.toHttpUrl()
        if (next != null && attempt == 0) {
          url = next
          continue
        }
        if (response.request.url.host != USERCONTENT_HOST) throw DriveUnavailableException(DriveParser.NOT_SHARED)
        throw DriveUnavailableException(DriveParser.describeErrorPage(html))
      }
      return toDownloadStream(response, contentType)
    }
    throw IOException("Google Drive kept asking for confirmation")
  }

  private fun toDownloadStream(response: Response, contentType: String?): DownloadStream {
    try {
      when {
        // Drive answers ids that don't exist (or were removed) with a server error.
        response.code == 404 || response.code == 403 || response.code == 500 ->
          throw DriveUnavailableException("This file is no longer shared, or was removed from Google Drive.")
        !response.isSuccessful -> throw IOException("Google Drive returned HTTP ${response.code}")
      }
      val body = response.body ?: throw IOException("Empty response")
      val partial = response.code == 206
      val contentRange = response.header("Content-Range")
      return DownloadStream(
        input = body.byteStream(),
        offset = if (partial) contentRangeStart(contentRange) ?: 0L else 0L,
        totalBytes = if (partial) contentRangeTotal(contentRange) ?: -1L else body.contentLength(),
        fileName = contentDispositionFileName(response.header("Content-Disposition")),
        contentType = contentType,
        onClose = { response.close() },
        url = response.request.url.toString(),
      )
    } catch (t: Throwable) {
      response.close()
      throw t
    }
  }

  companion object {
    const val ID = "gdrive"
    private const val DRIVE_HOST = "drive.google.com"
    private const val DRIVE = "https://$DRIVE_HOST"
    private const val USERCONTENT_HOST = "drive.usercontent.google.com"
    private const val KEY_SAVED = "gdrive.savedFolders"
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 2_000

    const val NOT_SHARED_FOLDER =
      "Couldn't open this folder. Check the link, and that it's shared as \"Anyone with the link\"."

    /** `confirm=t` skips the virus-scan warning Drive shows for files over about 100 MB. */
    fun downloadUrl(fileId: String): HttpUrl = "https://$USERCONTENT_HOST/download".toHttpUrl().newBuilder()
      .addQueryParameter("id", fileId)
      .addQueryParameter("export", "download")
      .addQueryParameter("confirm", "t")
      .build()
  }
}

/** How a Drive title is stored as a library remote id: "folder-<id>" or "file-<id>". */
sealed interface DriveRef {
  val id: String
  val remoteId: String

  data class Folder(override val id: String) : DriveRef {
    override val remoteId: String get() = "folder-$id"
  }

  data class File(override val id: String) : DriveRef {
    override val remoteId: String get() = "file-$id"
  }

  companion object {
    fun parse(remoteId: String): DriveRef? = when {
      remoteId.startsWith("folder-") -> Folder(remoteId.removePrefix("folder-"))
      remoteId.startsWith("file-") -> File(remoteId.removePrefix("file-"))
      else -> null
    }
  }
}
