package app.booklauncher.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBookTest {

    @Test
    fun hardWrappedParagraphsAreJoined() {
        val text = "It is a truth universally\nacknowledged, that a single man\nin possession of a good fortune.\n\n" +
            "However little known the feelings\nor views of such a man may be.\n\nThe end."
        assertEquals(
            listOf(
                "It is a truth universally acknowledged, that a single man in possession of a good fortune.",
                "However little known the feelings or views of such a man may be.",
                "The end.",
            ),
            TextBook.paragraphs(text),
        )
    }

    @Test
    fun oneParagraphPerLineIsKept() {
        val text = "First paragraph is on one line.\nSecond paragraph is on the next line.\nThird."
        assertEquals(3, TextBook.paragraphs(text).size)
    }

    @Test
    fun splitsAtChapterHeadings() {
        val text = "Title page\n\nCHAPTER I\n\nOne text.\n\nChapter 2. The Next\n\nTwo text.\n\nMore two."
        val book = TextBook.load(text.toByteArray(), "My Book")
        assertEquals(listOf("My Book", "CHAPTER I", "Chapter 2. The Next"), book.chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), book.chapters.map { it.spineIndex })
        assertEquals(BlockKind.HEADING, book.chapters[1].blocks[0].kind)
    }

    @Test
    fun longTextWithoutHeadingsIsSplitIntoParts() {
        val para = "word ".repeat(200).trim()
        val text = List(700) { para }.joinToString("\n\n")
        val book = TextBook.load(text.toByteArray(), "Long")
        assertTrue(book.chapters.size > 1)
        assertTrue(book.chapters.all { ch -> ch.blocks.sumOf { it.plain.length + 1 } <= TextBook.MAX_CHAPTER_CHARS + para.length + 1 })
        assertEquals("Part 1", book.chapters[0].title)
    }

    @Test
    fun decodesBomsAndFallsBackToWindows1252() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "café".toByteArray(Charsets.UTF_8)
        assertEquals("café", TextBook.decode(utf8Bom))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        assertEquals("hi", TextBook.decode(utf16))
        // 0xE9 alone is invalid UTF-8; 0x93/0x94 are curly quotes in Windows-1252.
        val cp1252 = byteArrayOf(0x93.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), 0x94.toByte())
        assertEquals("“café”", TextBook.decode(cp1252))
    }
}
