package vn.vhn.pckeyboard

import android.app.backup.BackupManager
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory

/**
 * Per-profile layout overrides -- one category per {small, large} x {portrait,
 * landscape} combination. See [KeyboardProfile].
 *
 * The category matching the screen you are holding is marked, since that is the one
 * whose changes you will see immediately.
 */
class PrefScreenProfiles : PreferenceActivity(), OnSharedPreferenceChangeListener {
    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        addPreferencesFromResource(R.xml.prefs_profiles)
        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        markActiveProfile()
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        BackupManager(this).dataChanged()
    }

    private fun markActiveProfile() {
        val active = KeyboardProfile.of(resources.configuration)
        val category = findPreference("profile_${active.suffix}") as? PreferenceCategory ?: return
        category.title = getString(R.string.profile_active_marker, category.title)
    }
}
