package vn.vhn.pckeyboard

import android.content.Context
import android.graphics.Paint

/**
 * The emoji the panel offers, grouped the way Unicode groups them.
 *
 * The list in `res/raw/emoji.txt` is generated from Unicode's own emoji-test.txt
 * (15.1), keeping the fully-qualified entries and dropping the skin-tone and hair
 * variants -- those triple the list while the base emoji already stands for all of
 * them, and picking a tone is a separate feature.
 *
 * Which of them a given phone can actually draw is not something the list can know:
 * a font that predates an emoji renders it as a blank box, and the font travels with
 * the system, not with us. So every entry is checked against the device's own fonts
 * with [Paint.hasGlyph], which answers exactly the question that matters -- "does this
 * string come out as one glyph" -- and anything that fails is dropped.
 */
object EmojiData {

    class Group(val name: String, val emoji: List<String>)

    @Volatile private var groups: List<Group>? = null

    /** Parsed and filtered once; the result is small and lives for the process. */
    fun groups(context: Context): List<Group> {
        groups?.let { return it }
        synchronized(this) {
            groups?.let { return it }
            val paint = Paint()
            val out = ArrayList<Group>()
            try {
                context.resources.openRawResource(R.raw.emoji).bufferedReader().forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val name = line.substring(0, tab)
                    val list = line.substring(tab + 1).split(' ')
                        .filter { it.isNotEmpty() && paint.hasGlyph(it) }
                    if (list.isNotEmpty()) out.add(Group(name, list))
                }
            } catch (_: Exception) {
            }
            groups = out
            return out
        }
    }

    /** A tab needs a face, and the first emoji of each group is a fair one. */
    fun tabLabel(group: Group): String = group.emoji.firstOrNull() ?: "?"
}
