package vn.vhn.pckeyboard

import android.content.Context

/**
 * Whether Vietnamese was last on or off in a given app.
 *
 * Which language you are typing is a property of what you are typing into, not of the
 * keyboard: Vietnamese in a chat, off in a terminal or an editor. Without this, every
 * switch between the two costs a toggle, and the one you forget costs a line of mangled
 * text.
 *
 * Only apps you have actually toggled in are recorded. Anything else falls back to the
 * general preference, so a new app behaves the way the last toggle did rather than
 * springing a stale state on you.
 */
object VnPerApp {
    private const val PREFS_NAME = "vn_per_app"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The remembered state for [pkg], or null if that app has never been toggled in. */
    fun get(context: Context, pkg: String?): Boolean? {
        if (pkg.isNullOrEmpty()) return null
        val p = prefs(context)
        return if (p.contains(pkg)) p.getBoolean(pkg, false) else null
    }

    fun set(context: Context, pkg: String?, on: Boolean) {
        if (pkg.isNullOrEmpty()) return
        prefs(context).edit().putBoolean(pkg, on).apply()
    }

    fun forget(context: Context) = prefs(context).edit().clear().apply()

    fun count(context: Context): Int = prefs(context).all.size
}
