package vn.vhn.pckeyboard

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 *
 * Nothing is listed while the device is locked. The panel can outlive a lock -- the
 * Thor's deck stays up on the lock screen -- so it also empties itself the moment the
 * screen goes off, and only looks again once the screen is back or the device unlocked.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    /** Paste this text into the field. */
    var onPaste: ((String) -> Unit)? = null

    private val list = ListView(context)
    private var entries: List<String> = emptyList()
    private var locked = false

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // At screen-off the keyguard may not be up yet (lock delay), but it will be
            // by the time anyone can look, so hide without asking.
            if (intent.action == Intent.ACTION_SCREEN_OFF) show(locked = true) else refresh()
        }
    }

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
        show(ClipboardHistory.locked(context))
    }

    private fun show(locked: Boolean) {
        this.locked = locked
        entries = if (locked) emptyList() else ClipboardHistory.entries(context)
        list.adapter = Adapter()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Exported: these come from the system, and NOT_EXPORTED would never receive them.
        androidx.core.content.ContextCompat.registerReceiver(context, lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(lockReceiver)
        super.onDetachedFromWindow()
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
                tv.text = context.getString(
                    if (locked) R.string.clipboard_locked else R.string.clipboard_empty)
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
