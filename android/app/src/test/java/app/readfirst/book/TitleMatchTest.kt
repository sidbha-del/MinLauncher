package app.readfirst.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleMatchTest {
    private fun c(title: String, author: String, cover: Long = 1, editions: Int = 1, key: String = title) =
        TitleMatch.Candidate(key, title, listOf(author), cover, editions)

    /** The real first three Open Library results for title=frankenstein. */
    private val frankenstein = listOf(
        c("Frankenstein; or, The Modern Prometheus", "Mary Shelley", 12356249, 2188, "OL450063W"),
        c("Dean Koontz's Frankenstein", "Dean Koontz", 6956759, 21),
        c("Frankenstein", "Mary Shelley", 15118068, 84, "OL26860869W"),
    )

    @Test
    fun fileNameTitleMatchesTheClassicNotTheSpinOff() {
        assertEquals("OL450063W", TitleMatch.best("frankenstein", "", frankenstein)?.key)
    }

    @Test
    fun knownAuthorMustAgree() {
        assertNull(TitleMatch.best("Frankenstein", "Dean Koontz", frankenstein.filter { it.authors[0] != "Dean Koontz" }))
        assertEquals("Dean Koontz's Frankenstein", TitleMatch.best("Dean Koontz's Frankenstein", "Koontz", frankenstein)?.title)
    }

    @Test
    fun oneWordTitleNeedsAWidelyPublishedWork() {
        val attention = listOf(c("Attention", "Some Novelist", 99, 3), c("Attention and Effort", "D. Kahneman", 98, 6))
        assertNull(TitleMatch.best("attention", "", attention))
    }

    @Test
    fun ourWordsMustAppearInTitleOrAuthor() {
        val prophet = listOf(c("The Prophet", "Kahlil Gibran", 5, 400))
        // Real file names often append the author.
        assertEquals("The Prophet", TitleMatch.best("the prophet gibran", "", prophet)?.title)
        assertEquals("The Prophet", TitleMatch.best("the prophet", "", prophet)?.title)
        // A word that is neither title nor author means it's a different book.
        assertNull(TitleMatch.best("the prophet smith", "", prophet))
        assertEquals("Gitanjali", TitleMatch.best("gitanjali tagore", "", listOf(c("Gitanjali", "Rabindranath Tagore", 7, 150)))?.title)
    }

    @Test
    fun cleansFileNameNoise() {
        assertEquals("Pride and Prejudice", TitleMatch.cleanTitle("Pride_and_Prejudice (Illustrated) [epub] 1813"))
        assertEquals(setOf("alice", "wonderland"), TitleMatch.tokens("Alice in Wonderland"))
    }
}
