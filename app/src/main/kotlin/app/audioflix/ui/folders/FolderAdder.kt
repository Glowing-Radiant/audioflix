package app.audioflix.ui.folders

import app.audioflix.AppContainer
import app.audioflix.data.StreamResult
import app.audioflix.provider.CatalogItem
import app.audioflix.provider.FolderProvider
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * What happened when adding a title, for the snackbar; [openKey] offers to open it. [interim] marks
 * a "working on it" message that the result replaces.
 */
data class AddOutcome(val message: String, val openKey: String? = null, val interim: Boolean = false)

/** Messages about titles being added, shown by the app wherever the user has gone meanwhile. */
class AddNotices {
  private val channel = Channel<AddOutcome>(Channel.BUFFERED)
  val flow: Flow<AddOutcome> = channel.receiveAsFlow()

  fun post(outcome: AddOutcome) {
    channel.trySend(outcome)
  }
}

/**
 * Adds folder-provider titles to the library, for streaming or downloading. The work runs on the
 * app's scope, not the screen's: reading a big folder can take a while, and leaving the screen (or
 * switching to the Library tab) must not cut it short.
 */
class FolderAdder(private val container: AppContainer, private val provider: FolderProvider) {
  fun stream(item: CatalogItem) {
    container.appScope.launch {
      container.addNotices.post(AddOutcome("Adding ${item.title}. Reading its files…", interim = true))
      container.addNotices.post(streamNow(item))
    }
  }

  fun download(item: CatalogItem) {
    container.appScope.launch { container.addNotices.post(downloadNow(item)) }
  }

  private suspend fun streamNow(item: CatalogItem): AddOutcome = try {
    when (val result = container.library.addForStreaming(item, provider)) {
      is StreamResult.Added -> {
        val tracks = if (result.trackCount == 1) "1 track" else "${result.trackCount} tracks"
        val skipped = when (result.skippedArchives) {
          0 -> ""
          1 -> " 1 zip file was left out; download the title to get it."
          else -> " ${result.skippedArchives} zip files were left out; download the title to get them."
        }
        val verb = if (provider.isLocal) "Added ${item.title} to play from its folder" else "Added ${item.title} for streaming"
        AddOutcome("$verb, $tracks.$skipped", item.key)
      }
      StreamResult.AlreadyInLibrary -> AddOutcome("${item.title} is already in your library.", item.key)
      is StreamResult.NothingToStream -> AddOutcome(
        if (result.archives > 0) "This only has zip files, which can't be played from here. ${provider.copyVerb} it instead."
        else "There are no audio files here.",
      )
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    AddOutcome(errorMessage(e))
  }

  private suspend fun downloadNow(item: CatalogItem): AddOutcome = try {
    container.library.download(item, provider.dailyDownloadLimit)
    AddOutcome(if (provider.isLocal) "Copying ${item.title} into AudioFlix" else "Downloading ${item.title}", item.key)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    AddOutcome(errorMessage(e))
  }
}

fun errorMessage(e: Exception): String = when (e) {
  is UnknownHostException -> "No internet connection."
  else -> e.message ?: "Something went wrong."
}
