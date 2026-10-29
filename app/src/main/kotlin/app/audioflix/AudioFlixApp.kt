package app.audioflix

import android.app.Application
import android.content.Context
import app.audioflix.data.LibraryDatabase
import app.audioflix.data.LibraryRepository
import app.audioflix.data.Settings
import app.audioflix.net.PersistentCookieJar
import app.audioflix.net.createApiClient
import app.audioflix.net.createHttpClient
import app.audioflix.player.PlayerConnection
import app.audioflix.provider.ProviderRegistry
import app.audioflix.provider.audiovault.AudioVaultProvider
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
  val library = LibraryRepository(context, database.libraryDao(), settings)

  val providers = ProviderRegistry(
    listOf(AudioVaultProvider(httpClient, cookieJar, prefs)),
  )

  val player = PlayerConnection(context, library, settings, appScope)

  val updates = UpdateManager(context, settings, apiClient, appScope)

  val summaries = SummaryRepository(File(context.filesDir, "summaries"), WikipediaClient(apiClient))
}

val Context.container: AppContainer get() = (applicationContext as AudioFlixApp).container
