package app.readfirst.book

enum class Format {
    TXT, EPUB, PDF;

    companion object {
        fun fromFileName(name: String): Format? = when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "text" -> TXT
            "epub" -> EPUB
            "pdf" -> PDF
            else -> null
        }
    }
}

data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false)

enum class BlockKind { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, PREFORMATTED }

data class Block(val kind: BlockKind, val runs: List<Run>, val level: Int = 0) {
    val plain: String get() = runs.joinToString("") { it.text }
}

/**
 * [spineIndex] is the chapter's 0-based position in the EPUB spine *before* empty documents are
 * skipped. KOReader progress sync addresses positions as `/body/DocFragment[spineIndex + 1]`,
 * so this must never be renumbered after filtering. For TXT it equals the chapter index.
 */
data class Chapter(val title: String, val blocks: List<Block>, val spineIndex: Int = 0)

/** A reflowable book (TXT or EPUB), already reduced to styled blocks. */
class FlowBook(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
) {
    /** Character length of each chapter's rendered text, as produced by [ChapterText]. */
    val chapterLengths: IntArray by lazy { IntArray(chapters.size) { ChapterText.build(chapters[it]).text.length } }
}

/**
 * A reading position that survives font-size, margin and screen changes: which chapter (for a
 * PDF, which page) and a character offset into that chapter's rendered text. This is also the
 * shape the planned KOReader progress sync will map to and from.
 */
data class Locator(val chapter: Int, val offset: Int) {
    companion object {
        val START = Locator(0, 0)
    }
}

object Progress {
    /** Fraction read (0..1) for a reflowable book. */
    fun flow(lengths: IntArray, at: Locator): Float {
        val total = lengths.sum()
        if (total <= 0 || lengths.isEmpty()) return 0f
        val ch = at.chapter.coerceIn(0, lengths.size - 1)
        var before = 0L
        for (i in 0 until ch) before += lengths[i]
        val within = at.offset.coerceIn(0, lengths[ch])
        return ((before + within).toFloat() / total).coerceIn(0f, 1f)
    }

    /** Fraction read for a paged book (PDF): the last page counts as finished. */
    fun paged(page: Int, pageCount: Int): Float {
        if (pageCount <= 1) return if (pageCount == 1) 1f else 0f
        return (page.coerceIn(0, pageCount - 1).toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
    }

    fun percent(fraction: Float): Int = (fraction * 100).toInt().coerceIn(0, 100)
}
