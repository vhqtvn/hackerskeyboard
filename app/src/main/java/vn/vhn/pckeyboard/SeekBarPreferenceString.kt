package vn.vhn.pckeyboard

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import java.util.regex.Pattern

/**
 * A [SeekBarPreference] stored as a string rather than a float.
 *
 * Which is how these were always written, so the stored settings stay readable and keep
 * working across the change; some of them were saved by old versions with a " ms" or "%"
 * suffix, which the pattern below forgives.
 */
open class SeekBarPreferenceString(context: Context, attrs: AttributeSet?) :
    SeekBarPreference(context, attrs) {

    constructor(context: Context) : this(context, null)

    private fun floatFromString(pref: String?): Float {
        val num = FLOAT_RE.matcher(pref ?: "")
        return if (!num.matches()) 0.0f else num.group(1)!!.toFloat()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any =
        floatFromString(a.getString(index))

    override fun onSetInitialValue(defaultValue: Any?) {
        // A default can arrive as a float from code or as a string from XML -- and some of
        // the strings carry the unit ("45%", "400 ms"), which is what floatFromString is for.
        val fallback = when (defaultValue) {
            is Float -> defaultValue
            is String -> floatFromString(defaultValue)
            else -> 0.0f
        }
        setVal(if (shouldPersist()) floatFromString(getPersistedString(fallback.toString()))
               else fallback)
        savePrevVal()
    }

    override fun persistFloat(value: Float): Boolean = persistString(value.toString())

    companion object {
        private val FLOAT_RE: Pattern = Pattern.compile("([\\d.]+).*")
    }
}
