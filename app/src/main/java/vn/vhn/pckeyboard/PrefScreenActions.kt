package vn.vhn.pckeyboard

import android.os.Bundle

class PrefScreenActions : SettingsFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_actions, rootKey)
    }
}
