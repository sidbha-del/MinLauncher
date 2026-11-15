package app.readfirst.ui

import android.view.View
import app.readfirst.HomeActivity

/** One full-screen page inside HomeActivity. Screens are cheap views, not activities. */
abstract class Screen(val host: HomeActivity) {
    val ui: Ui get() = host.ui
    val p: InkPalette get() = host.ui.p
    open val isHome: Boolean = false

    abstract fun build(): View
    open fun onHide() {}
    open fun onTimeTick() {}

    /** The activity came back to the foreground, or a sheet closed, and the screen was kept as it
     *  is (nothing it shows had changed): bring its live bits (clock, timer) up to date. */
    open fun onShown() { onTimeTick() }

    /** True while this screen is mid-gesture and must not have its view torn out from under it
     *  by a background rebuild (a prefs/library change, a clock tick). [Pager] is the only one
     *  that ever needs this, for its live page-drag. */
    open fun blocksRebuild(): Boolean = false
}
