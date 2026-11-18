package app.audioflix.player

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.audioflix.MainActivity
import app.audioflix.container
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background playback with a media notification. It also owns listening progress: positions are
 * written to the library while playing, on pause and on track changes, so progress is kept even
 * when the UI is gone.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
  private var session: MediaSession? = null
  private lateinit var player: ExoPlayer
  private val scope = MainScope()
  private var saveLoop: Job? = null
  private val writeLock = Mutex()

  /** Where the current track was last seen, so it can be saved after the player moved on. */
  private var lastMediaId: String? = null
  private var lastPositionMs = 0L
  private var lastDurationMs = 0L

  /**
   * When playback last paused (by the listener, a call, headphones coming out), on the
   * [SystemClock.elapsedRealtime] clock; null while playing, and after any seek or track change,
   * which are deliberate moves that auto-rewind shouldn't undo.
   */
  private var pausedAt: Long? = null

  override fun onCreate() {
    super.onCreate()
    player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
      .setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        /* handleAudioFocus = */ true,
      )
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .setSeekBackIncrementMs(SEEK_BACK_MS)
      .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
      .build()
    player.addListener(progressListener)

    val openApp = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    session = MediaSession.Builder(this, player)
      .setSessionActivity(openApp)
      .setCallback(sessionCallback)
      .build()

    saveLoop = scope.launch {
      var ticks = 0
      while (isActive) {
        delay(1_000)
        snapshot()
        if (player.isPlaying && ++ticks % SAVE_EVERY_SECONDS == 0) save()
      }
    }
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  /**
   * Local files are read directly; streams go through a disk cache. Chapters play as separate
   * slices of one file, and each slice reads the file's index (often megabytes, at the end of an
   * audiobook), so without the cache every chapter would download it again.
   */
  private fun dataSourceFactory(): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    val cached = CacheDataSource.Factory()
      .setCache(container.streamCache)
      .setUpstreamDataSourceFactory(http)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return DefaultDataSource.Factory(this, cached)
  }

  override fun onDestroy() {
    snapshot()
    save()
    saveLoop?.cancel()
    session?.run {
      player.release()
      release()
    }
    session = null
    scope.cancel()
    super.onDestroy()
  }

  private val sessionCallback = object : MediaSession.Callback {
    // Controllers can't pass playable URIs directly, so the file URI travels in request metadata.
    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
      mediaItems.map { item ->
        item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item
      }.toMutableList(),
    )
  }

  private val progressListener = object : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      val previous = lastMediaId
      if (previous != null && previous != mediaItem?.mediaId) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
          complete(previous, isLast = false)
        } else {
          save()
        }
      }
      lastMediaId = mediaItem?.mediaId
      pausedAt = null
      lastPositionMs = player.currentPosition
      lastDurationMs = player.duration.coerceAtLeast(0)
      save()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (isPlaying) {
        rewindAfterPause()
      } else if (player.playbackState == Player.STATE_READY) {
        // Paused, or held back by a call; buffering and the end of the title aren't pauses.
        pausedAt = SystemClock.elapsedRealtime()
      }
      snapshot()
      save()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_ENDED) {
        lastMediaId?.let { complete(it, isLast = !player.hasNextMediaItem()) }
      }
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        pausedAt = null
        snapshot()
        save()
      }
    }
  }

  /** Goes back a little on resuming, per the auto-rewind setting, never past the track's start. */
  private fun rewindAfterPause() {
    val since = pausedAt ?: return
    pausedAt = null
    val back = container.settings.autoRewind.value.rewindMs(SystemClock.elapsedRealtime() - since)
    if (back <= 0) return
    val position = player.currentPosition
    if (position <= 0) return
    player.seekTo((position - back).coerceAtLeast(0))
  }

  private fun snapshot() {
    val current = player.currentMediaItem ?: return
    if (current.mediaId != lastMediaId) return
    lastPositionMs = player.currentPosition
    player.duration.takeIf { it > 0 }?.let { lastDurationMs = it }
  }

  private fun save() {
    val (key, index) = MediaIds.parse(lastMediaId) ?: return
    val position = lastPositionMs
    val duration = lastDurationMs
    write { container.library.savePosition(key, index, position, duration) }
  }

  private fun complete(mediaId: String, isLast: Boolean) {
    val (key, index) = MediaIds.parse(mediaId) ?: return
    write { container.library.completeTrack(key, index, isLast) }
  }

  // Writes run on the app scope so they outlive the service, and in call order so a late position
  // save can't undo a completion.
  private fun write(block: suspend () -> Unit) {
    container.appScope.launch(start = CoroutineStart.UNDISPATCHED) { writeLock.withLock { block() } }
  }

  companion object {
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L
    private const val SAVE_EVERY_SECONDS = 5
  }
}

/** Media ids are "<library key>#<track index>". */
object MediaIds {
  fun of(itemKey: String, index: Int) = "$itemKey#$index"

  fun parse(mediaId: String?): Pair<String, Int>? {
    if (mediaId == null) return null
    val hash = mediaId.lastIndexOf('#')
    if (hash <= 0) return null
    val index = mediaId.substring(hash + 1).toIntOrNull() ?: return null
    return mediaId.substring(0, hash) to index
  }
}
