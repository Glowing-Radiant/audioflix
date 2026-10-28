package app.audioflix.player

import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.audioflix.data.LibraryRepository
import app.audioflix.data.Settings
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
) {
  val hasMedia: Boolean get() = itemKey != null
}

/** The app's handle on [PlaybackService]: loads library items and exposes playback state. */
class PlayerConnection(
  private val context: Context,
  private val library: LibraryRepository,
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
        val items = tracks.map { t ->
          val uri = File(dir, t.relativePath).toUri()
          MediaItem.Builder()
            .setMediaId(MediaIds.of(itemKey, t.index))
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
              MediaMetadata.Builder()
                .setTitle(if (tracks.size == 1) entry.item.title else t.title)
                .setArtist(entry.item.title)
                .setAlbumTitle(entry.item.title)
                .setTrackNumber(t.index + 1)
                .setTotalTrackCount(tracks.size)
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
    )
  }
}
