package app.booklauncher.data

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Being (and staying) the default home app, and leaving it safely.
 *
 * - Android 10+ shows the system "Set as default home app?" prompt.
 * - Android 8-9 (and brands that block the prompt) open the Home settings page.
 * - The user can open the previous home screen once without changing the default,
 *   or switch back to it on purpose; the latter is remembered so no nag is shown.
 */
object HomeRole {
    private const val PREFS = "home_role"
    private const val KEY_LEFT_ON_PURPOSE = "left_on_purpose"
    const val REQUEST_CODE = 4101

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val ri = context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == context.packageName
    }

    fun requestDefault(activity: Activity) {
        setLeftOnPurpose(activity, false)
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_CODE)
                    return
                } catch (_: Exception) {
                    // Some brands remove the role prompt; fall through to settings.
                }
            }
        }
        openHomeSettings(activity)
    }

    fun openHomeSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    /** Other installed home screens (the brand's stock launcher first if we can tell). */
    fun otherLaunchers(context: Context): List<ComponentName> =
        context.packageManager.queryIntentActivities(homeIntent(), 0)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }
            .sortedByDescending { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM }
            .map { ComponentName(it.packageName, it.name) }

    /** Opens the usual home screen once; pressing Home comes back here. */
    fun openOtherLauncherOnce(context: Context): Boolean {
        val target = otherLaunchers(context).firstOrNull() ?: return false
        return try {
            context.startActivity(homeIntent().setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** User chose to go back to the stock home screen: remember it, then open the settings page. */
    fun switchBackToStock(context: Context) {
        setLeftOnPurpose(context, true)
        openHomeSettings(context)
    }

    fun leftOnPurpose(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LEFT_ON_PURPOSE, false)

    private fun setLeftOnPurpose(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LEFT_ON_PURPOSE, value).apply()
    }
}
