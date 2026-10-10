package vn.vhn.pckeyboard

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.preference.ListPreference

/** A [ListPreference] that shows the chosen entry as its summary, without being told to. */
class AutoSummaryListPreference : ListPreference {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private fun trySetSummary() {
        val entry = try {
            entry
        } catch (e: ArrayIndexOutOfBoundsException) {
            Log.i(TAG, "Malfunctioning ListPreference, can't get entry")
            null
        } ?: return
        summary = entry.toString().replace("%", " percent")
    }

    override fun setEntries(entries: Array<CharSequence>) {
        super.setEntries(entries); trySetSummary()
    }

    override fun setEntryValues(entryValues: Array<CharSequence>) {
        super.setEntryValues(entryValues); trySetSummary()
    }

    override fun setValue(value: String?) {
        super.setValue(value); trySetSummary()
    }

    companion object {
        private const val TAG = "HK/AutoSummaryListPreference"
    }
}
