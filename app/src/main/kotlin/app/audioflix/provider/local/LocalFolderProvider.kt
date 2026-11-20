package app.audioflix.provider.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.audioflix.download.MediaInstaller
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
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Folders on the phone (or an SD card, or anything the system file picker offers), chosen with the
 * system folder picker. AudioFlix keeps read access to a chosen folder, and its audio can be played
 * where it is or copied into AudioFlix's own library.
 *
 * Ids are document addresses (content:// URIs) from the picker, encoded so they can also be
 * directory names.
 */
class LocalFolderProvider(
  private val context: Context,
  prefs: SharedPreferences,
) : FolderProvider {
  override val id = ID
  override val displayName = "Phone folders"
  override val supportedKinds = emptyList<MediaKind>()
  override val requiresLogin = false
  override val account: StateFlow<String?> = MutableStateFlow(null)
  override val isLocal = true
  override val needsNetwork = false

  override val linkLabel = "Folder"
  override val linkHelp =
    "Choose a folder on your phone, SD card or USB drive that has audio in it. AudioFlix can play it " +
      "where it is, or copy it into its own library."
  override val savedHeading = "Folders you chose"

  private val resolver: ContentResolver get() = context.contentResolver

  private val saved = SavedFolderStore(prefs, KEY_SAVED)
  override val savedFolders: StateFlow<List<SavedFolder>> = saved.folders

  override fun saveFolder(folder: SavedFolder) = saved.save(folder)

  override fun removeFolder(folderId: String) = saved.remove(folderId)

  /**
   * Keeps read access to a folder the picker returned, across restarts, and returns its id.
   * Throws [FolderUnavailableException] when the system won't let AudioFlix keep it.
   */
  fun adoptTree(treeUri: Uri): String {
    try {
      resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
      throw FolderUnavailableException("Android didn't let AudioFlix keep access to that folder. Try choosing it again.")
    }
    val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    return idOf(document)
  }

  override suspend fun listFolder(folderId: String): RemoteFolder = withContext(Dispatchers.IO) {
    val folder = uriOf(folderId) ?: throw IllegalArgumentException("Not a phone folder: $folderId")
    val docId = DocumentsContract.getDocumentId(folder)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, docId)
    val title = query(folder) { it.getString(0) } ?: "Folder"
    val entries = mutableListOf<RemoteEntry>()
    try {
      resolver.query(children, COLUMNS, null, null, null)?.use { c ->
        while (c.moveToNext()) {
          val childId = c.getString(0) ?: continue
          val name = c.getString(1) ?: continue
          val mime = c.getString(2).orEmpty()
          val uri = DocumentsContract.buildDocumentUriUsingTree(folder, childId)
          val type = when {
            mime == Document.MIME_TYPE_DIR -> RemoteEntry.Type.FOLDER
            MediaInstaller.isAudioName(name) || MediaInstaller.isArchiveName(name) || mime.startsWith("audio/") ->
              RemoteEntry.Type.FILE
            else -> RemoteEntry.Type.OTHER
          }
          entries += RemoteEntry(idOf(uri), name, type)
        }
      } ?: throw FolderUnavailableException(GONE)
    } catch (e: SecurityException) {
      throw FolderUnavailableException(NO_ACCESS)
    } catch (e: IllegalArgumentException) {
      // Thrown by some providers when the folder was deleted.
      throw FolderUnavailableException(GONE)
    }
    RemoteFolder(folderId, title, entries)
  }

  override suspend fun resolveLink(text: String): LinkTarget =
    throw InvalidLinkException("Choose a folder with the Choose a folder button.")

  override fun folderIdOf(remoteId: String): String? = remoteId.takeIf { it.startsWith(DIR) }?.removePrefix(DIR)

  override fun folderItem(folderId: String, title: String) = CatalogItem(ID, DIR + folderId, title, kind = null)

  override fun fileItem(fileId: String, name: String, description: String?) =
    CatalogItem(ID, FILE + fileId, name.substringBeforeLast('.').ifBlank { name }, kind = null)

  override suspend fun files(remoteId: String): List<RemoteFile> = when {
    remoteId.startsWith(DIR) -> collectFolderFiles(remoteId.removePrefix(DIR), maxDepth = MAX_DEPTH, maxFiles = MAX_FILES)
    remoteId.startsWith(FILE) -> {
      val fileId = remoteId.removePrefix(FILE)
      val name = withContext(Dispatchers.IO) { uriOf(fileId)?.let { uri -> query(uri) { it.getString(0) } } } ?: "audio"
      listOf(RemoteFile(fileId, MediaInstaller.sanitizeFileName(name)))
    }
    else -> throw IllegalArgumentException("Not a phone folder title: $remoteId")
  }

  /** The player reads content:// addresses directly. */
  override fun streamUrl(fileId: String): String? = uriOf(fileId)?.toString()

  override suspend fun fileSize(fileId: String): Long = withContext(Dispatchers.IO) {
    uriOf(fileId)?.let { uri -> runCatching { query(uri) { if (it.isNull(1)) -1L else it.getLong(1) } }.getOrNull() } ?: -1L
  }

  /** Copying into AudioFlix: the file is read from where it is. */
  override suspend fun openDownload(remoteId: String, resumeFrom: Long): DownloadStream = withContext(Dispatchers.IO) {
    val fileId = remoteId.removePrefix(FILE)
    val uri = uriOf(fileId) ?: throw IllegalArgumentException("Not a phone file: $remoteId")
    val size = fileSize(fileId)
    val name = runCatching { query(uri) { it.getString(0) } }.getOrNull()
    val input = try {
      resolver.openInputStream(uri) ?: throw FolderUnavailableException(GONE)
    } catch (e: FileNotFoundException) {
      throw FolderUnavailableException("This file isn't on the phone any more.")
    } catch (e: SecurityException) {
      throw FolderUnavailableException(NO_ACCESS)
    }
    var offset = 0L
    if (resumeFrom > 0) {
      try {
        while (offset < resumeFrom) {
          val n = input.skip(resumeFrom - offset)
          if (n <= 0) break
          offset += n
        }
      } catch (e: IOException) {
        input.close()
        throw e
      }
    }
    DownloadStream(input, offset, size, name, resolver.getType(uri), onClose = {})
  }

  /** Display name and size of a document, through [read], or null when it can't be read. */
  private fun <T> query(uri: Uri, read: (android.database.Cursor) -> T): T? = try {
    resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst()) read(c) else null
    }
  } catch (e: SecurityException) {
    null
  } catch (e: IllegalArgumentException) {
    null
  }

  companion object {
    const val ID = "local"
    private const val KEY_SAVED = "local.savedFolders"
    private const val DIR = "dir-"
    private const val FILE = "file-"
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 2_000
    private const val GONE = "This folder isn't there any more. It may have been moved, deleted, or be on a card that's been taken out."
    private const val NO_ACCESS = "AudioFlix no longer has access to this folder. Choose it again in Browse."

    private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)

    fun idOf(uri: Uri): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(uri.toString().toByteArray(Charsets.UTF_8))

    fun uriOf(id: String): Uri? = runCatching {
      Uri.parse(String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8)).takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    }.getOrNull()
  }
}
