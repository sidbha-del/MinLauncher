package app.readfirst.book

import java.text.Normalizer

/**
 * Decides whether an Open Library search result is really the book we have. Wrong covers are
 * worse than no cover, so this is deliberately conservative:
 * - every word of our title must appear in the candidate's title,
 * - our words must cover at least half of the candidate's main title (before ":" or ";"), so
 *   "Frankenstein" matches "Frankenstein; or, The Modern Prometheus" but not
 *   "Dean Koontz's Frankenstein",
 * - if we know the author, the candidate must share an author surname,
 * - a one-word title also needs a widely published work (10+ editions), so a file named
 *   "attention.pdf" never picks up some novel called "Attention".
 */
object TitleMatch {

    data class Candidate(val key: String, val title: String, val authors: List<String>, val coverId: Long, val editions: Int)

    fun best(ourTitle: String, ourAuthor: String, candidates: List<Candidate>): Candidate? {
        val q = tokens(cleanTitle(ourTitle))
        if (q.isEmpty()) return null
        val ourAuthorTokens = tokens(ourAuthor)
        return candidates.mapNotNull { c ->
            val full = tokens(c.title)
            val main = tokens(c.title.split(':', ';').first())
            // File names often carry the author ("the-prophet-gibran"); those words needn't be in the title.
            val authorWords = c.authors.flatMap { tokens(it) }.toSet()
            val titleWords = q.filter { it !in authorWords || it in full }.toSet()
            if (titleWords.isEmpty() || !full.containsAll(titleWords)) return@mapNotNull null
            val coverage = if (main.isEmpty()) 0.0 else main.count { it in titleWords }.toDouble() / main.size
            if (coverage < 0.5) return@mapNotNull null
            if (ourAuthorTokens.isNotEmpty() && c.authors.none { a -> tokens(a).any { it in ourAuthorTokens && it.length > 2 } }) return@mapNotNull null
            if (titleWords.size == 1 && c.editions < 10) return@mapNotNull null
            c to (coverage * 100 + (if (c.coverId > 0) 20 else 0) + minOf(c.editions, 200) / 10.0)
        }.maxByOrNull { it.second }?.first
    }

    /** Strips file-name noise: "(Illustrated)", "[epub]", "Part 2", "gutenberg", years, underscores. */
    fun cleanTitle(s: String): String =
        s.replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
            .replace(Regex("(?i)\\b(project gutenberg|gutenberg|ebook|epub|pdf|illustrated|unabridged)\\b"), " ")
            .replace(Regex("\\b(1[5-9]|20)\\d\\d\\b"), " ")
            .replace('_', ' ').replace('-', ' ')
            .replace(Regex("\\s+"), " ").trim()

    fun tokens(s: String): Set<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ').filter { it.length > 1 && it !in STOP }.toSet()

    private val STOP = setOf("the", "an", "of", "and", "or", "by", "to", "in")
}
