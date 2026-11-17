package app.audioflix.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.audioflix.data.LibraryRepository
import app.audioflix.data.Settings
import app.audioflix.download.MediaInstaller
import app.audioflix.provider.ProviderRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlayerUiState(
  val itemKey: String? = null,
  val itemTitle: String = "",
  val trackTitle: String = "",
  val trackIndex: Int = 0,
  val trackCount: Int = 0,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val speed: Float = 1f,
  /** Why playback stopped, when it failed (e.g. a stream that couldn't be loaded). */
  val error: String? = null,
  /** The file a chapter belongs to, when the title has several files with chapters. */
  val partTitle: String = "",
  /** What a track is called: "chapter", "episode" or "track". */
  val trackNoun: String = "episode",
) {
  val hasMedia: Boolean get() = itemKey != null
}

/** The app's handle on [PlaybackService]: loads library items and exposes playback state. */
class PlayerConnection(
  private val context: Context,
  private val library: LibraryRepository,
  private val providers: ProviderRegistry,
  private val settings: Settings,
  private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow(PlayerUiState(speed = settings.playbackSpeed.value))
  val state: StateFlow<PlayerUiState> = _state.asStateFlow()

  private var controller: MediaController? = null
  private val connectLock = Mutex()

  /** Connects to the service (starting it if needed) so state is available, e.g. for the mini player. */
  fun connect() {
    scope.launch(Dispatchers.Main) { controller() }
  }

  /**
   * Plays [itemKey] from where the user left off, or from [trackIndex] (at that track's saved
   * position) when given.
   */
  fun play(itemKey: String, trackIndex: Int? = null) {
    scope.launch(Dispatchers.Main) {
      var entry = library.get(itemKey) ?: return@launch
      if (trackIndex == null && entry.item.finished) {
        // Listening again from the start.
        library.setFinished(itemKey, false)
        entry = library.get(itemKey) ?: return@launch
      }
      val tracks = entry.sortedTracks
      if (tracks.isEmpty()) return@launch
      val index = (trackIndex ?: entry.item.currentTrackIndex).coerceIn(tracks.indices)
      val track = tracks[index]
      val startMs = when {
        track.completed -> 0L
        track.durationMs > 0 && track.positionMs >= track.durationMs - 2_000 -> 0L
        else -> track.positionMs
      }
      val c = controller()

      if (_state.value.itemKey == itemKey && c.mediaItemCount == tracks.size) {
        c.seekTo(index, startMs)
      } else {
        val dir = File(entry.item.localDir)
        val provider = providers[entry.item.providerId]
        val noun = entry.trackNoun
        val severalFiles = entry.hasSeveralFiles
        val items = tracks.map { t ->
          // Downloaded files play from the phone; the rest stream from the provider.
          val local = File(dir, t.relativePath)
          val uri = local.takeIf { it.exists() }?.toUri()
            ?: t.remoteFileId?.let { provider.streamUrl(it) }?.toUri()
            ?: local.toUri()
          MediaItem.Builder()
            .setMediaId(MediaIds.of(itemKey, t.index))
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .apply {
              // A chapter plays as a slice of its file; positions are then relative to the chapter.
              if (t.isChapter) {
                setClippingConfiguration(
                  MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(t.startMs)
                    .setEndPositionMs(t.endMs ?: C.TIME_END_OF_SOURCE)
                    .build(),
                )
              }
            }
            .setMediaMetadata(
              MediaMetadata.Builder()
                .setTitle(if (tracks.size == 1) entry.item.title else t.title)
                .setArtist(entry.item.title)
                .setAlbumTitle(entry.item.title)
                .setTrackNumber(t.index + 1)
                .setTotalTrackCount(tracks.size)
                .setSubtitle(if (severalFiles && t.isChapter) MediaInstaller.trackTitle(t.relativePath) else null)
                .setExtras(Bundle().apply { putString(EXTRA_TRACK_NOUN, noun) })
                .build(),
            )
            .build()
        }
        c.setMediaItems(items, index, startMs)
        c.prepare()
      }
      c.setPlaybackSpeed(settings.playbackSpeed.value)
      c.play()
    }
  }

  fun togglePlayPause() = withController { if (it.isPlaying) it.pause() else it.play() }

  fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

  fun seekBack() = withController { it.seekBack() }

  fun seekForward() = withController { it.seekForward() }

  fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

  fun previous() = withController {
    // Like most players: restart the track unless we're right at its beginning.
    if (it.currentPosition > 5_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
  }

  fun setSpeed(speed: Float) {
    settings.setPlaybackSpeed(speed)
    withController { it.setPlaybackSpeed(speed) }
  }

  /** Stops and unloads playback if it's currently on [itemKey] (e.g. before deleting it). */
  fun stopIfPlaying(itemKey: String) = withController {
    if (_state.value.itemKey == itemKey) {
      it.stop()
      it.clearMediaItems()
    }
  }

  private fun withController(block: (MediaController) -> Unit) {
    scope.launch(Dispatchers.Main) { block(controller()) }
  }

  private suspend fun controller(): MediaController = connectLock.withLock {
    controller?.let { return it }
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val c = MediaController.Builder(context, token).buildAsync().await()
    c.addListener(object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    })
    controller = c
    refresh(c)
    scope.launch(Dispatchers.Main) {
      while (true) {
        delay(500)
        if (c.isPlaying) refresh(c)
      }
    }
    c
  }

  private fun refresh(p: Player) {
    val ids = MediaIds.parse(p.currentMediaItem?.mediaId)
    val meta = p.mediaMetadata
    _state.value = PlayerUiState(
      itemKey = ids?.first,
      itemTitle = meta.albumTitle?.toString().orEmpty(),
      trackTitle = meta.title?.toString().orEmpty(),
      trackIndex = p.currentMediaItemIndex,
      trackCount = p.mediaItemCount,
      isPlaying = p.isPlaying,
      isBuffering = p.playbackState == Player.STATE_BUFFERING,
      positionMs = p.currentPosition.coerceAtLeast(0),
      durationMs = p.duration.coerceAtLeast(0),
      speed = p.playbackParameters.speed,
      error = p.playerError?.let(::describe),
      partTitle = meta.subtitle?.toString().orEmpty(),
      trackNoun = meta.extras?.getString(EXTRA_TRACK_NOUN) ?: "episode",
    )
  }

  private fun describe(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "Couldn't connect. Check your internet connection and press play to try again."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> "Couldn't play this file. If it streams from Google Drive, Drive may be limiting it for now, " +
      "or it is no longer shared. Try again later, or download it."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The audio file is missing from the phone. Try downloading it again."
    else -> "Couldn't play this file (${error.errorCodeName})."
  }
}

private const val EXTRA_TRACK_NOUN = "audioflix.trackNoun"
