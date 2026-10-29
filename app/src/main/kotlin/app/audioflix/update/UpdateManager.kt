package app.audioflix.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.audioflix.BuildConfig
import app.audioflix.data.Settings
import app.audioflix.net.await
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Everything newer than the installed version; [releases] is newest first. */
data class AvailableUpdate(val releases: List<Release>) {
  val latest: Release get() = releases.first()
}

sealed interface InstallStep {
  data object Idle : InstallStep
  data class Downloading(val bytesRead: Long, val totalBytes: Long) : InstallStep {
    val percent: Int get() = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt().coerceIn(0, 100) else 0
  }
  /** The APK is with the system installer, which asks the user to confirm. */
  data object Installing : InstallStep
  data class Failed(val message: String) : InstallStep
}

data class UpdateState(
  val checking: Boolean = false,
  /** Null until a check finds something newer. */
  val available: AvailableUpdate? = null,
  /** True when the last check finished and found nothing newer. */
  val upToDate: Boolean = false,
  val checkError: String? = null,
  val install: InstallStep = InstallStep.Idle,
)

/**
 * Checks GitHub Releases for a newer AudioFlix, downloads its APK, verifies it and hands it to the
 * system installer. The installer always asks the user to confirm.
 */
class UpdateManager(
  private val context: Context,
  private val settings: Settings,
  private val scope: CoroutineScope,
) {
  val installed: AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0)

  private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain ->
      chain.proceed(chain.request().newBuilder().header("User-Agent", "AudioFlix/$installed").build())
    }
    .build()

  private val downloadDir = File(context.cacheDir, "updates")

  private val _state = MutableStateFlow(UpdateState())
  val state: StateFlow<UpdateState> = _state.asStateFlow()

  // The installer's confirmation screen has to be started from a visible activity.
  private val _confirmations = Channel<Intent>(Channel.CONFLATED)
  val confirmations = _confirmations.receiveAsFlow()

  private var installJob: Job? = null

  /** Whether the user has allowed AudioFlix to install apps ("Install unknown apps"). */
  fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

  /** True the first time it's called for a given latest version, so the update screen opens once. */
  fun shouldPrompt(update: AvailableUpdate): Boolean {
    val version = update.latest.version.toString()
    if (settings.updatePromptedVersion == version) return false
    settings.updatePromptedVersion = version
    return true
  }

  fun check() {
    if (_state.value.checking) return
    _state.update { it.copy(checking = true, checkError = null, upToDate = false) }
    scope.launch {
      val result = runCatching { fetchNewerReleases() }
      _state.update { state ->
        result.fold(
          onSuccess = { releases ->
            state.copy(
              checking = false,
              available = releases.takeIf { it.isNotEmpty() }?.let(::AvailableUpdate),
              upToDate = releases.isEmpty(),
            )
          },
          onFailure = { state.copy(checking = false, checkError = describe(it)) },
        )
      }
    }
  }

  fun install() {
    val release = _state.value.available?.latest ?: return
    if (installJob?.isActive == true) return
    installJob = scope.launch {
      try {
        val apk = downloadedApk(release) ?: download(release)
        _state.update { it.copy(install = InstallStep.Installing) }
        settings.pendingUpdateVersion = release.version.toString()
        withContext(Dispatchers.IO) { commitSession(apk) }
      } catch (e: CancellationException) {
        _state.update { it.copy(install = InstallStep.Idle) }
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(install = InstallStep.Failed(describe(e))) }
      }
    }
  }

  fun cancelDownload() {
    installJob?.cancel()
  }

  /** Called by [InstallResultReceiver] with the installer's final answer. */
  internal fun onInstallResult(status: Int, message: String?) {
    val step = when (status) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> return
      // On success the system replaces this process, so this is rarely seen.
      PackageInstaller.STATUS_SUCCESS -> InstallStep.Idle
      PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStep.Failed("The installation was cancelled.")
      PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
        InstallStep.Failed(
          "This update can't replace the installed app, usually because the installed copy is a " +
            "development build signed with a different key. Uninstall it, then install the update.",
        )
      PackageInstaller.STATUS_FAILURE_STORAGE -> InstallStep.Failed("There isn't enough storage to install the update.")
      else -> InstallStep.Failed("The update couldn't be installed${message?.let { ": $it" } ?: "."}")
    }
    if (status != PackageInstaller.STATUS_SUCCESS) settings.pendingUpdateVersion = null
    _state.update { it.copy(install = step) }
  }

  internal fun requestConfirmation(intent: Intent) {
    _confirmations.trySend(intent)
  }

  /** Deletes downloaded APKs that are no newer than the installed app, plus partial downloads. */
  fun cleanUpDownloads() {
    scope.launch(Dispatchers.IO) {
      downloadDir.listFiles()?.forEach { file ->
        val version = AppVersion.parse(file.name.removePrefix("audioflix-").removeSuffix(".apk"))
        if (file.name.endsWith(".part") || version == null || version <= installed) file.delete()
      }
    }
  }

  private suspend fun fetchNewerReleases(): List<Release> {
    val request = Request.Builder()
      .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases?per_page=30")
      .header("Accept", "application/vnd.github+json")
      .header("X-GitHub-Api-Version", "2022-11-28")
      .build()
    val body = http.newCall(request).await().use { response ->
      when {
        response.code == 403 || response.code == 429 ->
          throw IOException("GitHub is limiting update checks right now. Try again in an hour.")
        !response.isSuccessful -> throw IOException("The update server answered with HTTP ${response.code}.")
      }
      response.body?.string().orEmpty()
    }
    return withContext(Dispatchers.Default) { newerReleases(body, installed) }
  }

  private fun apkFile(release: Release) = File(downloadDir, "audioflix-${release.version}.apk")

  /** An APK from an earlier attempt, so cancelling the installer doesn't mean downloading again. */
  private fun downloadedApk(release: Release): File? = apkFile(release).takeIf { it.isFile && it.length() > 0 }

  private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
    downloadDir.mkdirs()
    val target = apkFile(release)
    val part = File(downloadDir, target.name + ".part")
    _state.update { it.copy(install = InstallStep.Downloading(0, release.apkSize)) }

    http.newCall(Request.Builder().url(release.apkUrl).build()).await().use { response ->
      if (!response.isSuccessful) throw IOException("The download failed with HTTP ${response.code}.")
      val body = response.body ?: throw IOException("The download was empty.")
      val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
      val digest = MessageDigest.getInstance("SHA-256")
      var read = 0L
      var lastReport = 0L
      body.byteStream().use { input ->
        part.outputStream().use { out ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            read += n
            val now = System.currentTimeMillis()
            if (now - lastReport > 250) {
              lastReport = now
              _state.update { it.copy(install = InstallStep.Downloading(read, total)) }
            }
          }
        }
      }
      if (total > 0 && read < total) throw IOException("The connection closed before the download finished.")
      val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
      if (release.apkSha256 != null && release.apkSha256 != sha256) {
        part.delete()
        throw IOException("The downloaded update is damaged. Try again.")
      }
    }
    if (!part.renameTo(target)) throw IOException("Couldn't save the downloaded update.")
    target
  }

  private fun commitSession(apk: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(context.packageName)
      setSize(apk.length())
    }
    val sessionId = installer.createSession(params)
    try {
      installer.openSession(sessionId).use { session ->
        apk.inputStream().use { input ->
          session.openWrite("base.apk", 0, apk.length()).use { out ->
            input.copyTo(out)
            session.fsync(out)
          }
        }
        val callback = PendingIntent.getBroadcast(
          context,
          sessionId,
          Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
          // Mutable: the installer adds the status extras to this intent.
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        session.commit(callback.intentSender)
      }
    } catch (e: Exception) {
      runCatching { installer.abandonSession(sessionId) }
      throw e
    }
  }

  private fun describe(e: Throwable): String = when (e) {
    is UnknownHostException -> "No internet connection."
    is IOException -> e.message ?: "A network error occurred."
    else -> e.message ?: e.javaClass.simpleName
  }
}
