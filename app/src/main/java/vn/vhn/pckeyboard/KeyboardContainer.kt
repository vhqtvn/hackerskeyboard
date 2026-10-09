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
        // With the IME navigation bar hidden (see LatinIME.applyImeNavBarPreference) its
        // inset would still be applied as bottom padding, leaving the gap it was meant to
        // fill. Drop it so the window actually shrinks and the app above gets the space.
        val applied = if (LatinIME.sKeyboardSettings.hideImeNavBar) {
            @Suppress("DEPRECATION")
            insets.replaceSystemWindowInsets(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, 0)
        } else {
            insets
        }
        var i = 0
        val ie = childCount
        while (i < ie) {
            val v = getChildAt(i)
            if (v is LatinKeyboardBaseView) {
                v.applyInset(
                    applied.systemWindowInsetBottom,
                    applied.systemWindowInsetRight
                )
            }
            i++
        }
        return super.onApplyWindowInsets(applied)
    }
}