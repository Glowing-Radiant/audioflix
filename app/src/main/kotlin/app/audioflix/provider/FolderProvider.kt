package app.audioflix.provider

import android.content.SharedPreferences
import app.audioflix.download.MediaInstaller
import app.audioflix.download.NaturalOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One entry of a remote folder listing. */
data class RemoteEntry(val id: String, val name: String, val type: Type) {
  enum class Type {
    FOLDER,
    FILE,

    /** Nothing that can be added as audio: a document, an image, a web page. */
    OTHER,
  }
}

/** A folder listing: [id] is the provider's id for the folder itself. */
data class RemoteFolder(val id: String, val title: String, val entries: List<RemoteEntry>)

/** A folder the user opened, kept so they can come back to it. */
@Serializable
data class SavedFolder(val id: String, val title: String)

/** What a link the user pasted points at. */
sealed interface LinkTarget {
  data class Folder(val id: String) : LinkTarget
  data class File(val id: String, val name: String) : LinkTarget
}

/** Thrown when pasted text isn't a link this provider can open. */
class InvalidLinkException(message: String) : Exception(message)

/** Thrown when a folder or file is there but can't be read, in a way retrying won't fix soon. */
open class FolderUnavailableException(message: String) : Exception(message)

/**
 * A provider the user browses by opening a link rather than by searching a catalog: shared Google
 * Drive folders, a folder listing on a website. Folders are walked, and either a whole folder (with
 * its subfolders) or a single file becomes a library title, to stream or to download.
 */
interface FolderProvider : CatalogProvider {
  /** Label of the link box in Browse, e.g. "Google Drive link". */
  val linkLabel: String

  /** What the link box accepts, said to the listener above it. */
  val linkHelp: String

  /** Heading above the folders the user opened before. */
  val savedHeading: String get() = "Your folders"

  /** The folders the user opened before, newest first. */
  val savedFolders: StateFlow<List<SavedFolder>>

  /** Remembers [folder] at the top of [savedFolders]. */
  fun saveFolder(folder: SavedFolder)

  fun removeFolder(folderId: String)

  /** Lists one folder. Throws [FolderUnavailableException] when it can't be read. */
  suspend fun listFolder(folderId: String): RemoteFolder

  /**
   * Works out whether pasted text points at a folder or a single file, asking the server when the
   * link alone doesn't say. Throws [InvalidLinkException] when it isn't a link for this provider.
   */
  suspend fun resolveLink(text: String): LinkTarget

  /** The library title a whole folder becomes. */
  fun folderItem(folderId: String, title: String): CatalogItem

  /** The library title a single file becomes. */
  fun fileItem(fileId: String, name: String): CatalogItem
}

/** The saved-folder list of one provider, kept in [prefs] under [key]. */
class SavedFolderStore(private val prefs: SharedPreferences, private val key: String) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(SavedFolder.serializer())

  private val _folders = MutableStateFlow(load())
  val folders: StateFlow<List<SavedFolder>> = _folders.asStateFlow()

  fun save(folder: SavedFolder) {
    _folders.update { list -> listOf(folder) + list.filter { it.id != folder.id } }
    persist()
  }

  fun remove(folderId: String) {
    _folders.update { list -> list.filter { it.id != folderId } }
    persist()
  }

  private fun load(): List<SavedFolder> =
    prefs.getString(key, null)
      ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
      .orEmpty()

  private fun persist() {
    prefs.edit().putString(key, json.encodeToString(serializer, _folders.value)).apply()
  }
}

/**
 * The audio files and zips under [rootId], subfolders included, with paths that mirror the folder
 * layout. Stops at [maxDepth] levels and [maxFiles] files, so a huge or looping tree can't run away.
 */
suspend fun FolderProvider.collectFolderFiles(
  rootId: String,
  maxDepth: Int = 6,
  maxFiles: Int = 2_000,
): List<RemoteFile> {
  val files = mutableListOf<RemoteFile>()
  val usedPaths = HashSet<String>()
  val visited = hashSetOf(rootId)

  suspend fun walk(folderId: String, prefix: String, depth: Int) {
    val folder = listFolder(folderId)
    for (entry in folder.entries.sortedWith(compareBy(NaturalOrder) { it.name })) {
      if (files.size >= maxFiles) return
      when (entry.type) {
        RemoteEntry.Type.FOLDER -> if (depth < maxDepth && visited.add(entry.id)) {
          walk(entry.id, prefix + MediaInstaller.sanitizeFileName(entry.name) + "/", depth + 1)
        }
        RemoteEntry.Type.FILE ->
          if (MediaInstaller.isAudioName(entry.name) || MediaInstaller.isArchiveName(entry.name)) {
            files += RemoteFile(entry.id, uniquePath(prefix + MediaInstaller.sanitizeFileName(entry.name), usedPaths))
          }
        RemoteEntry.Type.OTHER -> {}
      }
    }
  }

  walk(rootId, "", 0)
  return files
}

/** [path], or "name (2).ext" and so on when a folder holds two files with the same name. */
fun uniquePath(path: String, used: MutableSet<String>): String {
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
