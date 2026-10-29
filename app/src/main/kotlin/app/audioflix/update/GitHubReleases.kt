package app.audioflix.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published release that the app can update to. */
data class Release(
  val version: AppVersion,
  val title: String,
  val notes: String,
  val publishedAt: String?,
  val apkUrl: String,
  val apkSize: Long,
  /** Lowercase hex SHA-256 of the APK, when GitHub reports one. */
  val apkSha256: String?,
)

@Serializable
private data class GitHubRelease(
  @SerialName("tag_name") val tagName: String,
  val name: String? = null,
  val body: String? = null,
  val draft: Boolean = false,
  val prerelease: Boolean = false,
  @SerialName("published_at") val publishedAt: String? = null,
  val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
  val name: String,
  val size: Long = 0,
  val digest: String? = null,
  @SerialName("browser_download_url") val downloadUrl: String,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses the GitHub `GET /repos/{owner}/{repo}/releases` response into the stable releases newer
 * than [installed], newest first. Releases without an APK or with a non-version tag are skipped.
 */
fun newerReleases(responseJson: String, installed: AppVersion): List<Release> =
  json.decodeFromString<List<GitHubRelease>>(responseJson)
    .asSequence()
    .filter { !it.draft && !it.prerelease }
    .mapNotNull { release ->
      val version = AppVersion.parse(release.tagName) ?: return@mapNotNull null
      val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@mapNotNull null
      Release(
        version = version,
        title = release.name?.takeIf { it.isNotBlank() } ?: "AudioFlix $version",
        notes = release.body.orEmpty().trim(),
        publishedAt = release.publishedAt,
        apkUrl = apk.downloadUrl,
        apkSize = apk.size,
        apkSha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
      )
    }
    .filter { it.version > installed }
    .sortedByDescending { it.version }
    .toList()
