package app.audioflix.update

/** One line of release notes, reduced to what the update screen shows. */
sealed interface NotesBlock {
  data class Heading(val text: String) : NotesBlock
  data class Bullet(val text: String) : NotesBlock
  data class Paragraph(val text: String) : NotesBlock
}

/**
 * Reads the small Markdown subset CHANGELOG.md uses: `#` headings, `-` or `*` bullets and plain
 * paragraphs. Inline formatting is stripped so a screen reader doesn't read out asterisks.
 */
fun parseReleaseNotes(markdown: String): List<NotesBlock> {
  val blocks = mutableListOf<NotesBlock>()
  val paragraph = StringBuilder()

  fun flushParagraph() {
    if (paragraph.isNotEmpty()) blocks += NotesBlock.Paragraph(paragraph.toString())
    paragraph.clear()
  }

  for (raw in markdown.lines()) {
    val line = raw.trim()
    val bullet = bulletPattern.matchEntire(line)
    when {
      line.isEmpty() -> flushParagraph()
      line.startsWith("#") -> {
        flushParagraph()
        blocks += NotesBlock.Heading(stripInline(line.trimStart('#')))
      }
      bullet != null -> {
        flushParagraph()
        blocks += NotesBlock.Bullet(stripInline(bullet.groupValues[1]))
      }
      // An indented line right after a bullet continues it.
      raw.startsWith(" ") && blocks.lastOrNull() is NotesBlock.Bullet && paragraph.isEmpty() -> {
        val last = blocks.removeAt(blocks.lastIndex) as NotesBlock.Bullet
        blocks += NotesBlock.Bullet(last.text + " " + stripInline(line))
      }
      else -> {
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(stripInline(line))
      }
    }
  }
  flushParagraph()
  return blocks.filterNot { it is NotesBlock.Heading && it.text.isEmpty() }
}

private val bulletPattern = Regex("""^[-*+]\s+(.*)$""")
private val linkPattern = Regex("""\[([^\]]+)]\([^)]*\)""")
private val emphasisPattern = Regex("""(\*\*|\*|`)(.+?)\1""")

private fun stripInline(text: String): String =
  text.trim()
    .replace(linkPattern, "$1")
    .replace(emphasisPattern, "$2")
