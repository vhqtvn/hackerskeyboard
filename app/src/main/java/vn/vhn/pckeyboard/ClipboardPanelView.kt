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
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * What has been copied lately, as a list you can keep open.
 *
 * It used to be a dialog, which could only be read once per press: it covered the field
 * you were pasting into, and picking anything dismissed it. As a tool in [ToolPanelView]
 * it sits beside the keys instead, so several clips can be pasted in a row, and the text
 * going in stays visible.
 *
 * Long-press removes one entry; the row at the end clears the lot. Android only lets an
 * app read the clipboard while it has focus, so the list is re-sampled on every open --
 * see [ClipboardHistory].
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    /** Paste this text into the field. */
    var onPaste: ((String) -> Unit)? = null

    private val list = ListView(context)
    private var entries: List<String> = emptyList()

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        list.divider = ColorDrawable(0x22FFFFFF)
        list.dividerHeight = 1
        list.selector = ColorDrawable(Color.TRANSPARENT)
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    /** Re-reads the clipboard and the history. Called whenever the tool is shown. */
    fun refresh() {
        ClipboardHistory.capture(context)
        entries = ClipboardHistory.entries(context)
        list.adapter = Adapter()
    }

    private fun pressable(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x22FFFFFF))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** One line, whitespace flattened, so every row is the same height. */
    private fun summarize(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 120) flat else flat.substring(0, 120) + "…"
    }

    private inner class Adapter : BaseAdapter() {
        // One past the end: the row that empties the history.
        override fun getCount() = if (entries.isEmpty()) 1 else entries.size + 1
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView as? TextView ?: TextView(context).apply {
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                background = pressable()
                isClickable = true
                isLongClickable = true
            }
            if (entries.isEmpty()) {
                tv.text = context.getString(R.string.clipboard_empty)
                tv.setTextColor(0xFF888888.toInt())
                tv.gravity = Gravity.CENTER
                tv.setOnClickListener(null)
                tv.setOnLongClickListener(null)
                return tv
            }
            tv.gravity = Gravity.CENTER_VERTICAL
            if (position == entries.size) {
                tv.text = context.getString(R.string.clipboard_clear)
                tv.setTextColor(0xFFE57373.toInt())
                tv.setOnClickListener { ClipboardHistory.clear(context); refresh() }
                tv.setOnLongClickListener(null)
            } else {
                val entry = entries[position]
                tv.text = summarize(entry)
                tv.setTextColor(0xFFDDDDDD.toInt())
                tv.setOnClickListener { onPaste?.invoke(entry) }
                tv.setOnLongClickListener {
                    ClipboardHistory.remove(context, entry); refresh(); true
                }
            }
            return tv
        }
    }
}
