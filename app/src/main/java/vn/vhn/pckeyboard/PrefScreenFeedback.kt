package vn.vhn.pckeyboard

import android.os.Bundle

class PrefScreenFeedback : SettingsFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_feedback, rootKey)
    }
}
