package vn.vhn.pckeyboard

import android.content.Context
import android.util.AttributeSet

class VibratePreference(context: Context, attrs: AttributeSet?) :
    SeekBarPreferenceString(context, attrs) {
    override fun onChange(x: Float) {
        LatinIME.sInstance?.vibrate(x.toInt())
    }
}