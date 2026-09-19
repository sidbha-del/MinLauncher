package app.booklauncher.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * A PDF opened with the platform PdfRenderer. The renderer is not thread-safe, so every call
 * goes through one worker thread; rendered pages come back on the main thread.
 */
class PdfDoc private constructor(private val renderer: PdfRenderer) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val contentBounds = HashMap<Int, RectF>()
    @Volatile
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /**
     * Renders [page] to fit within [maxW] x [maxH], keeping its aspect ratio. With [trim], blank
     * print margins are cropped first, so the text fills more of a phone screen.
     */
    fun render(page: Int, maxW: Int, maxH: Int, trim: Boolean, onReady: (Bitmap) -> Unit) {
        worker.execute {
            if (closed) return@execute
            val bmp = runCatching {
                val index = page.coerceIn(0, pageCount - 1)
                renderer.openPage(index).use { pg ->
                    val full = RectF(0f, 0f, pg.width.toFloat(), pg.height.toFloat())
                    val crop = if (trim) contentBounds.getOrPut(index) { findContent(pg) } else full
                    val scale = minOf(maxW / crop.width(), maxH / crop.height())
                    val w = (crop.width() * scale).toInt().coerceAtLeast(1)
                    val h = (crop.height() * scale).toInt().coerceAtLeast(1)
                    val m = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(-crop.left * scale, -crop.top * scale)
                    }
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        pg.render(it, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull() ?: return@execute
            main.post { if (!closed) onReady(bmp) }
        }
    }

    /** The printed area of a page, in page units, from a small preview render; the whole page if blank. */
    private fun findContent(pg: PdfRenderer.Page): RectF {
        val full = RectF(0f, 0f, pg.width.toFloat(), pg.height.toFloat())
        val s = PROBE_WIDTH / pg.width.toFloat()
        val w = PROBE_WIDTH
        val h = (pg.height * s).toInt().coerceAtLeast(1)
        val probe = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        probe.eraseColor(Color.WHITE)
        pg.render(probe, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val px = IntArray(w * h)
        probe.getPixels(px, 0, w, 0, 0, w, h)
        probe.recycle()
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            if (lum < 225) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return full
        val pad = 0.02f * w
        val r = RectF(
            ((left - pad) / s).coerceAtLeast(0f), ((top - pad) / s).coerceAtLeast(0f),
            ((right + 1 + pad) / s).coerceAtMost(full.right), ((bottom + 1 + pad) / s).coerceAtMost(full.bottom),
        )
        // A tiny mark (a page number alone) isn't worth zooming into.
        return if (r.width() < full.width() * 0.3f || r.height() < full.height() * 0.2f) full else r
    }

    fun close() {
        closed = true
        worker.execute { runCatching { renderer.close() } }
        worker.shutdown()
    }

    companion object {
        private const val PROBE_WIDTH = 200

        /** Blocking: call off the main thread. The renderer takes ownership of the descriptor. */
        fun open(context: Context, uri: Uri): PdfDoc {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Can't open file")
            return PdfDoc(PdfRenderer(pfd))
        }
    }
}
