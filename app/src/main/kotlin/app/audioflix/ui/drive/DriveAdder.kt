package app.audioflix.ui.drive

import app.audioflix.AppContainer
import app.audioflix.data.StreamResult
import app.audioflix.provider.CatalogItem
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

/** What happened when adding a Drive title, for the snackbar; [openKey] offers to open it. */
data class AddOutcome(val message: String, val openKey: String? = null)

/** Adds Drive titles to the library, for streaming or downloading. */
class DriveAdder(private val container: AppContainer) {
  suspend fun stream(item: CatalogItem): AddOutcome = try {
    when (val result = container.library.addForStreaming(item, container.drive)) {
      is StreamResult.Added -> {
        val tracks = if (result.trackCount == 1) "1 track" else "${result.trackCount} tracks"
        val skipped = when (result.skippedArchives) {
          0 -> ""
          1 -> " 1 zip file was left out; download the title to get it."
          else -> " ${result.skippedArchives} zip files were left out; download the title to get them."
        }
        AddOutcome("Added ${item.title} for streaming, $tracks.$skipped", item.key)
      }
      StreamResult.AlreadyInLibrary -> AddOutcome("${item.title} is already in your library.", item.key)
      is StreamResult.NothingToStream -> AddOutcome(
        if (result.archives > 0) "This only has zip files, which can't be streamed. Download it instead."
        else "There are no audio files here.",
      )
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    AddOutcome(errorMessage(e))
  }

  suspend fun download(item: CatalogItem): AddOutcome = try {
    container.library.download(item, container.drive.dailyDownloadLimit)
    AddOutcome("Downloading ${item.title}", item.key)
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
