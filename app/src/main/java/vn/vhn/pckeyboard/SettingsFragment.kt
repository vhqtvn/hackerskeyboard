package vn.vhn.pckeyboard

import android.app.backup.BackupManager
import android.content.SharedPreferences
import androidx.preference.PreferenceFragmentCompat

/**
 * What every settings screen here does the same way: back up on a change, and open the
 * slider preference's dialog, which androidx leaves to the host fragment rather than to
 * the preference itself.
 */
abstract class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        context?.let { BackupManager(it).dataChanged() }
    }

    override fun onDisplayPreferenceDialog(preference: androidx.preference.Preference) {
        if (preference is SeekBarPreference) {
            if (parentFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
            SeekBarPreferenceDialog.forKey(preference.key).also {
                it.setTargetFragment(this, 0)
                it.show(parentFragmentManager, DIALOG_TAG)
            }
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private companion object {
        const val DIALOG_TAG = "vn.vhn.pckeyboard.SEEK_BAR_DIALOG"
    }
}
