package vn.vhn.pckeyboard

import android.content.Context
import android.content.res.TypedArray
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A preference holding a number, edited with a slider in a dialog.
 *
 * The value itself lives here; the dialog is [SeekBarPreferenceDialog], because androidx
 * splits a preference from the dialog it opens -- the dialog is a fragment, so it survives
 * a rotation the framework version used to lose.
 *
 * The scale can be logarithmic, which is what a duration wants: a slider that is linear in
 * milliseconds spends most of its travel on values nobody picks.
 */
open class SeekBarPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs) {

    private var mMin = 0f
    private var mMax = 0f
    private var mVal = 0f
    private var mPrevVal = 0f
    private var mStep = 0f
    private var mAsPercent = false
    private var mLogScale = false
    private var mDisplayFormat: String? = null

    init {
        dialogLayoutResource = R.layout.seek_bar_dialog
        val a = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference)
        mMin = a.getFloat(R.styleable.SeekBarPreference_minValue, 0.0f)
        mMax = a.getFloat(R.styleable.SeekBarPreference_maxValue, 100.0f)
        mStep = a.getFloat(R.styleable.SeekBarPreference_step, 0.0f)
        mAsPercent = a.getBoolean(R.styleable.SeekBarPreference_asPercent, false)
        mLogScale = a.getBoolean(R.styleable.SeekBarPreference_logScale, false)
        mDisplayFormat = a.getString(R.styleable.SeekBarPreference_displayFormat)
        a.recycle()
    }

    constructor(context: Context) : this(context, null)

    /** Configures a slider built in code rather than inflated from a layout. */
    fun configure(
        min: Float, max: Float, step: Float = 0f,
        asPercent: Boolean = false, logScale: Boolean = false, displayFormat: String? = null,
    ) {
        mMin = min; mMax = max; mStep = step
        mAsPercent = asPercent; mLogScale = logScale; mDisplayFormat = displayFormat
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getFloat(index, 0.0f)

    override fun onSetInitialValue(defaultValue: Any?) {
        val fallback = (defaultValue as? Float) ?: 0.0f
        setVal(if (shouldPersist()) getPersistedFloat(fallback) else fallback)
        savePrevVal()
    }

    fun formatFloatDisplay(value: Float): String = when {
        mAsPercent -> String.format(Locale.US, "%d%%", (value * 100).toInt())
        mDisplayFormat != null -> String.format(Locale.US, mDisplayFormat!!, value)
        else -> value.toString()
    }

    protected fun setVal(value: Float) { mVal = value }
    protected fun savePrevVal() { mPrevVal = mVal }
    protected fun restoreVal() { mVal = mPrevVal }
    protected fun getValString(): String = mVal.toString()

    private fun percentToSteppedVal(
        percent: Int, min: Float, max: Float, step: Float, logScale: Boolean,
    ): Float {
        val raw = if (logScale) {
            exp(percentToSteppedVal(percent, ln(min), ln(max), step, false).toDouble()).toFloat()
        } else {
            var delta = percent * (max - min) / 100
            if (step != 0.0f) delta = (delta / step).roundToInt() * step
            min + delta
        }
        // Round to two significant digits so the number reads like one a person would pick.
        return String.format(Locale.US, "%.2g", raw).toFloat()
    }

    private fun getPercent(value: Float, min: Float, max: Float): Int =
        (100 * (value - min) / (max - min)).toInt()

    internal fun progressVal(): Int =
        if (mLogScale) getPercent(ln(mVal), ln(mMin), ln(mMax)) else getPercent(mVal, mMin, mMax)

    internal fun bindDialog(view: View) {
        val seek = view.findViewById<SeekBar>(R.id.seekBarPref)
        val valText = view.findViewById<TextView>(R.id.seekVal)
        view.findViewById<TextView>(R.id.seekMin).text = formatFloatDisplay(mMin)
        view.findViewById<TextView>(R.id.seekMax).text = formatFloatDisplay(mMax)
        valText.text = formatFloatDisplay(mVal)
        seek.progress = progressVal()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newVal = percentToSteppedVal(progress, mMin, mMax, mStep, mLogScale)
                    if (newVal != mVal) onChange(newVal)
                    setVal(newVal)
                    seekBar.progress = progressVal()
                }
                valText.text = formatFloatDisplay(mVal)
            }
        })
    }

    internal fun dialogClosed(positiveResult: Boolean) {
        if (!positiveResult) { restoreVal(); return }
        if (shouldPersist()) {
            persistFloat(mVal)
            savePrevVal()
            inheritedNote = null
        }
        notifyChanged()
    }

    open fun onChange(value: Float) {
        // override in subclasses
    }

    /**
     * Set when nothing is stored for this preference and the slider is showing a value
     * inherited from elsewhere, so the summary can say where it came from. Cleared as
     * soon as the user stores a value of their own. See [PrefScreenProfiles].
     */
    var inheritedNote: CharSequence? = null

    /** Shows [value] without persisting it, so an inherited setting reads honestly. */
    fun setDisplayedValue(value: Float) {
        setVal(value)
        savePrevVal()
        notifyChanged()
    }

    override fun getSummary(): CharSequence {
        val shown = formatFloatDisplay(mVal)
        return inheritedNote?.let { "$shown  $it" } ?: shown
    }
}

/** The slider dialog. androidx keeps this separate from the preference, as a fragment. */
class SeekBarPreferenceDialog : PreferenceDialogFragmentCompat() {

    private val pref get() = preference as SeekBarPreference

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        pref.bindDialog(view)
    }

    override fun onDialogClosed(positiveResult: Boolean) = pref.dialogClosed(positiveResult)

    companion object {
        fun forKey(key: String) = SeekBarPreferenceDialog().apply {
            arguments = Bundle(1).apply { putString(ARG_KEY, key) }
        }
    }
}
