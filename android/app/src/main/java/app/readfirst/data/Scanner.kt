package app.readfirst.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.readfirst.book.Epub
import app.readfirst.book.Format
import app.readfirst.book.PartialMd5
import java.nio.ByteBuffer

/**
 * Finding books in watched folders and filling in their details (title, author, cover, spine
 * colour, KOReader fingerprint). Everything here runs on [Library.io]; results are applied to the
 * library on the main thread.
 */
object Scanner {
    @Volatile
    private var running = false

    /** Rescans every watched folder, then enriches any book whose details are missing. */
    fun refresh(context: Context) {
        val lib = Library.get(context)
        if (running) return
        running = true
        val app = context.applicationContext
        val folders = lib.folders
        lib.io.execute {
            try {
                for (tree in folders) {
                    val found = runCatching { scanFolder(app, Uri.parse(tree)) }.getOrNull() ?: continue
                    lib.postToMain { lib.applyScan(tree, found) }
                }
                lib.postToMain { enrichPending(app) }
            } finally {
                running = false
            }
        }
    }

    /** Enriches books that have not been processed yet, one at a time in the background. */
    fun enrichPending(context: Context) {
        val lib = Library.get(context)
        val pending = lib.books.filter { !it.metaDone || it.pagesEstimate == 0 }
        val app = context.applicationContext
        if (pending.isEmpty()) {
            OnlineMeta.lookupPending(app)
            return
        }
        lib.io.execute {
            for (entry in pending) {
                val enriched = runCatching { enrich(app, entry) }.getOrElse { entry.copy(metaDone = true) }
                lib.postToMain {
                    lib.update(entry.id) { cur ->
                        cur.copy(
                            title = enriched.title, author = enriched.author, size = enriched.size,
                            docHash = enriched.docHash, hasCover = enriched.hasCover,
                            spineColor = enriched.spineColor, metaDone = true,
                            pagesEstimate = enriched.pagesEstimate.coerceAtLeast(1),
                        )
                    }
                }
            }
            // Local details first; only then fill gaps (covers, authors) from Open Library.
            lib.postToMain { OnlineMeta.lookupPending(app) }
        }
    }

    fun entryForFile(context: Context, uri: Uri, folder: String?): BookEntry? {
        val name = displayName(context, uri) ?: return null
        val format = Format.fromFileName(name) ?: return null
        val title = titleFromName(name)
        return BookEntry(Library.idFor(uri.toString()), uri.toString(), format, title, folder = folder, fileTitle = title)
    }

    private fun scanFolder(context: Context, tree: Uri): List<BookEntry> {
        val out = ArrayList<BookEntry>()
        val cr = context.contentResolver
        fun walk(docId: String, depth: Int) {
            if (depth > 6) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) walk(id, depth + 1)
                        continue
                    }
                    val format = Format.fromFileName(name) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    val title = titleFromName(name)
                    out += BookEntry(Library.idFor(uri), uri, format, title, folder = tree.toString(), fileTitle = title)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    private fun enrich(context: Context, entry: BookEntry): BookEntry {
        val uri = Uri.parse(entry.uri)
        val cr = context.contentResolver
        var result = entry.copy(docHash = partialMd5(context, uri), size = size(context, uri))
        var cover: Bitmap? = null
        when (entry.format) {
            Format.EPUB -> {
                val entries = cr.openInputStream(uri)?.use { Epub.readTextEntries(it) } ?: emptyMap()
                Epub.meta(entries)?.let { meta ->
                    result = result.copy(
                        title = meta.title.ifBlank { entry.title },
                        author = meta.author,
                        pagesEstimate = Epub.estimatePages(meta.textBytes),
                        // A catalog download already carries the entry's categories; keep those if the file has none.
                        subjects = meta.subjects.ifEmpty { result.subjects },
                    )
                    meta.coverPath?.let { path ->
                        val bytes = cr.openInputStream(uri)?.use { Epub.readEntry(it, path) }
                        if (bytes != null) cover = decodeScaled(bytes, COVER_HEIGHT)
                    }
                }
            }
            Format.PDF -> {
                val (bmp, pages) = renderPdfCover(context, uri)
                cover = bmp
                result = result.copy(pagesEstimate = pages)
            }
            Format.TXT -> result = result.copy(pagesEstimate = (result.size / 1500).toInt())
        }
        cover?.let { bmp ->
            Library.get(context).coverFile(entry.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            result = result.copy(hasCover = true, spineColor = spineColorOf(bmp))
            bmp.recycle()
        }
        return result.copy(metaDone = true)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private const val COVER_HEIGHT = 420

    /** "moby_dick.epub" -> "Moby Dick". Names that already use capitals are left as the author wrote them. */
    fun titleFromName(name: String): String {
        val base = name.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { name }
        if (base.any { it.isUpperCase() }) return base
        return base.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun size(context: Context, uri: Uri): Long = runCatching {
        if (uri.scheme == "file") return@runCatching java.io.File(uri.path ?: "").length()
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** KOReader fingerprint over a seekable file descriptor (content:// documents are seekable). */
    private fun partialMd5(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { ch ->
                val size = ch.size()
                PartialMd5.compute { offset, buffer ->
                    if (offset >= size) 0
                    else {
                        val bb = ByteBuffer.wrap(buffer)
                        var total = 0
                        while (bb.hasRemaining()) {
                            val n = ch.read(bb, offset + total)
                            if (n <= 0) break
                            total += n
                        }
                        total
                    }
                }
            }
        }
    }.getOrNull()

    private fun decodeScaled(bytes: ByteArray, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (bmp.height <= targetHeight) return bmp
        val w = (bmp.width * targetHeight.toFloat() / bmp.height).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, targetHeight, true).also { if (it != bmp) bmp.recycle() }
    }

    /** First page as a cover, plus the page count. */
    private fun renderPdfCover(context: Context, uri: Uri): Pair<Bitmap?, Int> {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null to 0
        // PdfRenderer owns the descriptor and closes it with the renderer.
        return PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount == 0) return null to 0
            val count = renderer.pageCount
            val bmp = renderer.openPage(0).use { page ->
                val h = COVER_HEIGHT
                val w = (page.width * h.toFloat() / page.height).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            bmp to count
        }
    }

    /** A muted, dark version of the cover's average colour, so white spine text stays readable. */
    private fun spineColorOf(bmp: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(bmp, 1, 1, true)
        val avg = one.getPixel(0, 0)
        if (one != bmp) one.recycle()
        val hsv = FloatArray(3)
        Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0.18f, 0.5f)
        hsv[2] = hsv[2].coerceIn(0.26f, 0.42f)
        return Color.HSVToColor(hsv)
    }
}
