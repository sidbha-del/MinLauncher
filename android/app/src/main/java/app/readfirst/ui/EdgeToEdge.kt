package app.readfirst.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ forces edge-to-edge for apps targeting API 35+, ignoring the old
 * statusBarColor/navigationBarColor reservation this app's theme relies on. Pad [root] by the
 * real system bar insets so content isn't drawn under the
 * status/nav bar on those OS versions. WindowInsets.Type.systemBars() only exists on API 30+; on
 * older OS versions edge-to-edge isn't forced, so there's nothing to consume here.
 */
/** Dark status/navigation icons on light pages, light icons on dark (Night) pages. */
@Suppress("DEPRECATION")
fun setBarIcons(activity: android.app.Activity, dark: Boolean) {
    val decor = activity.window.decorView
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
    } else {
        var flags = decor.systemUiVisibility
        flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 27) {
            flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        decor.systemUiVisibility = flags
    }
}

fun applyEdgeToEdgeInsets(root: View) {
    if (Build.VERSION.SDK_INT < 30) return
    root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
