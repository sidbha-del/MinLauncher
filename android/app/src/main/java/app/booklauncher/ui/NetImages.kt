package app.booklauncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.booklauncher.data.Net
import java.util.concurrent.Executors

/** Catalog thumbnails and covers (data: URIs or network), cached, loaded off the main thread. */
object NetImages {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val workers = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun into(view: ImageView, url: String?, p: InkPalette, maxPx: Int) {
        view.tag = url
        view.colorFilter = p.imageFilter
        if (url.isNullOrBlank()) return
        synchronized(cache) { cache.get(url) }?.let { view.setImageBitmap(it); return }
        workers.execute {
            val bytes = Net.image(url) ?: return@execute
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@execute
            synchronized(cache) { cache.put(url, bmp) }
            main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }
}
