package app.audioflix.summary

import app.audioflix.provider.CatalogItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Wikipedia summaries for catalog titles, cached on disk per item so each title is looked up only
 * once.
 */
class SummaryRepository(private val dir: File, private val wikipedia: WikipediaClient) {
  private val memory = ConcurrentHashMap<String, TitleSummary>()

  suspend fun cached(item: CatalogItem): TitleSummary? = memory[item.key] ?: withContext(Dispatchers.IO) {
    val file = fileFor(item.key)
    if (!file.isFile) return@withContext null
    runCatching { json.decodeFromString<TitleSummary>(file.readText()) }
      .onFailure { file.delete() }
      .getOrNull()
      ?.also { memory[item.key] = it }
  }

  /** Looks the title up on Wikipedia and caches what it finds. Null when no article matches. */
  suspend fun fetch(item: CatalogItem): TitleSummary? {
    val summary = wikipedia.lookup(titleQueryOf(item.title, item.kind)) ?: return null
    memory[item.key] = summary
    withContext(Dispatchers.IO) {
      dir.mkdirs()
      fileFor(item.key).writeText(json.encodeToString(TitleSummary.serializer(), summary))
    }
    return summary
  }

  private fun fileFor(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
