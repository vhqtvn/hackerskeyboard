package vn.vhn.pckeyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * A short history of what has been copied, so pasting something from two copies ago
 * does not mean going back to the other app to copy it again.
 *
 * Android only lets an app read the clipboard while it has focus, and an IME counts as
 * focused while it is up -- but it is not notified of copies made in other apps. So
 * rather than listen, this samples the clipboard at the moments the keyboard is in a
 * position to read it: when it opens on a field, and when the history is opened.
 *
 * The history is persisted, because an IME process is killed and restarted constantly
 * and an in-memory-only history would almost always be empty when wanted. It is capped
 * in both entry count and entry size, and it refuses clips the source app marked
 * sensitive (password managers and the like set this).
 */
object ClipboardHistory {
    const val MAX_ENTRIES = 30

    /** Long clips are usually not the thing you want to re-paste, and they bloat prefs. */
    const val MAX_ENTRY_LENGTH = 4000

    private const val PREFS_NAME = "clipboard_history"
    private const val KEY_COUNT = "count"
    private const val KEY_ENTRY = "entry_"

    private var loaded = false
    private val entries = ArrayList<String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val count = p.getInt(KEY_COUNT, 0).coerceIn(0, MAX_ENTRIES)
        for (i in 0 until count) {
            p.getString(KEY_ENTRY + i, null)?.let { entries.add(it) }
        }
    }

    @Synchronized
    private fun save(context: Context) {
        val e = prefs(context).edit()
        e.clear()
        e.putInt(KEY_COUNT, entries.size)
        entries.forEachIndexed { i, text -> e.putString(KEY_ENTRY + i, text) }
        e.apply()
    }

    @Synchronized
    fun entries(context: Context): List<String> {
        load(context)
        return ArrayList(entries)
    }

    /**
     * Reads the current clipboard and records it if it is new. Safe to call often --
     * re-reading the same clip is a no-op.
     */
    @Synchronized
    fun capture(context: Context) {
        load(context)
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val clip = try {
            // Throws in some states (no focus, device locked); never worth a crash.
            if (manager.hasPrimaryClip()) manager.primaryClip else null
        } catch (_: Throwable) {
            null
        } ?: return
        if (isSensitive(clip.description)) return
        val text = (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(context)?.toString() }
            .firstOrNull { it.isNotBlank() }
            ?: return
        if (text.length > MAX_ENTRY_LENGTH) return
        add(context, text)
    }

    @Synchronized
    fun add(context: Context, text: String) {
        load(context)
        if (text.isBlank()) return
        // Re-copying something already held moves it to the front rather than duplicating.
        entries.remove(text)
        entries.add(0, text)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save(context)
    }

    @Synchronized
    fun remove(context: Context, text: String) {
        load(context)
        if (entries.remove(text)) save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        load(context)
        entries.clear()
        save(context)
    }

    private fun isSensitive(description: ClipDescription?): Boolean {
        description ?: return false
        val extras = description.extras ?: return false
        val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
        else "android.content.extra.IS_SENSITIVE"
        return extras.getBoolean(key, false)
    }
}
