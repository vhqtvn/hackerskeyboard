package vn.vhn.pckeyboard

import android.content.Context

/**
 * The emoji you actually use, most recent first.
 *
 * Persisted for the same reason the clipboard history is: an IME process is killed and
 * restarted constantly, and a list that only lived in memory would be empty exactly when
 * it is wanted. Kept to one line's worth so the row never scrolls.
 */
object EmojiRecents {
    const val MAX = 36

    private const val PREFS_NAME = "emoji_recents"
    private const val KEY = "recent"

    private var loaded = false
    private val entries = ArrayList<String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        // Space-separated: no emoji contains a space, so nothing needs escaping.
        prefs(context).getString(KEY, null)?.split(' ')
            ?.filterTo(entries) { it.isNotEmpty() }
    }

    @Synchronized
    fun entries(context: Context): List<String> {
        load(context)
        return ArrayList(entries)
    }

    /** Records a use, moving an emoji already present back to the front. */
    @Synchronized
    fun add(context: Context, emoji: String) {
        load(context)
        entries.remove(emoji)
        entries.add(0, emoji)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        prefs(context).edit().putString(KEY, entries.joinToString(" ")).apply()
    }
}
