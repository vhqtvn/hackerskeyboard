package vn.vhn.pckeyboard

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.FrameLayout

class KeyboardContainer : FrameLayout {
    constructor(context: Context?) : super(context!!) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context!!, attrs) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context!!, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        fitsSystemWindows = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var i = 0
        val ie = childCount
        while (i < ie) {
            val v = getChildAt(i)
            if (v is LatinKeyboardBaseView) {
                v.applyInset(
                    insets.systemWindowInsetBottom,
                    insets.systemWindowInsetRight
                )
            }
            i++
        }
        return super.onApplyWindowInsets(insets)
    }
}