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
 *
 * A profile that has never been touched stores nothing and inherits the global
 * setting. A slider cannot express "unset", so rather than show a default that is not
 * what the keyboard is actually doing, untouched sliders are moved to the value being
 * inherited and labelled as inherited.
 */
class PrefScreenProfiles : PreferenceActivity(), OnSharedPreferenceChangeListener {
    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        addPreferencesFromResource(R.xml.prefs_profiles)
        padForSystemBars()
        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        markActiveProfile()
        showInheritedHeights()
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

    private fun showInheritedHeights() {
        val prefs = preferenceManager.sharedPreferences
        val note = getString(R.string.profile_inherited_note)
        for (profile in KeyboardProfile.entries) {
            val key = ProfilePrefs.key(LatinIME.PREF_HEIGHT, profile)
            if (prefs.contains(key)) continue
            val pref = findPreference(key) as? SeekBarPreferenceString ?: continue
            val legacyKey =
                if (profile.isPortrait) LatinIME.PREF_HEIGHT_PORTRAIT
                else LatinIME.PREF_HEIGHT_LANDSCAPE
            val legacyDefault = resources.getString(
                if (profile.isPortrait) R.string.default_height_portrait
                else R.string.default_height_landscape)
            val inherited = ProfilePrefs.getInt(prefs, LatinIME.PREF_HEIGHT, profile,
                legacyKey = legacyKey,
                default = LatinIME.getHeight(prefs, legacyKey, legacyDefault))
            pref.inheritedNote = note
            pref.setDisplayedValue(inherited.toFloat())
        }
    }
}
