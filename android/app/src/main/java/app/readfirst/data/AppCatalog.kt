package app.readfirst.data

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.text.Collator
import java.util.concurrent.Executors

/** Installed launchable apps, with icons loaded off the main thread into a memory-sized cache. */
class AppCatalog private constructor(context: Context) {
    private val app = context.applicationContext
    private val pm = app.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    data class Entry(val key: String, val label: String, val intent: Intent, val component: ComponentName)

    @Volatile
    var apps: List<Entry> = emptyList()
        private set

    private val icons: LruCache<String, Bitmap> = run {
        val am = app.getSystemService(ActivityManager::class.java)
        val bytes = am.memoryClass * 1024 * 1024 / 16
        object : LruCache<String, Bitmap>(bytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    val isLowRam: Boolean = app.getSystemService(ActivityManager::class.java).isLowRamDevice

    init {
        reload()
    }

    fun reload() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val list = ArrayList<Entry>()
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == app.packageName) continue
            val component = ComponentName(ai.packageName, ai.name)
            if (!seen.add(component.flattenToString())) continue
            list += Entry(
                key = component.flattenToString(),
                label = ri.loadLabel(pm).toString().trim(),
                intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                component = component,
            )
        }
        val collator = Collator.getInstance()
        apps = list.sortedWith { a, b -> collator.compare(a.label, b.label) }
        synchronized(icons) { icons.evictAll() }
    }

    fun launch(context: Context, entry: Entry): Boolean = try {
        context.startActivity(entry.intent)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Loads the app's icon off the main thread, adapted to the ink: muted colour for Color ink;
     * for Black ink, Android's own monochrome (themed-icon) layer drawn in black on white when
     * the app ships one, otherwise a high-contrast grayscale icon. [onReady] runs on the main thread.
     */
    /** [dark]: a Night page, where Black ink icons invert to a light glyph on a dark tile. */
    fun loadIcon(entry: Entry, sizePx: Int, ink: Ink, dark: Boolean = false, onReady: (Bitmap) -> Unit) {
        val cacheKey = "${entry.key}@$sizePx@$ink@$dark"
        synchronized(icons) { icons.get(cacheKey) }?.let { onReady(it); return }
        worker.execute {
            val drawable: Drawable = try {
                pm.getActivityIcon(entry.component)
            } catch (e: Exception) {
                runCatching { pm.getApplicationIcon(entry.component.packageName) }.getOrNull() ?: return@execute
            }
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val mono = if (ink == Ink.BLACK && Build.VERSION.SDK_INT >= 33 && drawable is AdaptiveIconDrawable) drawable.monochrome else null
            if (mono != null) drawMonochrome(canvas, mono, sizePx, dark) else {
                drawable.mutate().colorFilter = filterFor(ink)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
            }
            synchronized(icons) { icons.put(cacheKey, bmp) }
            main.post { onReady(bmp) }
        }
    }

    /** Ink glyph on a page-coloured rounded square with an ink outline: the Black ink icon style. */
    private fun drawMonochrome(canvas: Canvas, mono: Drawable, size: Int, dark: Boolean) {
        val ink = if (dark) Color.WHITE else Color.BLACK
        val paper = if (dark) Color.BLACK else Color.WHITE
        val stroke = size * 0.06f
        val radius = size * 0.24f
        val box = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
        canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) })
        // Adaptive layers are 108 units with the glyph inside the central 66; enlarge so it fills the tile.
        val bleed = (size * 0.2f).toInt()
        mono.mutate().setTint(ink)
        mono.setBounds(-bleed, -bleed, size + bleed, size + bleed)
        mono.draw(canvas)
        canvas.restore()
        canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = ink
        })
    }

    private fun filterFor(ink: Ink) = ColorMatrixColorFilter(ColorMatrix().apply {
        if (ink == Ink.BLACK) {
            setSaturation(0f)
            val c = 1.25f
            val t = (1 - c) * 128
            postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        } else {
            setSaturation(0.8f)
        }
    })

    companion object {
        @Volatile
        private var instance: AppCatalog? = null

        fun get(context: Context): AppCatalog =
            instance ?: synchronized(this) { instance ?: AppCatalog(context).also { instance = it } }
    }
}
