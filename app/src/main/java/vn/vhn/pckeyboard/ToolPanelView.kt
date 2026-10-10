package vn.vhn.pckeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The dock the keyboard opens beside or instead of its keys: a thin strip of controls
 * along the top, and whichever tool is active beneath it.
 *
 * It exists so the things that are not typing -- the emoji grid today, a clipboard or
 * anything else later -- share one place, one way in and one way out, instead of each
 * inventing its own panel. A tool is a label and a factory; adding one is a single entry
 * in the list [LatinIME] hands over, and its view is built the first time it is picked.
 */
@SuppressLint("ViewConstructor")
class ToolPanelView(context: Context, val compact: Boolean) : LinearLayout(context) {

    /** A thing the dock can show. [create] is called once, the first time it is picked. */
    class Tool(val label: String, val create: (Context) -> View)

    /** Dismiss the dock. */
    var onClose: (() -> Unit)? = null

    /** Delete backwards; only offered when the dock has replaced the keys. */
    var onBackspace: (() -> Unit)? = null

    private val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val content = FrameLayout(context)
    private val built = HashMap<Int, View>()
    private var tools: List<Tool> = emptyList()
    private var active = -1

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setTools(newTools: List<Tool>, start: Int = 0) {
        tools = newTools
        buildStrip()
        select(start)
    }

    private fun pressable(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x22FFFFFF))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun stripButton(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        setTextColor(0xFFDDDDDD.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = pressable()
        isClickable = true
        setOnClickListener { action() }
    }

    private fun buildStrip() {
        strip.removeAllViews()
        tools.forEachIndexed { i, t ->
            strip.addView(stripButton(t.label) { select(i) }.apply { tag = i })
        }
        // Pushes what follows to the far edge, so the way out is always in the same corner.
        strip.addView(View(context), LayoutParams(0, 1, 1f))
        // With the keys hidden the dock has to offer the two of them it took away.
        if (!compact) {
            strip.addView(stripButton("⌫") { onBackspace?.invoke() })
            strip.addView(stripButton("ABC") { onClose?.invoke() })
        } else {
            strip.addView(stripButton("✕") { onClose?.invoke() })
        }
    }

    private fun select(index: Int) {
        if (index !in tools.indices) return
        active = index
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            if (child.tag is Int) child.alpha = if (child.tag == index) 1f else 0.45f
        }
        val view = built.getOrPut(index) { tools[index].create(context) }
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
}
