package app.audioflix.provider.gdrive

import android.content.SharedPreferences
import app.audioflix.download.MediaInstaller
import app.audioflix.download.NaturalOrder
import app.audioflix.net.await
import app.audioflix.net.contentDispositionFileName
import app.audioflix.net.contentRangeStart
import app.audioflix.net.contentRangeTotal
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.CatalogProvider
import app.audioflix.provider.DownloadStream
import app.audioflix.provider.MediaKind
import app.audioflix.provider.RemoteFile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Drive said no in a way retrying won't fix soon: not shared, removed, or download-limited. */
class DriveUnavailableException(message: String) : Exception(message)

/** A shared folder the user opened, kept so they can come back to it. */
@Serializable
data class SavedFolder(val id: String, val title: String)

data class DriveFileInfo(val name: String?, val sizeBytes: Long)

/**
 * Shared Google Drive folders and files, read without an account: whatever the owner shared as
 * "Anyone with the link". A library title is either a whole folder (with its subfolders) or a
 * single file, and its audio can be streamed straight from Drive or downloaded.
 */
class GoogleDriveProvider(
  private val client: OkHttpClient,
  private val prefs: SharedPreferences,
) : CatalogProvider {
  override val id = ID
  override val displayName = "Google Drive"
  override val supportedKinds = emptyList<MediaKind>()
  override val requiresLogin = false
  override val account: StateFlow<String?> = MutableStateFlow(null)

  private val json = Json { ignoreUnknownKeys = true }
  private val savedSerializer = ListSerializer(SavedFolder.serializer())

  private val _savedFolders = MutableStateFlow(loadSaved())
  val savedFolders: StateFlow<List<SavedFolder>> = _savedFolders.asStateFlow()

  /** Remembers [folder] at the top of the saved list. */
  fun saveFolder(folder: SavedFolder) {
    _savedFolders.update { list -> listOf(folder) + list.filter { it.id != folder.id } }
    persistSaved()
  }

  fun removeFolder(folderId: String) {
    _savedFolders.update { list -> list.filter { it.id != folderId } }
    persistSaved()
  }

  suspend fun listFolder(folderId: String): DriveFolder = withContext(Dispatchers.IO) {
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

  /** The name and size of a shared file, from the first byte of its download. */
  suspend fun fileInfo(fileId: String): DriveFileInfo =
    open(fileId, range = "bytes=0-0").use { DriveFileInfo(it.fileName, it.totalBytes) }

  override suspend fun fileSize(fileId: String): Long = runCatching { fileInfo(fileId).sizeBytes }.getOrDefault(-1)

  /**
   * Audio files and zips of a folder title, subfolders included, with paths that mirror the folder
   * layout. A file title is just that file.
   */
  override suspend fun files(remoteId: String): List<RemoteFile> = when (val ref = DriveRef.parse(remoteId)) {
    is DriveRef.Folder -> collectFiles(ref.id)
    is DriveRef.File -> {
      val name = fileInfo(ref.id).name ?: "audio"
      listOf(RemoteFile(ref.id, MediaInstaller.sanitizeFileName(name)))
    }
    null -> throw IllegalArgumentException("Not a Google Drive title: $remoteId")
  }

  override fun streamUrl(fileId: String): String = downloadUrl(fileId).toString()

  override suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream =
    open(remoteId, range = if (resumeFrom > 0) "bytes=$resumeFrom-" else null)

  private suspend fun collectFiles(rootId: String): List<RemoteFile> {
    val files = mutableListOf<RemoteFile>()
    val usedPaths = HashSet<String>()
    val visited = hashSetOf(rootId)

    suspend fun walk(folderId: String, prefix: String, depth: Int) {
      val folder = listFolder(folderId)
      for (entry in folder.entries.sortedWith(compareBy(NaturalOrder) { it.name })) {
        if (files.size >= MAX_FILES) return
        when (entry.type) {
          DriveEntry.Type.FOLDER -> if (depth < MAX_DEPTH && visited.add(entry.id)) {
            walk(entry.id, prefix + MediaInstaller.sanitizeFileName(entry.name) + "/", depth + 1)
          }
          DriveEntry.Type.FILE -> if (MediaInstaller.isAudioName(entry.name) || MediaInstaller.isArchiveName(entry.name)) {
            files += RemoteFile(entry.id, uniquePath(prefix + MediaInstaller.sanitizeFileName(entry.name), usedPaths))
          }
          DriveEntry.Type.OTHER -> {}
        }
      }
    }

    walk(rootId, "", 0)
    return files
  }

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
      )
    } catch (t: Throwable) {
      response.close()
      throw t
    }
  }

  private fun loadSaved(): List<SavedFolder> =
    prefs.getString(KEY_SAVED, null)?.let { runCatching { json.decodeFromString(savedSerializer, it) }.getOrNull() }
      .orEmpty()

  private fun persistSaved() {
    prefs.edit().putString(KEY_SAVED, json.encodeToString(savedSerializer, _savedFolders.value)).apply()
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

    fun folderItem(folderId: String, title: String) =
      CatalogItem(ID, DriveRef.Folder(folderId).remoteId, title, kind = null)

    fun fileItem(fileId: String, name: String) =
      CatalogItem(ID, DriveRef.File(fileId).remoteId, name.substringBeforeLast('.').ifBlank { name }, kind = null)
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

/** [path], or "name (2).ext" and so on when a folder holds two files with the same name. */
internal fun uniquePath(path: String, used: MutableSet<String>): String {
  if (used.add(path.lowercase())) return path
  val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
  val file = path.substringAfterLast('/')
  val base = file.substringBeforeLast('.', file)
  val ext = if ('.' in file) "." + file.substringAfterLast('.') else ""
  var n = 2
  while (true) {
    val candidate = "$dir$base ($n)$ext"
    if (used.add(candidate.lowercase())) return candidate
    n++
  }
}
