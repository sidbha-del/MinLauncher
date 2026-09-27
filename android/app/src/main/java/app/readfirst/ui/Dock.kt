package app.readfirst.ui

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.readfirst.HomeActivity
import app.readfirst.data.AppCatalog
import app.readfirst.data.Focus
import app.readfirst.data.Prefs

/**
 * Pinned apps, shared by every page of the pager: always on Home, and on Library/Apps too once
 * the user opts in via [Prefs.dockEverywhere]. Never shown in the reader, since that's a separate
 * activity this view is never part of. Empty slots show a "+" that opens a picker directly, so
 * building a dock doesn't require detouring through the Apps list's long-press menu.
 */
object Dock {
    fun build(host: HomeActivity): View {
        val ui = host.ui
        val p = ui.p
        val byKey = host.catalog.apps.associateBy { it.key }
        val apps = host.prefs.pinned.mapNotNull { byKey[it] }
        val row = ui.horizontal()
        val size = ui.dp(36)
        for (app in apps.take(Prefs.MAX_PINS)) {
            val cell = ui.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(10), 0, ui.dp(8))
            }
            val icon = ImageView(host)
            cell.addView(icon, LinearLayout.LayoutParams(size, size))
            host.catalog.loadIcon(app, size, p.ink, p.dark) { icon.setImageBitmap(it) }
            cell.addView(ui.text(host.prefs.label(app.key, app.label), 12f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }.also { ui.margins(it, 4, 6, 4, 0) })
            ui.tappable(cell, { host.launchApp(app) }, { AppActions.show(host, app) })
            cell.contentDescription = app.label
            row.addView(cell, ui.lp(0, weight = 1f))
        }
        repeat((Prefs.MAX_PINS - apps.size).coerceAtLeast(0)) {
            val cell = ui.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(10), 0, ui.dp(8))
            }
            cell.addView(ui.box(0, dashed = true).let { box ->
                ui.text("+", 16f, p.soft).apply {
                    gravity = Gravity.CENTER
                    background = box
                }
            }, LinearLayout.LayoutParams(size, size))
            cell.addView(ui.text("Add", 12f, p.soft).apply {
                maxLines = 1
                gravity = Gravity.CENTER
            }.also { ui.margins(it, 4, 6, 4, 0) })
            ui.tappable(cell, { pickAppSheet(host) })
            cell.contentDescription = "Add a pinned app"
            row.addView(cell, ui.lp(0, weight = 1f))
        }
        return ui.vertical().apply {
            addView(ui.rule(soft = true))
            addView(row)
            ui.margins(this, 18, 20, 18, 0)
        }
    }

    /** A quick search-and-pin sheet, so filling an empty dock slot doesn't require finding the
     *  app in the full list first and long-pressing it. */
    private fun pickAppSheet(host: HomeActivity) {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(8)) }
        col.addView(ui.mono("Pin to home", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        val search = EditText(host).apply {
            hint = "SEARCH APPS"
            setHintTextColor(p.soft)
            setTextColor(p.text)
            typeface = Fonts.mono
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11))
        }
        col.addView(search)
        ui.margins(search, 18, 0, 18, 6)

        val listHolder = ui.vertical()
        val scroll = ScrollView(host).apply { addView(listHolder) }
        col.addView(scroll, ui.lp(h = ui.dp(340)))

        fun candidates(query: String): List<AppCatalog.Entry> {
            val hidden = host.prefs.hidden
            val pinned = host.prefs.pinned.toSet()
            val needle = query.trim()
            return host.catalog.apps
                .filter { it.key !in hidden && it.key !in pinned }
                .filter { needle.isEmpty() || it.label.contains(needle, ignoreCase = true) }
                .sortedBy { it.label }
        }

        fun refresh(query: String) {
            listHolder.removeAllViews()
            val found = candidates(query)
            if (found.isEmpty()) {
                listHolder.addView(ui.text("No apps found.", 14f, p.soft).also { ui.margins(it, 18, 16, 18, 16) })
            }
            for (app in found) {
                listHolder.addView(ui.row(host.prefs.label(app.key, app.label)) { pin(host, app) })
            }
        }
        refresh("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString().orEmpty()) }
        })
        host.showSheet(col)
    }

    /**
     * Pinning is one tap further for an app already flagged distracting — a nudge, not a block,
     * so the dock stays the handful of apps you actually need at a glance rather than quietly
     * gaining the ones the rest of the app is trying to help you open less.
     */
    fun pin(host: HomeActivity, app: AppCatalog.Entry) {
        val prefs = host.prefs
        if (!Focus.isDistracting(prefs, host.catalog, app.component.packageName)) {
            prefs.pinned = prefs.pinned + app.key
            host.dismissSheet()
            return
        }
        // The picker sheet that led here keeps the keyboard open for its search box; replacing
        // it in place left the nudge rendered behind the still-closing keyboard. Closing first —
        // which detaches the focused search field and lets the keyboard actually go down — then
        // opening the nudge a beat later avoids the race.
        host.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
        host.dismissSheet()
        Handler(Looper.getMainLooper()).postDelayed({ showDistractingNudge(host, app) }, 120)
    }

    private fun showDistractingNudge(host: HomeActivity, app: AppCatalog.Entry) {
        val prefs = host.prefs
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        col.addView(ui.mono("Pin ${app.label}?", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.text(
            "${app.label} is flagged as distracting. The dock is for the handful of apps you actually need at a glance — not the ones you're trying to open less.",
            14f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 0, 18, 12) })
        val row = ui.horizontal()
        row.addView(ui.boxButton("Choose another") { host.dismissSheet() }, ui.lp(0, weight = 1f).apply { rightMargin = ui.dp(6) })
        row.addView(ui.boxButton("Pin anyway") {
            prefs.pinned = prefs.pinned + app.key
            host.dismissSheet()
        }, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(6) })
        col.addView(row)
        ui.margins(row, 18, 0, 18, 0)
        host.showSheet(col)
    }
}
