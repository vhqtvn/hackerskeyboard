package vn.vhn.pckeyboard

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
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
            // Not flush to the edge: the bottom strip is where a swipe up means Home, so
            // a key row sitting in it still takes taps but loses every slide gesture.
            // Stop above that strip -- which is still a good deal smaller than the
            // navigation bar we just removed.
            val gestureBottom = WindowInsetsCompat.toWindowInsetsCompat(insets)
                .getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            @Suppress("DEPRECATION")
            insets.replaceSystemWindowInsets(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                minOf(gestureBottom, insets.systemWindowInsetBottom))
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