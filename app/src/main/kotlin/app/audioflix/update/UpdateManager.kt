package app.audioflix.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.audioflix.BuildConfig
import app.audioflix.data.Settings
import app.audioflix.data.UpdateConnections
import app.audioflix.net.FileChangedException
import app.audioflix.net.HttpStatusException
import app.audioflix.net.NotTheFileException
import app.audioflix.net.RangedDownloader
import app.audioflix.net.await
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
  /** [note] says why the download is going more slowly or starting again, after a problem. */
  data class Downloading(val bytesRead: Long, val totalBytes: Long, val note: String? = null) : InstallStep {
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
  private val http: OkHttpClient,
  private val scope: CoroutineScope,
) {
  val installed: AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0)

  private val downloadDir = File(context.cacheDir, "updates")
  // Slow, patchy connections get several tries at each piece, with longer waits between them.
  private val downloader = RangedDownloader(
    http,
    connections = UpdateConnections.CHOICES.max(),
    maxAttempts = 5,
    requireSameFile = true,
  )

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
        InstallStep.Failed(SIGNED_DIFFERENTLY)
      PackageInstaller.STATUS_FAILURE_INVALID -> {
        // Whatever is wrong with the file, downloading it again is the way out.
        _state.value.available?.latest?.let { release -> scope.launch(Dispatchers.IO) { apkFile(release).delete() } }
        InstallStep.Failed("Android couldn't read the downloaded update. Try again to download it afresh.")
      }
      PackageInstaller.STATUS_FAILURE_STORAGE -> InstallStep.Failed("There isn't enough storage to install the update.")
      else -> InstallStep.Failed("The update couldn't be installed${message?.let { ": $it" } ?: "."}")
    }
    if (status != PackageInstaller.STATUS_SUCCESS) settings.pendingUpdateVersion = null
    _state.update { it.copy(install = step) }
  }

  internal fun requestConfirmation(intent: Intent) {
    _confirmations.trySend(intent)
  }

  /**
   * Deletes downloaded APKs that are no newer than the installed app. Partial downloads of a newer
   * version are kept, so the next try carries on from them.
   */
  fun cleanUpDownloads() {
    scope.launch(Dispatchers.IO) {
      downloadDir.listFiles()?.forEach { file ->
        val name = file.name.removeSuffix(".done").removeSuffix(".part")
        val version = AppVersion.parse(name.removePrefix("audioflix-").removeSuffix(".apk"))
        if (version == null || version <= installed) file.delete()
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

  /**
   * An APK from an earlier attempt, so cancelling the installer doesn't mean downloading again. It's
   * checked again first, and thrown away if it no longer passes.
   */
  private suspend fun downloadedApk(release: Release): File? = withContext(Dispatchers.IO) {
    val file = apkFile(release)
    when {
      !file.isFile -> null
      checkApk(file, release) == ApkCheck.Ok -> file
      else -> null.also { file.delete() }
    }
  }

  /**
   * Downloads [release]'s APK and checks it. A download that fails part way is tried again over
   * fewer connections, carrying on from what was saved, since many connections at once are what a
   * weak or patchy network copes with worst. A download that arrives damaged is fetched once more
   * from scratch over a single connection.
   */
  private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
    downloadDir.mkdirs()
    val target = apkFile(release)
    val part = File(downloadDir, target.name + ".part")
    var connections = settings.updateConnections.value
    var failures = 0
    var fetchedAgain = false
    _state.update { it.copy(install = InstallStep.Downloading(0, release.apkSize)) }

    while (true) {
      try {
        fetch(release, part, connections)
      } catch (e: IOException) {
        // Cancelling closes the connection, which can show up here as an IOException.
        ensureActive()
        failures++
        if (!worthRetrying(e) || failures >= MAX_DOWNLOAD_TRIES) throw e
        if (e is FileChangedException) {
          showNote("The update was replaced on the server while downloading. Downloading the new copy.")
        } else {
          connections = (connections / 2).coerceAtLeast(1)
          showNote("The connection dropped. Carrying on over ${connectionsText(connections)}.")
        }
        delay(RETRY_DELAY_MS * failures)
        continue
      }
      ensureActive()
      when (val check = checkApk(part, release)) {
        ApkCheck.Ok -> break
        is ApkCheck.Unusable -> {
          part.delete()
          throw IOException(check.message)
        }
        ApkCheck.Damaged -> {
          part.delete()
          downloader.logFile(part).delete()
          if (fetchedAgain) {
            throw IOException(
              "The update arrived damaged twice. Try again later, or choose fewer connections in " +
                "Settings, under App updates.",
            )
          }
          fetchedAgain = true
          connections = 1
          showNote("The download arrived damaged. Downloading it again over 1 connection.")
        }
      }
    }
    if (!part.renameTo(target)) throw IOException("Couldn't save the downloaded update.")
    target
  }

  private suspend fun fetch(release: Release, part: File, connections: Int) {
    val lastReport = AtomicLong(0)
    downloader.download(release.apkUrl, part, connections) { done, reported ->
      val total = reported.takeIf { it > 0 } ?: release.apkSize
      val now = System.currentTimeMillis()
      val last = lastReport.get()
      if (done == total || (now - last > 250 && lastReport.compareAndSet(last, now))) {
        _state.update { state ->
          val note = (state.install as? InstallStep.Downloading)?.note
          state.copy(install = InstallStep.Downloading(done, total, note))
        }
      }
    }
  }

  private fun showNote(note: String) {
    _state.update { state ->
      val step = state.install as? InstallStep.Downloading ?: return@update state
      state.copy(install = step.copy(note = note))
    }
  }

  /** Network trouble is worth another go; a refusal from the server, or a missing file, isn't. */
  private fun worthRetrying(e: IOException): Boolean = when (e) {
    is NotTheFileException -> false
    is HttpStatusException -> e.code !in 400..499
    else -> true
  }

  private sealed interface ApkCheck {
    data object Ok : ApkCheck
    /** Cut short or corrupted on the way: downloading it again should fix it. */
    data object Damaged : ApkCheck
    /** A sound file that still can't replace this app, so downloading it again won't help. */
    data class Unusable(val message: String) : ApkCheck
  }

  /**
   * Checks a downloaded APK before it goes to the installer: its size and SHA-256 against what
   * GitHub lists (when it lists them), and that Android can read it as an AudioFlix of the expected
   * version, signed with the same key as this app.
   */
  private fun checkApk(file: File, release: Release): ApkCheck {
    if (release.apkSize > 0 && file.length() != release.apkSize) return ApkCheck.Damaged
    if (release.apkSha256 != null && release.apkSha256 != sha256(file)) return ApkCheck.Damaged
    val info = runCatching { packageInfo(file) }.getOrNull() ?: return ApkCheck.Damaged
    if (info.packageName != context.packageName) {
      return ApkCheck.Unusable(
        "This update is for a different edition of AudioFlix (the released app or a development " +
          "build), so it can't replace this one.",
      )
    }
    if (AppVersion.parse(info.versionName.orEmpty()) != release.version) {
      return ApkCheck.Unusable("The download isn't version ${release.version} as expected, so it wasn't installed.")
    }
    val theirs = signers(info)
    val ours = runCatching { packageInfo(null)?.let(::signers) }.getOrNull().orEmpty()
    // Older Android versions can't always read an APK's signature; the installer still checks it.
    if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs.none { it in ours }) {
      return ApkCheck.Unusable(SIGNED_DIFFERENTLY)
    }
    return ApkCheck.Ok
  }

  /** The package info of [apk], or of this app when [apk] is null, with signing certificates. */
  @Suppress("DEPRECATION")
  private fun packageInfo(apk: File?): PackageInfo? {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    return when {
      apk == null && Build.VERSION.SDK_INT >= 33 ->
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
      apk == null -> pm.getPackageInfo(context.packageName, flags)
      Build.VERSION.SDK_INT >= 33 -> pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
      else -> pm.getPackageArchiveInfo(apk.path, flags)
    }
  }

  @Suppress("DEPRECATION")
  private fun signers(info: PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
      val signing = info.signingInfo ?: return emptySet()
      if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
    } else {
      info.signatures
    }
    return signatures.orEmpty().map { it.toCharsString() }.toSet()
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
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
    is SocketTimeoutException, is SocketException ->
      "The connection is too slow or keeps dropping. Try again to carry on where it stopped, or " +
        "choose fewer connections in Settings, under App updates."
    is IOException -> e.message ?: "A network error occurred."
    else -> e.message ?: e.javaClass.simpleName
  }

  private companion object {
    /** Downloads of one update before giving up, each carrying on from the last. */
    const val MAX_DOWNLOAD_TRIES = 4
    const val RETRY_DELAY_MS = 3_000L

    const val SIGNED_DIFFERENTLY =
      "This update can't replace the installed app, usually because the installed copy is a " +
        "development build signed with a different key. Uninstall it, then install the update."
  }
}

/** "1 connection", "4 connections". */
fun connectionsText(n: Int) = if (n == 1) "1 connection" else "$n connections"
