package vn.vhn.pckeyboard

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import com.vhn.SurfaceDuoUtils
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
        // Side insets: only a Surface Duo wants them. There, Keyboard narrows itself to
        // avoid the hinge (mShouldApplyInset is true only on that device) and the padding
        // here matches, so view and layout agree.
        //
        // Everywhere else nothing narrows the keyboard, so letting fitsSystemWindows pad
        // the sides made the view narrower than the layout it holds and the right-hand
        // keys were clipped -- visible on the cover screen in landscape, where the system
        // reports a side inset and the last key of every row was cut in half.
        val keepSides = SurfaceDuoUtils.isDeviceSurfaceDuo(context.packageManager)
        // With the IME navigation bar hidden (see LatinIME.applyImeNavBarPreference) its
        // inset would still be applied as bottom padding, leaving the gap it was meant to
        // fill. Drop it so the window actually shrinks and the app above gets the space.
        val applied = if (LatinIME.sKeyboardSettings.hideImeNavBar) {
            // Leave room for the system's gesture bar and nothing else. Measured on a
            // Fold 7, the insets this window reports are: systemWindow 114, navigationBars
            // 31, mandatorySystemGestures 114, systemGestures 114, tappableElement 114 --
            // everything except navigationBars counts the IME navigation bar we just
            // hid, which is exactly the space we are trying to reclaim.
            val gestureBar = WindowInsetsCompat.toWindowInsetsCompat(insets)
                .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            sideAdjusted(insets, keepSides, minOf(gestureBar, insets.systemWindowInsetBottom))
        } else {
            sideAdjusted(insets, keepSides, insets.systemWindowInsetBottom)
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

    @Suppress("DEPRECATION")
    private fun sideAdjusted(insets: WindowInsets, keepSides: Boolean, bottom: Int): WindowInsets {
        val left = if (keepSides) insets.systemWindowInsetLeft else 0
        val right = if (keepSides) insets.systemWindowInsetRight else 0
        if (left == insets.systemWindowInsetLeft && right == insets.systemWindowInsetRight &&
            bottom == insets.systemWindowInsetBottom
        ) return insets
        return insets.replaceSystemWindowInsets(left, insets.systemWindowInsetTop, right, bottom)
    }
}