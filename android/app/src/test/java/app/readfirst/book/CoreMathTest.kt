package app.readfirst.book

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class CoreMathTest {

    @Test
    fun chapterTextOffsetsAndRanges() {
        val chapter = Chapter(
            "T",
            listOf(
                Block(BlockKind.HEADING, listOf(Run("Title", bold = true)), level = 1),
                Block(BlockKind.PARAGRAPH, listOf(Run("A "), Run("b", italic = true))),
                Block(BlockKind.LIST_ITEM, listOf(Run("item"))),
            ),
        )
        val ct = ChapterText.build(chapter)
        assertEquals("Title\nA b\n${ChapterText.BULLET}item", ct.text)
        val italic = ct.ranges.single { it.style == ChapterText.Style.ITALIC }
        assertEquals("b", ct.text.substring(italic.start, italic.end))
        val heading = ct.ranges.single { it.style == ChapterText.Style.HEADING }
        assertEquals(1, heading.level)
        assertEquals(3, ct.ranges.count { it.style == ChapterText.Style.PARAGRAPH })
    }

    @Test
    fun flowProgress() {
        val lengths = intArrayOf(100, 300)
        assertEquals(0f, Progress.flow(lengths, Locator(0, 0)), 0.0001f)
        assertEquals(0.25f, Progress.flow(lengths, Locator(1, 0)), 0.0001f)
        assertEquals(1f, Progress.flow(lengths, Locator(1, 300)), 0.0001f)
        assertEquals(1f, Progress.flow(lengths, Locator(9, 999)), 0.0001f) // clamped, never throws
        assertEquals(0f, Progress.flow(IntArray(0), Locator(0, 0)), 0.0001f)
    }

    @Test
    fun pagedProgress() {
        assertEquals(0f, Progress.paged(0, 11), 0.0001f)
        assertEquals(0.5f, Progress.paged(5, 11), 0.0001f)
        assertEquals(1f, Progress.paged(10, 11), 0.0001f)
        assertEquals(1f, Progress.paged(0, 1), 0.0001f)
    }

    @Test
    fun partialMd5MatchesKoreaderSampling() {
        assertEquals(256L, PartialMd5.offsets.first())
        assertEquals(1024L shl 20, PartialMd5.offsets.last())

        // Smaller than 256 bytes: no samples at all -> MD5 of nothing.
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", PartialMd5.of(ByteArray(100)))

        // 10 KB file: samples at 256 (1024 B), 1024 (1024 B), 4096 (1024 B); 16384 is past EOF.
        val data = ByteArray(10_000) { (it * 31 + 7).toByte() }
        val md5 = MessageDigest.getInstance("MD5")
        for (off in intArrayOf(256, 1024, 4096)) md5.update(data, off, 1024)
        val expected = md5.digest().joinToString("") { "%02x".format(it) }
        assertEquals(expected, PartialMd5.of(data))
    }
}
