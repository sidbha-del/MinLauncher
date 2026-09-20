package app.readfirst.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Ink { COLOR, BLACK }

/**
 * What the ink is printed on. DEFAULT keeps each ink's own page (paper for Color ink, pure
 * white for Black ink); NIGHT is a dark page, which turns the ink light.
 */
enum class Page(val label: String, val color: Int, val dark: Boolean) {
    DEFAULT("Default", 0, false),
    WHITE("White", 0xFFFFFFFF.toInt(), false),
    PAPER("Paper", 0xFFF4F1EA.toInt(), false),
    SEPIA("Sepia", 0xFFF1E4CA.toInt(), false),
    MIST("Mist", 0xFFE5E8E6.toInt(), false),
    NIGHT("Night", 0xFF191814.toInt(), true),
}

enum class Texture(val label: String) { NONE("None"), GRAIN("Grain"), LINEN("Linen") }

/** How books are ordered within each shelf. RECENT is the reading order: most recently touched first. */
enum class LibrarySort(val label: String) {
    RECENT("Recent"), TITLE("Title"), AUTHOR("Author"), SUBJECT("Subject")
}

/** A page colour plus texture; the launcher has one, and books either share it or have their own. */
data class PageStyle(val page: Page, val texture: Texture)

/** Small user settings. Listeners fire on the main thread after any change. */
class Prefs private constructor(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val listeners = ArrayList<() -> Unit>()

    var ink: Ink
        get() = runCatching { Ink.valueOf(sp.getString(KEY_INK, null) ?: "") }.getOrDefault(Ink.COLOR)
        set(v) = edit { putString(KEY_INK, v.name) }

    /** Reader text size in sp. */
    var textSize: Int
        get() = sp.getInt("text_size", 18)
        set(v) = edit { putInt("text_size", v.coerceIn(MIN_TEXT, MAX_TEXT)) }

    var serif: Boolean
        get() = sp.getBoolean("serif", true)
        set(v) = edit { putBoolean("serif", v) }

    /** Reader side margin step, 0..3. */
    var margin: Int
        get() = sp.getInt("margin", 1)
        set(v) = edit { putInt("margin", v.coerceIn(0, 3)) }

    var launcherPage: PageStyle
        get() = PageStyle(enumOr("launcher_page", Page.DEFAULT), enumOr("launcher_texture", Texture.NONE))
        set(v) = edit { putString("launcher_page", v.page.name); putString("launcher_texture", v.texture.name) }

    /** True: books use the launcher's page. False: books have their own page. */
    var booksShareLauncherPage: Boolean
        get() = sp.getBoolean("books_share_page", true)
        set(v) = edit { putBoolean("books_share_page", v) }

    var booksOwnPage: PageStyle
        get() = PageStyle(enumOr("books_page", Page.DEFAULT), enumOr("books_texture", Texture.NONE))
        set(v) = edit { putString("books_page", v.page.name); putString("books_texture", v.texture.name) }

    /** The page the reader actually uses. */
    val readerPage: PageStyle get() = if (booksShareLauncherPage) launcherPage else booksOwnPage

    /** Changes whichever page the reader is using: the shared one, or the books' own. */
    fun setReaderPage(v: PageStyle) {
        if (booksShareLauncherPage) launcherPage = v else booksOwnPage = v
    }

    /** PDF: crop the blank print margins so text is larger on a phone. */
    var pdfTrim: Boolean
        get() = sp.getBoolean("pdf_trim", true)
        set(v) = edit { putBoolean("pdf_trim", v) }

    /** PDF: keep the file's own colours instead of taking on the page colour (Night, Sepia…). */
    var pdfOriginalColours: Boolean
        get() = sp.getBoolean("pdf_original_colours", false)
        set(v) = edit { putBoolean("pdf_original_colours", v) }

    /** Library layout: false = spines on shelves, true = books stacked flat (full titles). */
    var libraryStack: Boolean
        get() = sp.getBoolean("library_stack", true)
        set(v) = edit { putBoolean("library_stack", v) }

    var librarySort: LibrarySort
        get() = enumOr("library_sort", LibrarySort.RECENT)
        set(v) = edit { putString("library_sort", v.name) }

    /** Now listening: false = audiobook apps only, true = any audio (music, podcasts). */
    var nowPlayingAllAudio: Boolean
        get() = sp.getBoolean("now_playing_all_audio", false)
        set(v) = edit { putBoolean("now_playing_all_audio", v) }

    /** The user said "Not now" to showing audiobooks on Home; don't ask again. */
    var nowPlayingPromptDismissed: Boolean
        get() = sp.getBoolean("now_playing_prompt_dismissed", false)
        set(v) = edit { putBoolean("now_playing_prompt_dismissed", v) }

    /** Fill missing covers, authors and titles from Open Library (sends book titles only). */
    var onlineMeta: Boolean
        get() = sp.getBoolean("online_meta", true)
        set(v) = edit { putBoolean("online_meta", v) }

    /** User catalogs and built-in catalog sign-ins, as a JSON array (see [Catalogs]). */
    var catalogsJson: String
        get() = sp.getString("catalogs", "[]") ?: "[]"
        set(v) = edit { putString("catalogs", v) }

    /** Pause before opening distracting apps from the home screen. */
    var pauseEnabled: Boolean
        get() = sp.getBoolean("pause_enabled", true)
        set(v) = edit { putBoolean("pause_enabled", v) }

    /** Package names the user treats as distracting; null until they've changed the suggested list. */
    var distracting: Set<String>?
        get() = if (sp.contains("distracting")) readList("distracting").toSet() else null
        set(v) = edit { if (v == null) remove("distracting") else putString("distracting", JSONArray(v.sorted()).toString()) }

    /** Package names the user added to / removed from Work, on top of the built-in guess. */
    var workAdded: Set<String>
        get() = readList("work_added").toSet()
        set(v) = edit { putString("work_added", JSONArray(v.sorted()).toString()) }
    var workRemoved: Set<String>
        get() = readList("work_removed").toSet()
        set(v) = edit { putString("work_removed", JSONArray(v.sorted()).toString()) }

    /** Apps the user marked as audiobook apps: their playback counts as reading. */
    var audiobookAdded: Set<String>
        get() = readList("audiobook_added").toSet()
        set(v) = edit { putString("audiobook_added", JSONArray(v.sorted()).toString()) }

    /** One-time Home notices the user has closed, by id. */
    var dismissedNotices: Set<String>
        get() = readList("dismissed_notices").toSet()
        set(v) = edit { putString("dismissed_notices", JSONArray(v.sorted()).toString()) }

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = edit { putBoolean("setup_done", v) }

    /** Pinned app keys (component strings), in order, at most [MAX_PINS]. */
    var pinned: List<String>
        get() = readList("pinned")
        set(v) = edit { putString("pinned", JSONArray(v.distinct().take(MAX_PINS)).toString()) }

    var hidden: Set<String>
        get() = readList("hidden").toSet()
        set(v) = edit { putString("hidden", JSONArray(v.sorted()).toString()) }

    var renamed: Map<String, String>
        get() = runCatching {
            val o = JSONObject(sp.getString("renamed", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = edit { putString("renamed", JSONObject(v.filterValues { it.isNotBlank() }).toString()) }

    fun label(key: String, fallback: String): String = renamed[key] ?: fallback

    fun toggleInk() {
        ink = if (ink == Ink.COLOR) Ink.BLACK else Ink.COLOR
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private inline fun <reified E : Enum<E>> enumOr(key: String, fallback: E): E =
        runCatching { enumValueOf<E>(sp.getString(key, null) ?: "") }.getOrDefault(fallback)

    private fun readList(key: String): List<String> = runCatching {
        val a = JSONArray(sp.getString(key, "[]") ?: "[]")
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        listeners.toList().forEach { it() }
    }

    companion object {
        private const val KEY_INK = "ink"
        const val MAX_PINS = 4
        const val MIN_TEXT = 12
        const val MAX_TEXT = 32

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context).also { instance = it } }
    }
}
