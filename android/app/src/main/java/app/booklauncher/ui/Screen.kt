package app.booklauncher.ui

import android.view.View
import app.booklauncher.HomeActivity

/** One full-screen page inside HomeActivity. Screens are cheap views, not activities. */
abstract class Screen(val host: HomeActivity) {
    val ui: Ui get() = host.ui
    val p: InkPalette get() = host.ui.p
    open val isHome: Boolean = false

    abstract fun build(): View
    open fun onHide() {}
    open fun onTimeTick() {}
}
