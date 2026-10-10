package vn.vhn.pckeyboard

import android.os.Bundle
import androidx.preference.ListPreference

class PrefScreenView : SettingsFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_view, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // Nothing to pick from if the device cannot change how the keyboard is drawn.
        if (LatinKeyboardBaseView.sSetRenderMode == null) {
            (findPreference(LatinIME.PREF_RENDER_MODE) as? ListPreference)?.apply {
                isEnabled = false
                setSummary(R.string.render_mode_unavailable)
            }
        }
    }
}
