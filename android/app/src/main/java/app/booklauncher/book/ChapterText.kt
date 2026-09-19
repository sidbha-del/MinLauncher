package app.booklauncher.book

/**
 * A chapter flattened to one string plus style ranges. Offsets into [text] are the character
 * offsets stored in a [Locator], so this function must stay deterministic: the same chapter
 * always produces the same text, whatever the font size or screen.
 *
 * Blocks are separated by a single '\n'; paragraph spacing is applied by the renderer, not by
 * blank lines, so no vertical space is wasted and offsets stay compact.
 */
class ChapterText(val text: String, val ranges: List<StyleRange>) {

    enum class Style { BOLD, ITALIC, HEADING, QUOTE, LIST_ITEM, PREFORMATTED, PARAGRAPH }

    /** [level] is the heading level (1-6) for HEADING, 0 otherwise. */
    data class StyleRange(val start: Int, val end: Int, val style: Style, val level: Int = 0)

    companion object {
        const val BULLET = "•  "

        fun build(chapter: Chapter): ChapterText {
            val sb = StringBuilder()
            val ranges = ArrayList<StyleRange>()
            chapter.blocks.forEachIndexed { index, block ->
                if (index > 0) sb.append('\n')
                val blockStart = sb.length
                if (block.kind == BlockKind.LIST_ITEM) sb.append(BULLET)
                for (run in block.runs) {
                    val s = sb.length
                    sb.append(run.text)
                    val e = sb.length
                    if (e > s) {
                        if (run.bold) ranges += StyleRange(s, e, Style.BOLD)
                        if (run.italic) ranges += StyleRange(s, e, Style.ITALIC)
                    }
                }
                val blockEnd = sb.length
                if (blockEnd > blockStart) {
                    val style = when (block.kind) {
                        BlockKind.HEADING -> Style.HEADING
                        BlockKind.QUOTE -> Style.QUOTE
                        BlockKind.LIST_ITEM -> Style.LIST_ITEM
                        BlockKind.PREFORMATTED -> Style.PREFORMATTED
                        BlockKind.PARAGRAPH -> Style.PARAGRAPH
                    }
                    ranges += StyleRange(blockStart, blockEnd, style, if (block.kind == BlockKind.HEADING) block.level else 0)
                    if (style != Style.PARAGRAPH) ranges += StyleRange(blockStart, blockEnd, Style.PARAGRAPH)
                }
            }
            return ChapterText(sb.toString(), ranges)
        }
    }
}
