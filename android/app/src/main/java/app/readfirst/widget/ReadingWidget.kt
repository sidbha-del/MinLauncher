package app.readfirst.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.readfirst.HomeActivity
import app.readfirst.R
import app.readfirst.book.Progress
import app.readfirst.data.Focus
import app.readfirst.data.HomeRole
import app.readfirst.data.Library
import app.readfirst.data.Prefs
import app.readfirst.reader.ReaderActivity
import app.readfirst.ui.InkPalette
import java.util.concurrent.Executors

/**
 * ReadFirst on any home screen: the page where you stopped, one tap to continue, and today's
 * reading vs scrolling. For people who aren't ready to replace their launcher; it also offers
 * to make ReadFirst the home screen. Resizes from a 4×2 card to nearly a full page.
 */
class ReadingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        worker.execute {
            try {
                for (id in ids) render(context, manager, id)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val pending = goAsync()
        worker.execute {
            try {
                render(context, manager, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MAKE_HOME = "app.readfirst.MAKE_HOME"
        const val ACTION_FREE_BOOKS = "app.readfirst.FREE_BOOKS"
        private val worker = Executors.newSingleThreadExecutor()

        /** Refreshes every ReadFirst widget; call when the reading position or library changes. */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, ReadingWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            worker.execute { for (id in ids) runCatching { render(app, manager, id) } }
        }

        /** Asks the current home screen to add the widget (Android 8+ launchers that support pinning). */
        fun requestPin(context: Context): Boolean {
            val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(ComponentName(context, ReadingWidget::class.java), null, null)
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = Prefs.get(context)
            val p = InkPalette.of(prefs.ink, prefs.launcherPage)
            val book = Library.get(context).current()
            val v = RemoteViews(context.packageName, R.layout.widget_reading)
            val heightDp = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)

            v.setInt(android.R.id.background, "setBackgroundColor", p.bg)
            for (tid in intArrayOf(R.id.w_excerpt, R.id.w_title, R.id.w_stats)) v.setTextColor(tid, p.text)
            for (tid in intArrayOf(R.id.w_header, R.id.w_author, R.id.w_home)) v.setTextColor(tid, p.soft)
            v.setInt(R.id.w_continue, "setBackgroundColor", p.text)
            v.setTextColor(R.id.w_continue, p.bg)

            // The excerpt grows with the widget: none on a small card, most of a page when tall.
            val lines = ((heightDp - 230) / 22).coerceIn(0, 16)
            v.setViewVisibility(R.id.w_excerpt, if (lines >= 2) View.VISIBLE else View.INVISIBLE)
            v.setInt(R.id.w_excerpt, "setMaxLines", lines.coerceAtLeast(1))

            val open: PendingIntent
            if (book == null) {
                v.setTextViewText(R.id.w_header, "READFIRST")
                v.setTextViewText(R.id.w_excerpt, "Pick a free classic or add your own books, and the page you're on will wait here.")
                v.setTextViewText(R.id.w_title, "No book yet")
                v.setTextViewText(R.id.w_author, "")
                v.setViewVisibility(R.id.w_cover, View.GONE)
                v.setViewVisibility(R.id.w_progress, View.GONE)
                v.setTextViewText(R.id.w_continue, "GET A FREE CLASSIC")
                open = activity(context, 10, Intent(context, HomeActivity::class.java).setAction(ACTION_FREE_BOOKS))
            } else {
                val opened = book.openedAt > 0
                v.setTextViewText(R.id.w_header, "${if (opened) "NOW READING" else "UP NEXT"} · ${Progress.percent(book.progress)}%")
                v.setTextViewText(R.id.w_excerpt, if (opened && book.excerpt.isNotBlank()) "… " + book.excerpt else "A new book. Open it to begin.")
                v.setTextViewText(R.id.w_title, book.title)
                v.setTextViewText(R.id.w_author, book.author)
                val cover = cover(context, book.id, p)
                if (cover != null) {
                    v.setImageViewBitmap(R.id.w_cover, cover)
                    v.setViewVisibility(R.id.w_cover, View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.w_cover, View.GONE)
                }
                v.setImageViewBitmap(R.id.w_progress, progressBar(book.progress, p))
                v.setViewVisibility(R.id.w_progress, View.VISIBLE)
                v.setTextViewText(R.id.w_continue, if (opened) "CONTINUE READING" else "START READING")
                open = activity(context, 11, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, book.id))
            }
            v.setOnClickPendingIntent(R.id.w_continue, open)
            v.setOnClickPendingIntent(R.id.w_excerpt, open)
            v.setOnClickPendingIntent(R.id.w_book, open)

            // Today's mirror: what makes this more than a reading widget.
            val day = runCatching { Focus.computeDay(context, 0) }.getOrNull()
            val stats = when {
                day == null -> ""
                day.hasUsage -> "TODAY  READ ${Focus.format(day.readingMs)} · SCROLL ${Focus.format(day.scrollingMs)}".uppercase()
                else -> "TODAY  READ ${Focus.format(day.readingMs)}".uppercase()
            }
            v.setTextViewText(R.id.w_stats, stats)
            v.setViewVisibility(R.id.w_stats, if (stats.isNotEmpty() && heightDp >= 170) View.VISIBLE else View.GONE)

            // For people using another launcher: the path to the full ReadFirst home screen.
            val isHome = HomeRole.isDefault(context)
            v.setViewVisibility(R.id.w_home, if (!isHome && heightDp >= 200) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.w_home, activity(context, 12,
                Intent(context, HomeActivity::class.java).setAction(ACTION_MAKE_HOME)))
            // The free-books starter shelf (Gutenberg classics, then the full catalogs).
            v.setViewVisibility(R.id.w_free, if (heightDp >= 170) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.w_free, activity(context, 13,
                Intent(context, HomeActivity::class.java).setAction(ACTION_FREE_BOOKS)))

            manager.updateAppWidget(id, v)
        }

        private fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun cover(context: Context, id: String, p: InkPalette): Bitmap? {
            val file = Library.get(context).coverFile(id)
            if (!file.exists()) return null
            val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return null
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = p.imageFilter })
            src.recycle()
            return out
        }

        /** The segmented progress bar from Home, as a small image (widgets can't draw custom views). */
        private fun progressBar(fraction: Float, p: InkPalette): Bitmap {
            val segments = 20
            val w = 800
            val h = 14
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val gap = 6f
            val segW = (w - gap * (segments - 1)) / segments
            val filled = if (fraction <= 0f) 0 else maxOf(1, (fraction * segments).toInt())
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.fill }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = p.text }
            for (i in 0 until segments) {
                val x = i * (segW + gap)
                if (i < filled) c.drawRect(x, 0f, x + segW, h.toFloat(), fill)
                else c.drawRect(RectF(x + 1, 1f, x + segW - 1, h - 1f), stroke)
            }
            return bmp
        }
    }
}
