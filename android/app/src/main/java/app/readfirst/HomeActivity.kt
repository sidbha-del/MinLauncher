package app.readfirst

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import app.readfirst.data.AppCatalog
import app.readfirst.data.BookEntry
import app.readfirst.data.Library
import app.readfirst.data.NowPlaying
import app.readfirst.data.Prefs
import app.readfirst.data.Scanner
import app.readfirst.reader.ReaderActivity
import app.readfirst.ui.HomeScreen
import app.readfirst.ui.InkPalette
import app.readfirst.ui.PageLook
import app.readfirst.ui.Screen
import app.readfirst.ui.setBarIcons
import app.readfirst.ui.SetupScreen
import app.readfirst.ui.Ui
import app.readfirst.ui.applyEdgeToEdgeInsets
import app.readfirst.ui.attachSheet

/**
 * The launcher's only home activity. Screens are plain views swapped inside one window; sheets
 * slide over the current screen. Reading happens in [ReaderActivity].
 */
class HomeActivity : Activity() {
    lateinit var prefs: Prefs
        private set
    lateinit var library: Library
        private set
    lateinit var catalog: AppCatalog
        private set
    lateinit var ui: Ui
        private set

    private lateinit var root: FrameLayout
    private val stack = ArrayDeque<Screen>()
    private var sheet: View? = null
    private var lastScan = 0L
    private var rebuildPosted = false
    private var visible = false

    private val onPrefs: () -> Unit = { scheduleRebuild(inkMayChange = true) }
    private val onLibrary: () -> Unit = { scheduleRebuild() }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            stack.lastOrNull()?.onTimeTick()
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            catalog.reload()
            scheduleRebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        library = Library.get(this)
        catalog = AppCatalog.get(this)
        ui = makeUi()
        root = FrameLayout(this)
        setContentView(root)
        applyEdgeToEdgeInsets(root)
        paintWindow()
        stack.addLast(HomeScreen(this))
        if (!prefs.setupDone) {
            pinDefaultCommsApps()
            stack.addLast(SetupScreen(this))
        }
        render(animate = false)
        handleWidgetAction(intent)

        prefs.addListener(onPrefs)
        library.addListener(onLibrary)
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        prefs.removeListener(onPrefs)
        library.removeListener(onLibrary)
        unregisterReceiver(timeReceiver)
        unregisterReceiver(packageReceiver)
        stack.forEach { it.onHide() }
        super.onDestroy()
    }

    /**
     * Switching the home screen away from the phone's stock launcher raises one real worry: "how do
     * I still make a call or read a text?" Answer it by pinning the phone's actual dialer and
     * messaging apps to Home before the user has discovered "Pin to home" themselves — not just
     * leaving them reachable via the app drawer.
     */
    private fun pinDefaultCommsApps() {
        if (prefs.pinned.isNotEmpty()) return
        val dialerPkg = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        val smsPkg = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val keys = listOfNotNull(dialerPkg, smsPkg).distinct()
            .mapNotNull { pkg -> catalog.apps.firstOrNull { it.component.packageName == pkg }?.key }
        if (keys.isNotEmpty()) prefs.pinned = keys
    }

    /**
     * The window can change size without the activity restarting: a rotation, a split-screen
     * divider being dragged, a desktop-mode window. The manifest opts into handling those itself
     * (configChanges), so nothing re-reads the layout unless we do it here — and content measured
     * for the old window would otherwise stay that size and be clipped by the new one.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.getChildAt(0)?.layoutParams = ui.rootParams()
    }

    private var listeningKey: String? = null

    /** Rebuild only when what's playing changes, not on every position tick. */
    private val onNowPlaying: () -> Unit = {
        val s = NowPlaying.get(this).current(prefs.nowPlayingAllAudio)
        val key = s?.let { "${it.controller.packageName}|${it.title}|${it.subtitle}|${it.playing}|${it.art != null}|${it.durationMs > 0}" }
        if (key != listeningKey) {
            listeningKey = key
            scheduleRebuild()
        }
    }

    override fun onPause() {
        visible = false
        NowPlaying.get(this).stop()
        app.readfirst.widget.ReadingWidget.updateAll(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        NowPlaying.get(this).start(onNowPlaying)
        // Reading position, progress and ink may have changed while the reader was open.
        if (lookChanged()) {
            ui = makeUi()
            paintWindow()
        }
        if (sheet == null) rebuild()
        if (SystemClock.elapsedRealtime() - lastScan > 5 * 60_000L) {
            lastScan = SystemClock.elapsedRealtime()
            Scanner.refresh(this)
        }
    }

    // ---- navigation ---------------------------------------------------------------------------

    fun push(screen: Screen) {
        dismissSheet()
        stack.lastOrNull()?.onHide()
        stack.addLast(screen)
        render()
    }

    fun pop() {
        if (stack.size <= 1) return
        dismissSheet()
        stack.removeLast().onHide()
        render()
    }

    fun goHome() {
        dismissSheet()
        while (stack.size > 1) stack.removeLast().onHide()
        render()
    }

    fun rebuild() = render(animate = false)

    /** Async work (a catalog page loading) only redraws if its screen is still the one showing. */
    fun isTop(screen: Screen) = stack.lastOrNull() === screen && sheet == null

    private fun scheduleRebuild(inkMayChange: Boolean = false) {
        if (rebuildPosted) return
        rebuildPosted = true
        root.post {
            rebuildPosted = false
            if (inkMayChange && lookChanged()) {
                ui = makeUi()
                paintWindow()
            }
            // Rebuilding under an open sheet would detach it (closing a sheet rebuilds), and
            // while hidden behind the reader onResume rebuilds instead.
            if (sheet == null && visible) rebuild()
        }
    }

    private fun render(animate: Boolean = true) {
        val screen = stack.lastOrNull() ?: return
        val view = screen.build()
        root.removeAllViews()
        root.addView(view, ui.rootParams())
        sheet = null
        if (animate && ui.p.animate) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(if (catalog.isLowRam) 90 else 140).withLayer().start()
        }
    }

    private fun makeUi() = Ui(this, InkPalette.of(prefs.ink, prefs.launcherPage))

    private fun lookChanged() = ui.p.ink != prefs.ink || ui.p.style != prefs.launcherPage

    private fun paintWindow() {
        window.decorView.setBackgroundColor(ui.p.bg)
        root.background = PageLook.background(this, ui.p)
        setBarIcons(this, dark = ui.p.dark)
    }

    // ---- sheets -------------------------------------------------------------------------------

    /** Slides [content] up from the bottom over a dimmed screen; tapping the dim area closes it. */
    fun showSheet(content: View) {
        if (sheet != null) {
            root.removeView(sheet)
            sheet = null
        }
        // A sheet rises into the space the keyboard occupies, so opening one straight from the
        // app search — searching for YouTube, say, and being offered your book instead — left the
        // sheet behind the keyboard, with only a dimmed screen to show for it. Sheets that do want
        // typing, like renaming an app, ask for the keyboard back after this.
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        sheet = attachSheet(root, ui, content) { dismissSheet() }
    }

    fun dismissSheet() {
        val s = sheet ?: return
        sheet = null
        root.removeView(s)
        rebuild()
    }

    // ---- apps ---------------------------------------------------------------------------------

    /**
     * Every app opened from the launcher goes through here, so the pause before distracting apps
     * applies to the app list, pinned apps and the library alike.
     */
    fun launchApp(entry: AppCatalog.Entry) {
        val pkg = entry.component.packageName
        if (prefs.pauseEnabled && app.readfirst.data.Focus.isDistracting(prefs, catalog, pkg)) {
            app.readfirst.ui.PauseSheet.show(this, entry)
            return
        }
        if (!catalog.launch(this, entry)) Toast.makeText(this, "Can't open ${entry.label}", Toast.LENGTH_SHORT).show()
    }

    /** Home notices closed with "Later" stay hidden until the app restarts. */
    val laterNotices = HashSet<String>()

    // ---- books --------------------------------------------------------------------------------

    fun openBook(book: BookEntry) {
        dismissSheet()
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, book.id))
    }

    fun pickFolder() {
        dismissSheet()
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    fun pickFiles() {
        dismissSheet()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/pdf", "text/plain", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_FILES)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        when (requestCode) {
            REQ_FOLDER -> {
                val tree = data.data ?: return
                runCatching { contentResolver.takePersistableUriPermission(tree, flags) }
                library.addFolder(tree.toString())
                lastScan = SystemClock.elapsedRealtime()
                Scanner.refresh(this)
                Toast.makeText(this, R.string.scanning_folder, Toast.LENGTH_SHORT).show()
            }
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
                data.data?.let { uris += it }
                val entries = uris.distinct().mapNotNull { uri ->
                    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                    Scanner.entryForFile(this, uri, null)
                }
                val skipped = uris.distinct().size - entries.size
                library.addAll(entries)
                Scanner.enrichPending(this)
                if (skipped > 0) Toast.makeText(this, getString(R.string.skipped_files, skipped), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- system -------------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheet != null -> dismissSheet()
            stack.size > 1 -> pop()
            // Home swallows Back: a launcher has nowhere further back to go.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleWidgetAction(intent)) return
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            if (prefs.setupDone) goHome() else dismissSheet()
        }
    }

    /** Links from the widget: the free-books shelf, or becoming the home screen. */
    private fun handleWidgetAction(intent: Intent?): Boolean {
        when (intent?.action) {
            app.readfirst.widget.ReadingWidget.ACTION_FREE_BOOKS -> root.post { app.readfirst.ui.Starter.show(this) }
            app.readfirst.widget.ReadingWidget.ACTION_MAKE_HOME -> app.readfirst.data.HomeRole.requestDefault(this)
            else -> return false
        }
        return true
    }

    companion object {
        private const val REQ_FOLDER = 7101
        private const val REQ_FILES = 7102
    }
}
