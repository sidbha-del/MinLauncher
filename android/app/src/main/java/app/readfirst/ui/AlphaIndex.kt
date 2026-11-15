package app.readfirst.ui

/**
 * The Apps list's A-Z strip, on plain data so it can be tested. The list sorts by folded label, so
 * digits come before "A" and symbols or non-Latin letters after "Z": the strip shows one entry
 * per Latin letter that exists, and a single "#" last standing for everything else, which jumps
 * to the first of those sections.
 */
object AlphaIndex {
    const val OTHER = "#"

    /** What a list section (the upper-cased folded first character) is called on the strip. */
    fun letterFor(section: String): String {
        val c = section.firstOrNull() ?: return OTHER
        return if (c in 'A'..'Z') c.toString() else OTHER
    }

    /** [letters] and, alongside, the list row each one jumps to. */
    data class Strip(val letters: List<String>, val rows: List<Int>)

    /**
     * Builds the strip from the list's own sections ([sections] and the row each starts at, in list
     * order): letters that occur once each in alphabetical order, "#" last, each mapped to the
     * first row it covers.
     */
    fun build(sections: List<String>, sectionStart: List<Int>): Strip {
        val first = LinkedHashMap<String, Int>()
        for (i in sections.indices) {
            val letter = letterFor(sections[i])
            val row = sectionStart.getOrElse(i) { 0 }
            val had = first[letter]
            if (had == null || row < had) first[letter] = row
        }
        val ordered = first.keys.filter { it != OTHER }.sorted() + listOfNotNull(OTHER.takeIf { it in first })
        return Strip(ordered, ordered.map { first.getValue(it) })
    }

    /** The entry under a finger at [y] on a strip [height] tall with [count] entries: continuous, not 48dp per letter. */
    fun indexAt(y: Float, height: Float, count: Int): Int {
        if (count <= 0 || height <= 0f) return 0
        return (y / height * count).toInt().coerceIn(0, count - 1)
    }
}
