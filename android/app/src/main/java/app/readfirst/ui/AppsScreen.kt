package app.readfirst.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.data.AppCatalog
import app.readfirst.data.Focus
import app.readfirst.data.NowPlaying
import app.readfirst.data.Prefs
import java.text.Normalizer
import java.util.Locale

/** All apps, A to Z, with search. Tap to open, long-press for actions. */
class AppsScreen(host: HomeActivity) : Screen(host) {
    private var query = ""

    override fun build(): View {
        val col = ui.vertical()
        val all = visibleApps()
        col.addView(ui.topBar("Apps", "${all.size}", onRight = { host.push(SettingsScreen(host)) },
            rightIcon = app.readfirst.R.drawable.ic_settings, rightIconLabel = "Settings"))

        val search = EditText(host).apply {
            hint = "SEARCH APPS"
            setHintTextColor(p.soft)
            setTextColor(p.text)
            typeface = Fonts.mono
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11))
        }
        col.addView(search)
        ui.margins(search, 18, 12, 18, 6)

        val adapter = AppAdapter()
        val list = ListView(host).apply {
            divider = null
            selector = ui.tapBackground()
            isFastScrollEnabled = true
            isVerticalScrollBarEnabled = false
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ -> (adapter.getItem(pos) as? AppCatalog.Entry)?.let { launch(it) } }
            setOnItemLongClickListener { _, _, pos, _ ->
                (adapter.getItem(pos) as? AppCatalog.Entry)?.let { AppActions.show(host, it) }
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard(search)
                }
                override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {}
            })
        }
        adapter.submit(all)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                adapter.submit(filter(all, query))
            }
        })
        search.setOnEditorActionListener { _, action, event ->
            val go = action == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) filter(all, query).firstOrNull()?.let { launch(it) }
            go
        }
        col.addView(list, ui.lp(h = 0, weight = 1f))
        col.addView(ui.navBar(
            "← Library" to { host.push(LibraryScreen(host)) },
            "Home ↓" to { host.goHome() },
        ))
        return col
    }

    override fun onHide() {
        (host.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
    }

    private fun launch(app: AppCatalog.Entry) {
        host.launchApp(app)
    }

    private fun hideKeyboard(v: View) {
        host.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun visibleApps(): List<AppCatalog.Entry> {
        val hidden = host.prefs.hidden
        val names = host.prefs.renamed
        return host.catalog.apps.filter { it.key !in hidden }
            .map { e -> names[e.key]?.let { e.copy(label = it) } ?: e }
            .sortedBy { fold(it.label) }
    }

    private fun filter(all: List<AppCatalog.Entry>, q: String): List<AppCatalog.Entry> {
        val needle = fold(q.trim())
        if (needle.isEmpty()) return all
        // Word-start matches first, then anywhere.
        val starts = all.filter { e -> fold(e.label).split(' ').any { it.startsWith(needle) } }
        val contains = all.filter { it !in starts && fold(it.label).contains(needle) }
        return starts + contains
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    private fun letterOf(label: String): String {
        val c = fold(label).firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    /** Rows are either a letter header (String) or an app (Entry). */
    private inner class AppAdapter : BaseAdapter(), SectionIndexer {
        private var rows: List<Any> = emptyList()
        private var sections: Array<String> = emptyArray()
        private var sectionStart: IntArray = IntArray(0)
        private val iconSize = ui.dp(30)

        fun submit(apps: List<AppCatalog.Entry>) {
            val out = ArrayList<Any>()
            val secs = ArrayList<String>()
            val starts = ArrayList<Int>()
            val grouped = query.isBlank()
            var last: String? = null
            for (a in apps) {
                val l = letterOf(a.label)
                if (grouped && l != last) {
                    secs += l
                    starts += out.size
                    out += l
                    last = l
                }
                out += a
            }
            rows = out
            sections = secs.toTypedArray()
            sectionStart = starts.toIntArray()
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun isEnabled(position: Int) = rows[position] !is String

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = rows[position]
            if (item is String) {
                val v = (convertView as? LinearLayout) ?: ui.vertical().apply {
                    addView(ui.mono("", 10f).apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(4)) })
                    addView(ui.rule())
                }
                ((v.getChildAt(0)) as TextView).text = item
                return v
            }
            val app = item as AppCatalog.Entry
            val row = (convertView as? LinearLayout) ?: ui.horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9))
                addView(ImageView(host), LinearLayout.LayoutParams(iconSize, iconSize))
                addView(ui.text("", 16.5f).apply { maxLines = 1 }, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
            }
            val icon = row.getChildAt(0) as ImageView
            val label = row.getChildAt(1) as TextView
            label.text = app.label
            icon.setImageDrawable(null)
            icon.tag = app.key
            host.catalog.loadIcon(app, iconSize, p.ink, p.dark) { bmp -> if (icon.tag == app.key) icon.setImageBitmap(bmp) }
            row.contentDescription = app.label
            return row
        }

        override fun getSections(): Array<Any> = sections.map { it as Any }.toTypedArray()
        override fun getPositionForSection(section: Int) = sectionStart.getOrElse(section) { 0 }
        override fun getSectionForPosition(position: Int): Int {
            var s = 0
            for (i in sectionStart.indices) if (sectionStart[i] <= position) s = i
            return s
        }
    }
}

/** Long-press on an app: pin to home, rename, hide, app info, uninstall. */
object AppActions {
    fun show(host: HomeActivity, app: AppCatalog.Entry) {
        val ui = host.ui
        val p = ui.p
        val prefs = host.prefs
        val col = ui.vertical()
        val head = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
        }
        val icon = ImageView(host)
        head.addView(icon, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
        host.catalog.loadIcon(app, ui.dp(36), p.ink, p.dark) { icon.setImageBitmap(it) }
        head.addView(ui.text(prefs.label(app.key, app.label), 17f, face = Fonts.sansBold), ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(12) })
        col.addView(head)
        col.addView(ui.rule())

        val pinned = prefs.pinned
        if (app.key in pinned) {
            col.addView(ui.row("Unpin from home") {
                prefs.pinned = pinned - app.key
                host.dismissSheet()
            })
        } else if (pinned.size < Prefs.MAX_PINS) {
            col.addView(ui.row("Pin to home", "${pinned.size} / ${Prefs.MAX_PINS} used") {
                prefs.pinned = pinned + app.key
                host.dismissSheet()
            })
        } else {
            col.addView(ui.row("Pin to home", "Home is full (${Prefs.MAX_PINS})", off = true))
        }
        val pkg = app.component.packageName
        val distracting = Focus.isDistracting(prefs, host.catalog, pkg)
        col.addView(ui.row(if (distracting) "Don't pause before opening" else "Pause before opening (distracting)") {
            Focus.setDistracting(prefs, host.catalog, pkg, !distracting)
            host.dismissSheet()
        })
        val work = Focus.isWork(host, prefs, pkg)
        col.addView(ui.row(if (work) "Don't count as work" else "Count as work", "Home stats") {
            Focus.setWork(host, prefs, pkg, !work)
            host.dismissSheet()
        })
        val audiobook = NowPlaying.isAudiobookApp(host, pkg)
        val builtIn = pkg in NowPlaying.AUDIOBOOK_APPS
        if (!builtIn) col.addView(ui.row(if (audiobook) "Not an audiobook app" else "Audiobook app: listening counts as reading") {
            prefs.audiobookAdded = if (audiobook) prefs.audiobookAdded - pkg else prefs.audiobookAdded + pkg
            host.dismissSheet()
        })
        col.addView(ui.row("Rename") { rename(host, app) })
        col.addView(ui.row("Hide from list") {
            prefs.hidden = prefs.hidden + app.key
            prefs.pinned = prefs.pinned - app.key
            host.dismissSheet()
        })
        col.addView(ui.row("App info") {
            host.dismissSheet()
            runCatching {
                host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null)))
            }
        })
        col.addView(ui.row("Uninstall", warn = true) {
            host.dismissSheet()
            runCatching {
                host.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null)))
            }
        })
        host.showSheet(col)
    }

    private fun rename(host: HomeActivity, app: AppCatalog.Entry) {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        col.addView(ui.mono("Rename ${app.label}", 11f).also { ui.margins(it, 18, 16, 18, 10) })
        val field = EditText(host).apply {
            setText(host.prefs.label(app.key, app.label))
            setTextColor(p.text)
            textSize = 16f
            isSingleLine = true
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            selectAll()
        }
        col.addView(field)
        ui.margins(field, 18, 0, 18, 12)
        val row = ui.horizontal()
        row.addView(ui.boxButton("Reset") {
            host.prefs.renamed = host.prefs.renamed - app.key
            host.dismissSheet()
        }, ui.lp(0, weight = 1f).apply { rightMargin = ui.dp(6) })
        row.addView(ui.boxButton("Save") {
            val name = field.text.toString().trim()
            host.prefs.renamed = if (name.isEmpty() || name == app.label) host.prefs.renamed - app.key else host.prefs.renamed + (app.key to name)
            host.dismissSheet()
        }, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(6) })
        col.addView(row)
        ui.margins(row, 18, 0, 18, 0)
        host.showSheet(col)
        field.requestFocus()
        host.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }
}
