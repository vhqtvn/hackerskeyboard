package vn.vhn.pckeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The emoji picker, shown in place of the keys rather than over them: it is the same
 * window and the same height, so the app above never reflows when it opens.
 *
 * A row of category tabs, a grid, and a bottom bar to get back to the letters. The
 * first tab is what you have used recently, which after a day is the only tab most
 * people touch -- see [EmojiRecents].
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context) : LinearLayout(context) {

    /** Commit this emoji into the field. */
    var onEmoji: ((String) -> Unit)? = null

    /** Delete backwards, same as the keyboard's own backspace. */
    var onBackspace: (() -> Unit)? = null

    /** Dismiss the panel and bring the letters back. */
    var onClose: (() -> Unit)? = null

    private val grid: GridView
    private val tabStrip: LinearLayout
    private val groups = EmojiData.groups(context)
    private var current = -1          // -1 = the recents tab

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)

        tabStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        grid = GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(44f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 0
            horizontalSpacing = 0
            isVerticalScrollBarEnabled = true
            setSelector(ColorDrawable(Color.TRANSPARENT))   // cells draw their own press state
        }
        // The grid takes whatever height is left once the two bars have had theirs,
        // so the panel always fills exactly the keyboard's height.
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(bottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buildTabs()
        // Recents is the right tab to land on -- except the first time, when it is empty
        // and the panel would open on a blank grid.
        select(if (EmojiRecents.entries(context).isEmpty()) 0 else -1)
    }

    private fun cellBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x22FFFFFF))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun buildTabs() {
        tabStrip.removeAllViews()
        // Recents first: it is the one you want nine times out of ten.
        tabStrip.addView(tabView("🕒", -1))
        groups.forEachIndexed { i, g -> tabStrip.addView(tabView(EmojiData.tabLabel(g), i)) }
    }

    private fun tabView(label: String, index: Int) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        background = cellBackground()
        isClickable = true
        tag = index
        setOnClickListener { select(index) }
    }

    private fun bottomBar() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(barButton("ABC") { onClose?.invoke() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(barButton("⌫") { onBackspace?.invoke() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun barButton(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(0, dp(10f), 0, dp(10f))
        setTextColor(0xFFDDDDDD.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = cellBackground()
        isClickable = true
        setOnClickListener { action() }
    }

    private fun select(index: Int) {
        current = index
        for (i in 0 until tabStrip.childCount) {
            val tab = tabStrip.getChildAt(i)
            tab.alpha = if (tab.tag == index) 1f else 0.45f
        }
        val list = if (index < 0) EmojiRecents.entries(context) else groups[index].emoji
        grid.adapter = Adapter(list)
        grid.setSelection(0)
    }

    /** Refreshes the recents tab if it is the one showing, after a pick. */
    private fun refreshRecentsIfShowing() {
        if (current < 0) select(-1)
    }

    private inner class Adapter(private val items: List<String>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView as? TextView ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                height = dp(44f)
                background = cellBackground()
                isClickable = true
            }
            val emoji = items[position]
            tv.text = emoji
            tv.setOnClickListener {
                EmojiRecents.add(context, emoji)
                onEmoji?.invoke(emoji)
                refreshRecentsIfShowing()
            }
            return tv
        }
    }
}
