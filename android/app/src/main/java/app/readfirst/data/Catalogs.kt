package app.readfirst.data

import android.content.Context
import android.net.Uri
import app.readfirst.book.Opds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An OPDS catalog. [user]/[pass] are for HTTP Basic sign-in on servers that ask for it. */
data class Catalog(
    val id: String,
    val name: String,
    val url: String,
    val note: String,
    val user: String = "",
    val pass: String = "",
    val builtIn: Boolean = false,
    /** A page a person can read in a browser, for when the feed itself won't load. */
    val web: String = "",
) {
    val auth: String? get() = if (user.isNotBlank()) "$user:$pass" else null
}

/** Somewhere to find books. [free] separates public-domain libraries from shops. */
data class BookSource(val name: String, val url: String, val note: String, val free: Boolean)

object Catalogs {
    private val BUILT_IN = listOf(
        Catalog(
            "gutenberg", "Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/",
            "75,000+ free classics", builtIn = true, web = "https://www.gutenberg.org/ebooks/categories.html",
        ),
    )

    /**
     * Libraries and shops worth knowing about, none of them reachable as an in-app feed: Standard
     * Ebooks keeps its catalog for Patrons Circle members, and the shops sell through their own
     * sites. Every one of these opens in a browser; nothing is bought inside the app.
     */
    val SOURCES = listOf(
        BookSource("Standard Ebooks", "https://standardebooks.org/ebooks", "Carefully typeset classics", true),
        BookSource("Open Library", "https://openlibrary.org", "Borrow scanned books", true),
        BookSource("LibriVox", "https://librivox.org", "Public-domain audiobooks", true),
        BookSource("Google Play Books", "https://play.google.com/store/books", "Buy ebooks", false),
        BookSource("Kobo", "https://www.kobo.com/ebooks", "Buy ebooks", false),
        BookSource("Amazon Kindle", "https://www.amazon.com/kindle-store", "Buy ebooks", false),
    )

    /**
     * Where to send someone whose feed won't load. Falls back to the site's front page, since an
     * OPDS address is XML a browser will only offer to download.
     */
    fun webPage(catalog: Catalog, feedUrl: String): String {
        if (catalog.web.isNotBlank()) return catalog.web
        val u = Uri.parse(feedUrl.ifBlank { catalog.url })
        val host = u.host ?: return catalog.url
        return "${u.scheme ?: "https"}://$host"
    }

    fun all(prefs: Prefs): List<Catalog> {
        val saved = read(prefs)
        val builtIns = BUILT_IN.map { b -> saved.firstOrNull { it.id == b.id }?.let { b.copy(user = it.user, pass = it.pass) } ?: b }
        return builtIns + saved.filter { s -> BUILT_IN.none { it.id == s.id } }
    }

    fun save(prefs: Prefs, catalog: Catalog) {
        val others = read(prefs).filter { it.id != catalog.id }
        write(prefs, others + catalog)
    }

    fun remove(prefs: Prefs, id: String) = write(prefs, read(prefs).filter { it.id != id })

    /** The library book already downloaded from any of [links], if any. */
    fun inLibrary(library: Library, links: List<Opds.Link>): BookEntry? {
        val hrefs = links.map { it.href }.toSet()
        return library.books.firstOrNull { it.sourceUrl.isNotEmpty() && it.sourceUrl in hrefs }
    }

    /**
     * Network work for catalogs, kept off [Library.io]: a stalled connection (a DNS lookup has no
     * timeout) must never hold up saving reading positions.
     */
    val net: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newCachedThreadPool()

    /**
     * Downloads [link] into app storage and adds it to the library (on the main thread).
     * [onProgress] and [onDone] run on the main thread.
     */
    fun download(
        context: Context,
        catalog: Catalog,
        entry: Opds.Entry,
        link: Opds.Link,
        onProgress: (Float) -> Unit,
        onDone: (BookEntry?, String?) -> Unit,
    ) {
        val lib = Library.get(context)
        val format = Opds.formatOf(link) ?: return onDone(null, "This file type isn't supported")
        val ext = when (format) { app.readfirst.book.Format.EPUB -> "epub"; app.readfirst.book.Format.PDF -> "pdf"; app.readfirst.book.Format.TXT -> "txt" }
        val safe = entry.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifEmpty { "book" }
        val dest = File(lib.downloadsDir(), "$safe-${Library.idFor(link.href).take(6)}.$ext")
        val app = context.applicationContext
        net.execute {
            try {
                Net.download(link.href, dest, catalog.auth) { f -> lib.postToMain { onProgress(f) } }
                val uri = Uri.fromFile(dest).toString()
                val book = BookEntry(
                    id = Library.idFor(uri), uri = uri, format = format,
                    title = entry.title.ifBlank { safe }, author = entry.author,
                    fileTitle = entry.title, sourceUrl = link.href,
                    // A PDF or text download has no metadata of its own to read subjects from later.
                    subjects = entry.categories,
                )
                lib.postToMain {
                    lib.addAll(listOf(book))
                    Scanner.enrichPending(app)
                    onDone(lib.find(book.id) ?: book, null)
                }
            } catch (e: Net.AuthRequired) {
                lib.postToMain { onDone(null, "This catalog needs you to sign in") }
            } catch (e: Net.HttpError) {
                val why = if (e.code == 403) "${catalog.name} is refusing downloads right now" else "The catalog answered with an error (${e.code})"
                lib.postToMain { onDone(null, why) }
            } catch (e: Exception) {
                lib.postToMain { onDone(null, "Download failed: ${e.message ?: "network error"}") }
            }
        }
    }

    private fun read(prefs: Prefs): List<Catalog> = runCatching {
        val a = JSONArray(prefs.catalogsJson)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Catalog(o.getString("id"), o.optString("name"), o.optString("url"), o.optString("note"), o.optString("user"), o.optString("pass"))
        }
    }.getOrDefault(emptyList())

    private fun write(prefs: Prefs, list: List<Catalog>) {
        prefs.catalogsJson = JSONArray().apply {
            list.forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("url", c.url).put("note", c.note).put("user", c.user).put("pass", c.pass))
            }
        }.toString()
    }
}
