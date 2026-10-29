package app.audioflix.update

/** A `major.minor.patch` version, as used by versionName and the `v1.2.3` release tags. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
  override fun compareTo(other: AppVersion): Int =
    compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

  override fun toString() = "$major.$minor.$patch"

  companion object {
    private val pattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

    /** Parses "1.2.3" or "v1.2.3"; anything else (pre-release suffixes included) gives null. */
    fun parse(text: String): AppVersion? {
      val match = pattern.matchEntire(text.trim()) ?: return null
      val (major, minor, patch) = match.destructured
      return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
    }
  }
}
