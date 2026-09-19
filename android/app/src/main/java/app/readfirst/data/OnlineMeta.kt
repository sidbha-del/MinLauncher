package app.readfirst.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.readfirst.book.Format
import app.readfirst.book.TitleMatch
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Fills gaps in book details from Open Library: covers, authors, and proper titles for files
 * whose only title is their file name. Runs only when the user allows online lookups, only for
 * books that are missing something, one request per second, and only accepts confident matches
 * (see [TitleMatch]); any match can be undone from the book's menu.
 */
object OnlineMeta {
    @Volatile
    private var running = false

    fun needsLookup(b: BookEntry): Boolean =
        !b.olChecked && b.metaDone && (b.format != Format.EPUB || !b.hasCover || b.author.isBlank())

    fun lookupPending(context: Context) {
        val app = context.applicationContext
        val lib = Library.get(app)
        if (!Prefs.get(app).onlineMeta || running) return
        val pending = lib.books.filter(::needsLookup)
        if (pending.isEmpty()) return
        running = true
        Thread {
            try {
                for (book in pending) {
                    val result = runCatching { lookup(app, book) }.getOrNull()
                    lib.postToMain {
                        lib.update(book.id) { cur ->
                            if (result == null) cur.copy(olChecked = true) else apply(cur, result)
                        }
                    }
                    Thread.sleep(1100) // Open Library asks clients to stay near one request per second.
                }
            } finally {
                running = false
            }
        }.apply { isDaemon = true; name = "open-library" }.start()
    }

    /** Looks one book up again, e.g. after the user reset a wrong match. */
    fun retry(context: Context, id: String) {
        Library.get(context).update(id) { it.copy(olChecked = false) }
        lookupPending(context)
    }

    /** Undo an Open Library match: back to the file's own title, no online cover or author. */
    fun reset(context: Context, id: String) {
        val lib = Library.get(context)
        val book = lib.find(id) ?: return
        if (book.olKey.isNotEmpty()) lib.coverFile(id).delete()
        lib.update(id) {
            it.copy(
                title = it.fileTitle.ifBlank { it.title },
                author = if (it.format == Format.EPUB) it.author else "",
                hasCover = if (it.olKey.isNotEmpty()) false else it.hasCover,
                spineColor = if (it.olKey.isNotEmpty()) 0 else it.spineColor,
                olKey = "", olChecked = true,
            )
        }
    }

    private data class Found(val key: String, val title: String, val author: String, val coverSaved: Boolean, val spineColor: Int)

    /** Network work, on a background thread. */
    private fun lookup(context: Context, book: BookEntry): Found? {
        val query = TitleMatch.cleanTitle(if (book.format == Format.EPUB) book.title else book.fileTitle.ifBlank { book.title })
        if (query.isBlank()) return null
        val url = "https://openlibrary.org/search.json?q=" + URLEncoder.encode(query, "UTF-8") +
            (if (book.author.isNotBlank()) "&author=" + URLEncoder.encode(book.author, "UTF-8") else "") +
            "&limit=8&fields=key,title,author_name,cover_i,edition_count"
        val docs = JSONObject(String(Net.get(url), Charsets.UTF_8)).optJSONArray("docs") ?: return null
        val candidates = List(docs.length()) { i ->
            val d = docs.getJSONObject(i)
            val authors = d.optJSONArray("author_name")
            TitleMatch.Candidate(
                key = d.optString("key"),
                title = d.optString("title"),
                authors = if (authors == null) emptyList() else List(authors.length()) { authors.getString(it) },
                coverId = d.optLong("cover_i"),
                editions = d.optInt("edition_count"),
            )
        }
        val match = TitleMatch.best(query, book.author, candidates) ?: return null
        var saved = false
        var spine = 0
        if (!book.hasCover && match.coverId > 0) {
            val bytes = runCatching { Net.get("https://covers.openlibrary.org/b/id/${match.coverId}-L.jpg?default=false") }.getOrNull()
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) {
                Library.get(context).coverFile(book.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                spine = mutedAverage(bmp)
                saved = true
                bmp.recycle()
            }
        }
        return Found(match.key, match.title, match.authors.take(2).joinToString(", "), saved, spine)
    }

    private fun apply(cur: BookEntry, f: Found): BookEntry = cur.copy(
        // EPUBs carry a real title already; files named by their filename get the proper one.
        title = if (cur.format == Format.EPUB) cur.title else f.title,
        author = cur.author.ifBlank { f.author },
        hasCover = cur.hasCover || f.coverSaved,
        spineColor = if (f.coverSaved) f.spineColor else cur.spineColor,
        olKey = f.key,
        olChecked = true,
    )

    private fun mutedAverage(bmp: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(bmp, 1, 1, true)
        val hsv = FloatArray(3)
        Color.colorToHSV(one.getPixel(0, 0), hsv)
        if (one != bmp) one.recycle()
        hsv[1] = hsv[1].coerceIn(0.18f, 0.5f)
        hsv[2] = hsv[2].coerceIn(0.26f, 0.42f)
        return Color.HSVToColor(hsv)
    }
}
