package app.audioflix

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.audioflix.chapters.ChapterReader
import app.audioflix.data.LibraryDatabase
import app.audioflix.data.LibraryRepository
import app.audioflix.data.Settings
import app.audioflix.download.DownloadQuota
import app.audioflix.net.PersistentCookieJar
import app.audioflix.net.createApiClient
import app.audioflix.net.createHttpClient
import app.audioflix.player.PlayerConnection
import app.audioflix.provider.ProviderRegistry
import app.audioflix.provider.audiovault.AudioVaultProvider
import app.audioflix.provider.gdrive.GoogleDriveProvider
import app.audioflix.provider.local.LocalFolderProvider
import app.audioflix.provider.web.WebFolderProvider
import app.audioflix.net.RangedDownloader
import app.audioflix.ui.folders.AddNotices
import app.audioflix.summary.SummaryRepository
import app.audioflix.summary.WikipediaClient
import app.audioflix.update.UpdateManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudioFlixApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    container.appScope.launch { container.library.resumePending() }
    container.updates.cleanUpDownloads()
    if (BuildConfig.CHECK_UPDATES_ON_START) container.updates.check()
  }
}

/** Hand-wired dependencies shared by the UI, the download worker and the playback service. */
class AppContainer(context: Context) {
  val appScope = CoroutineScope(SupervisorJob())

  private val prefs = context.getSharedPreferences("audioflix", Context.MODE_PRIVATE)
  private val cookieJar = PersistentCookieJar(File(context.filesDir, "cookies.txt"))
  private val httpClient = createHttpClient(cookieJar)
  private val apiClient = createApiClient()

  val settings = Settings(prefs)
  val database = LibraryDatabase.create(context)

  val providers = ProviderRegistry(
    listOf(
      AudioVaultProvider(httpClient, cookieJar, prefs),
      GoogleDriveProvider(httpClient, prefs),
      WebFolderProvider(httpClient, prefs),
      LocalFolderProvider(context, prefs),
    ),
  )

  val library = LibraryRepository(
    context,
    database.libraryDao(),
    settings,
    DownloadQuota(prefs),
    ChapterReader(context, httpClient),
    providers,
  )

  /** Downloads titles over several connections, for providers whose faster downloads are on. */
  val mediaDownloader = RangedDownloader(httpClient, connections = 6, chunkSize = 4L shl 20)

  /** Results of adding titles, shown wherever the user is by then. */
  val addNotices = AddNotices()

  val player = PlayerConnection(context, library, providers, settings, appScope)

  /** Recently streamed audio, shared by every playback session (a cache directory has one owner). */
  @OptIn(UnstableApi::class)
  val streamCache: SimpleCache by lazy {
    SimpleCache(
      File(context.cacheDir, "streams"),
      LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
      StandaloneDatabaseProvider(context),
    )
  }

  val updates = UpdateManager(context, settings, apiClient, appScope)

  val summaries = SummaryRepository(File(context.filesDir, "summaries"), WikipediaClient(apiClient))
}

val Context.container: AppContainer get() = (applicationContext as AudioFlixApp).container

private const val STREAM_CACHE_BYTES = 256L * 1024 * 1024
