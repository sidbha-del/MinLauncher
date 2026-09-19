package app.booklauncher.book

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Plain-text books. Handles the two common shapes: hard-wrapped text with blank lines between
 * paragraphs (Project Gutenberg style), and one-paragraph-per-line text. Long books are split
 * at "Chapter ..." headings when present, otherwise into parts, so each chapter lays out quickly.
 */
object TextBook {
    const val MAX_CHAPTER_CHARS = 60_000

    private val chapterHeading = Regex(
        """^(chapter|book|part|prologue|epilogue|letter|volume|act)\b[\s.:\-]*([0-9ivxlcdm]+|[a-z-]+)?[\s.:\-]*.{0,60}$""",
        RegexOption.IGNORE_CASE,
    )

    fun load(bytes: ByteArray, title: String): FlowBook {
        val paragraphs = paragraphs(decode(bytes))
        val chapters = ArrayList<Chapter>()
        var current = ArrayList<Block>()
        var currentTitle: String? = null
        var size = 0

        fun close() {
            if (current.isEmpty()) return
            val name = currentTitle ?: if (chapters.isEmpty() && size < MAX_CHAPTER_CHARS) title else "Part ${chapters.size + 1}"
            chapters += Chapter(name, current, spineIndex = chapters.size)
            current = ArrayList()
            currentTitle = null
            size = 0
        }

        for (p in paragraphs) {
            val isHeading = p.length <= 70 && chapterHeading.matches(p)
            if (isHeading || size >= MAX_CHAPTER_CHARS) close()
            if (isHeading) {
                currentTitle = p
                current += Block(BlockKind.HEADING, listOf(Run(p, bold = true)), level = 2)
            } else {
                current += Block(BlockKind.PARAGRAPH, listOf(Run(p)))
            }
            size += p.length + 1
        }
        close()
        if (chapters.isEmpty()) chapters += Chapter(title, listOf(Block(BlockKind.PARAGRAPH, listOf(Run("(empty)")))))
        return FlowBook(title, "", chapters)
    }

    /** UTF-8 (with or without BOM) or UTF-16 by BOM; anything that isn't valid UTF-8 is read as Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /**
     * Splits into paragraphs. If blank lines are common, they separate paragraphs and single
     * newlines inside a paragraph are soft wraps; otherwise every line is its own paragraph.
     */
    fun paragraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val nonEmpty = lines.count { it.isNotBlank() }
        val blankSeparated = normalized.split(Regex("\n[ \t]*\n")).size - 1
        // Gutenberg-style: roughly one blank line per 3-8 wrapped lines, and short lines.
        val hardWrapped = blankSeparated >= maxOf(1, nonEmpty / 16) && averageLength(lines) < 90
        return if (hardWrapped) {
            normalized.split(Regex("\n[ \t]*\n+"))
                .map { it.lines().joinToString(" ") { l -> l.trim() }.replace(Regex(" {2,}"), " ").trim() }
                .filter { it.isNotEmpty() }
        } else {
            lines.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun averageLength(lines: List<String>): Double {
        val nonEmpty = lines.filter { it.isNotBlank() }
        return if (nonEmpty.isEmpty()) 0.0 else nonEmpty.sumOf { it.length }.toDouble() / nonEmpty.size
    }
}
