package vn.vhn.pckeyboard

import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import java.util.*

/**
 * SeekBarPreference provides a dialog for editing float-valued preferences with a slider.
 */
open class SeekBarPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs) {
    private var mMinText: TextView? = null
    private var mMaxText: TextView? = null
    private var mValText: TextView? = null
    private var mSeek: SeekBar? = null
    private var mMin = 0f
    private var mMax = 0f
    private var mVal = 0f
    private var mPrevVal = 0f
    private var mStep = 0f
    private var mAsPercent = false
    private var mLogScale = false
    private var mDisplayFormat: String? = null
    protected fun init(context: Context, attrs: AttributeSet?) {
        dialogLayoutResource = R.layout.seek_bar_dialog
        val a = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference)
        mMin = a.getFloat(R.styleable.SeekBarPreference_minValue, 0.0f)
        mMax = a.getFloat(R.styleable.SeekBarPreference_maxValue, 100.0f)
        mStep = a.getFloat(R.styleable.SeekBarPreference_step, 0.0f)
        mAsPercent = a.getBoolean(R.styleable.SeekBarPreference_asPercent, false)
        mLogScale = a.getBoolean(R.styleable.SeekBarPreference_logScale, false)
        mDisplayFormat = a.getString(R.styleable.SeekBarPreference_displayFormat)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Float {
        return a.getFloat(index, 0.0f)
    }

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        if (restorePersistedValue) {
            setVal(getPersistedFloat(0.0f))
        } else {
            setVal(defaultValue as Float)
        }
        savePrevVal()
    }

    private fun formatFloatDisplay(`val`: Float): String {
        // Use current locale for format, this is for display only.
        if (mAsPercent) {
            return String.format("%d%%", (`val` * 100).toInt())
        }
        return if (mDisplayFormat != null) {
            String.format(mDisplayFormat!!, `val`)
        } else {
            java.lang.Float.toString(`val`)
        }
    }

    private fun showVal() {
        mValText!!.text = formatFloatDisplay(mVal)
    }

    protected fun setVal(`val`: Float) {
        mVal = `val`
    }

    protected fun savePrevVal() {
        mPrevVal = mVal
    }

    protected fun restoreVal() {
        mVal = mPrevVal
    }

    protected fun getValString(): String {
        return java.lang.Float.toString(mVal)
    }

    private fun percentToSteppedVal(
        percent: Int,
        min: Float,
        max: Float,
        step: Float,
        logScale: Boolean
    ): Float {
        var `val`: Float
        if (logScale) {
            `val` = Math.exp(percentToSteppedVal(percent,
                Math.log(min.toDouble()).toFloat(),
                Math.log(max.toDouble()).toFloat(),
                step,
                false).toDouble()).toFloat()
        } else {
            var delta = percent * (max - min) / 100
            if (step != 0.0f) {
                delta = Math.round(delta / step) * step
            }
            `val` = min + delta
        }
        // Hack: Round number to 2 significant digits so that it looks nicer.
        `val` = java.lang.Float.valueOf(String.format(Locale.US, "%.2g", `val`))
        return `val`
    }

    private fun getPercent(`val`: Float, min: Float, max: Float): Int {
        return (100 * (`val` - min) / (max - min)).toInt()
    }

    private fun getProgressVal(): Int {
        return if (mLogScale) {
            getPercent(Math.log(mVal.toDouble()).toFloat(),
                Math.log(mMin.toDouble()).toFloat(),
                Math.log(mMax.toDouble()).toFloat())
        } else {
            getPercent(mVal, mMin, mMax)
        }
    }

    override fun onBindDialogView(view: View) {
        mSeek = view.findViewById<View>(R.id.seekBarPref) as SeekBar
        mMinText = view.findViewById<View>(R.id.seekMin) as TextView
        mMaxText = view.findViewById<View>(R.id.seekMax) as TextView
        mValText = view.findViewById<View>(R.id.seekVal) as TextView
        showVal()
        mMinText!!.text = formatFloatDisplay(mMin)
        mMaxText!!.text = formatFloatDisplay(mMax)
        mSeek!!.progress = getProgressVal()
        mSeek!!.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newVal = percentToSteppedVal(progress, mMin, mMax, mStep, mLogScale)
                    if (newVal != mVal) {
                        onChange(newVal)
                    }
                    setVal(newVal)
                    mSeek!!.progress = getProgressVal()
                }
                showVal()
            }
        })
        super.onBindDialogView(view)
    }

    open fun onChange(`val`: Float) {
        // override in subclasses
    }

    override fun getSummary(): CharSequence {
        return formatFloatDisplay(mVal)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (!positiveResult) {
            restoreVal()
            return
        }
        if (shouldPersist()) {
            persistFloat(mVal)
            savePrevVal()
        }
        notifyChanged()
    }

    init {
        init(context, attrs)
    }
}