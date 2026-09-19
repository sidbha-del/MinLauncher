package app.booklauncher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.booklauncher.book.Format
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

enum class Shelf { READING, TO_READ, FINISHED }

data class BookEntry(
    val id: String,
    val uri: String,
    val format: Format,
    val title: String,
    val author: String = "",
    /** Tree URI of the watched folder this book came from, or null if picked as a file. */
    val folder: String? = null,
    val size: Long = 0,
    /** KOReader partial-MD5 fingerprint, used by progress sync (e-reader sync, planned). */
    val docHash: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val openedAt: Long = 0,
    val shelf: Shelf = Shelf.TO_READ,
    val chapter: Int = 0,
    val offset: Int = 0,
    val progress: Float = 0f,
    /** Text at the resume point, shown on Home. */
    val excerpt: String = "",
    val chapterTitle: String = "",
    val page: Int = 0,
    val pages: Int = 0,
    val metaDone: Boolean = false,
    /** Rough printed length, for spine thickness; 0 until estimated. */
    val pagesEstimate: Int = 0,
    val hasCover: Boolean = false,
    /** Spine colour derived from the cover, 0 when unknown. */
    val spineColor: Int = 0,
    /** Title as first derived from the file, so an online match can be undone. */
    val fileTitle: String = "",
    /** Open Library lookup attempted (whether or not it matched). */
    val olChecked: Boolean = false,
    /** Open Library work key when details came from there, else empty. */
    val olKey: String = "",
    /** Catalog download URL for books fetched from an OPDS catalog, else empty. */
    val sourceUrl: String = "",
)

/**
 * The book list, persisted as one JSON file. All mutation happens on the main thread; disk
 * writes and scanning happen on a single background thread, and listeners are told on the main
 * thread.
 */
class Library private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "library.json")
    private val main = Handler(Looper.getMainLooper())
    val io = Executors.newSingleThreadExecutor()
    private val listeners = ArrayList<() -> Unit>()

    var books: List<BookEntry> = emptyList()
        private set
    var folders: List<String> = emptyList()
        private set

    init {
        load()
    }

    fun find(id: String): BookEntry? = books.firstOrNull { it.id == id }

    /** The book Home shows: the most recently opened unfinished book, else the newest to-read one. */
    fun current(): BookEntry? =
        books.filter { it.shelf == Shelf.READING && it.openedAt > 0 }.maxByOrNull { it.openedAt }
            ?: books.filter { it.shelf != Shelf.FINISHED }.maxByOrNull { maxOf(it.openedAt, it.addedAt) }

    fun onShelf(shelf: Shelf): List<BookEntry> = books.filter { it.shelf == shelf }.let { list ->
        when (shelf) {
            Shelf.READING -> list.sortedByDescending { it.openedAt }
            Shelf.TO_READ -> list.sortedByDescending { it.addedAt }
            Shelf.FINISHED -> list.sortedByDescending { it.openedAt }
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // ---- mutation (main thread) ----------------------------------------------------------------

    fun update(id: String, change: (BookEntry) -> BookEntry) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0) return
        val next = change(books[i])
        if (next == books[i]) return
        books = books.toMutableList().also { it[i] = next }
        changed()
    }

    fun addAll(entries: List<BookEntry>) {
        val known = books.map { it.uri }.toHashSet()
        val fresh = entries.filter { it.uri !in known }
        if (fresh.isEmpty()) return
        books = books + fresh
        changed()
    }

    fun remove(id: String) {
        val book = find(id)
        books = books.filterNot { it.id == id }
        changed()
        File(coverDir(), "$id.jpg").delete()
        // Catalog downloads live in app storage; files from the user's folders are never touched.
        book?.uri?.takeIf { it.startsWith("file://") }?.let { File(android.net.Uri.parse(it).path ?: "") }
            ?.takeIf { it.parentFile?.canonicalPath == downloadsDir().canonicalPath }?.delete()
    }

    /** Where catalog downloads are stored. */
    fun downloadsDir(): File = File(app.filesDir, "books").apply { mkdirs() }

    fun addFolder(tree: String) {
        if (tree in folders) return
        folders = folders + tree
        changed()
    }

    fun removeFolder(tree: String) {
        folders = folders - tree
        val gone = books.filter { it.folder == tree }.map { it.id }.toSet()
        books = books.filterNot { it.id in gone }
        changed()
        gone.forEach { File(coverDir(), "$it.jpg").delete() }
    }

    /** Folder scan results: new books are added, and books whose files vanished are dropped. */
    fun applyScan(tree: String, found: List<BookEntry>) {
        if (tree !in folders) return
        val foundUris = found.map { it.uri }.toHashSet()
        val kept = books.filterNot { it.folder == tree && it.uri !in foundUris }
        val known = kept.map { it.uri }.toHashSet()
        val next = kept + found.filter { it.uri !in known }
        // A rescan usually finds nothing new; rebuilding the UI anyway can swallow a tap in progress.
        if (next == books) return
        books = next
        changed()
    }

    fun coverDir(): File = File(app.filesDir, "covers").apply { mkdirs() }
    fun coverFile(id: String): File = File(coverDir(), "$id.jpg")

    private fun changed() {
        val snapshotBooks = books
        val snapshotFolders = folders
        io.execute { save(snapshotBooks, snapshotFolders) }
        listeners.toList().forEach { it() }
    }

    fun postToMain(block: () -> Unit) = main.post(block)

    // ---- persistence --------------------------------------------------------------------------

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("books") ?: JSONArray()
            books = List(arr.length()) { fromJson(arr.getJSONObject(it)) }.filterNotNull()
            val f = root.optJSONArray("folders") ?: JSONArray()
            folders = List(f.length()) { f.getString(it) }
        }
    }

    private fun save(books: List<BookEntry>, folders: List<String>) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("books", JSONArray().apply { books.forEach { put(toJson(it)) } })
        root.put("folders", JSONArray(folders))
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun toJson(b: BookEntry) = JSONObject().apply {
        put("id", b.id); put("uri", b.uri); put("format", b.format.name); put("title", b.title)
        put("author", b.author); put("folder", b.folder ?: ""); put("size", b.size); put("docHash", b.docHash ?: "")
        put("addedAt", b.addedAt); put("openedAt", b.openedAt); put("shelf", b.shelf.name)
        put("chapter", b.chapter); put("offset", b.offset); put("progress", b.progress.toDouble())
        put("excerpt", b.excerpt); put("chapterTitle", b.chapterTitle); put("page", b.page); put("pages", b.pages)
        put("metaDone", b.metaDone); put("hasCover", b.hasCover); put("spineColor", b.spineColor)
        put("pagesEstimate", b.pagesEstimate)
        put("fileTitle", b.fileTitle); put("olChecked", b.olChecked); put("olKey", b.olKey); put("sourceUrl", b.sourceUrl)
    }

    private fun fromJson(o: JSONObject): BookEntry? {
        val format = runCatching { Format.valueOf(o.getString("format")) }.getOrNull() ?: return null
        return BookEntry(
            id = o.getString("id"),
            uri = o.getString("uri"),
            format = format,
            title = o.optString("title"),
            author = o.optString("author"),
            folder = o.optString("folder").ifEmpty { null },
            size = o.optLong("size"),
            docHash = o.optString("docHash").ifEmpty { null },
            addedAt = o.optLong("addedAt"),
            openedAt = o.optLong("openedAt"),
            shelf = runCatching { Shelf.valueOf(o.optString("shelf")) }.getOrDefault(Shelf.TO_READ),
            chapter = o.optInt("chapter"),
            offset = o.optInt("offset"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            excerpt = o.optString("excerpt"),
            chapterTitle = o.optString("chapterTitle"),
            page = o.optInt("page"),
            pages = o.optInt("pages"),
            metaDone = o.optBoolean("metaDone"),
            hasCover = o.optBoolean("hasCover"),
            spineColor = o.optInt("spineColor"),
            pagesEstimate = o.optInt("pagesEstimate"),
            fileTitle = o.optString("fileTitle"),
            olChecked = o.optBoolean("olChecked"),
            olKey = o.optString("olKey"),
            sourceUrl = o.optString("sourceUrl"),
        )
    }

    companion object {
        @Volatile
        private var instance: Library? = null

        fun get(context: Context): Library =
            instance ?: synchronized(this) { instance ?: Library(context).also { instance = it } }

        fun idFor(uri: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            return d.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
