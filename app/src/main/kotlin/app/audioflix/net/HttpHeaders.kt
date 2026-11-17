package app.audioflix.net

import java.net.URLDecoder

/** The file name a `Content-Disposition` header suggests, preferring the RFC 5987 `filename*` form. */
fun contentDispositionFileName(header: String?): String? {
  if (header.isNullOrBlank()) return null
  Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    .find(header)
    ?.let { m ->
      val charset = m.groupValues[1].ifBlank { "UTF-8" }
      return runCatching { URLDecoder.decode(m.groupValues[2].trim(), charset) }.getOrNull()
    }
  Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)
    ?.let { return it.groupValues[1].ifBlank { null } }
  Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)
    ?.let { return it.groupValues[1].trim().ifBlank { null } }
  return null
}

/** Where a `Content-Range: bytes 100-199/1000` response starts (100). */
fun contentRangeStart(header: String?): Long? =
  header?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

/** The full size a `Content-Range: bytes 100-199/1000` response gives (1000). */
fun contentRangeTotal(header: String?): Long? =
  header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
