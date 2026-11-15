package app.readfirst.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphaIndexTest {
    @Test fun lettersAreDedupedAndOtherIsLast() {
        // As the list builds them: digits first, Latin letters, then a symbol and a non-Latin one.
        val s = AlphaIndex.build(listOf("#", "A", "B", "#", "Я"), listOf(0, 3, 9, 20, 25))
        assertEquals(listOf("A", "B", "#"), s.letters)
        // "#" jumps to its first section, the digits at the top.
        assertEquals(listOf(3, 9, 0), s.rows)
    }

    @Test fun onlyExistingLettersAppear() {
        val s = AlphaIndex.build(listOf("C", "F", "Z"), listOf(0, 4, 8))
        assertEquals(listOf("C", "F", "Z"), s.letters)
        assertEquals(listOf(0, 4, 8), s.rows)
    }

    @Test fun otherOnlyAtTheEnd() {
        val s = AlphaIndex.build(listOf("A", "~"), listOf(0, 6))
        assertEquals(listOf("A", "#"), s.letters)
        assertEquals(listOf(0, 6), s.rows)
    }

    @Test fun emptyWhenSearching() {
        val s = AlphaIndex.build(emptyList(), emptyList())
        assertEquals(emptyList<String>(), s.letters)
    }

    @Test fun letterFor() {
        assertEquals("Q", AlphaIndex.letterFor("Q"))
        assertEquals("#", AlphaIndex.letterFor("7"))
        assertEquals("#", AlphaIndex.letterFor("Ж"))
        assertEquals("#", AlphaIndex.letterFor(""))
    }

    @Test fun fingerMapsContinuously() {
        assertEquals(0, AlphaIndex.indexAt(-5f, 540f, 27))
        assertEquals(0, AlphaIndex.indexAt(19f, 540f, 27))
        assertEquals(1, AlphaIndex.indexAt(20f, 540f, 27))
        assertEquals(26, AlphaIndex.indexAt(9999f, 540f, 27))
        assertEquals(0, AlphaIndex.indexAt(10f, 0f, 27))
    }
}
