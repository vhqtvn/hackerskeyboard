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
        reportDetection()
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        BackupManager(this).dataChanged()
    }

    /**
     * Spells out what the detection actually saw. Profile detection is the one thing
     * here that can look simply broken -- a screen classed as the wrong size silently
     * applies the wrong layout -- and the numbers behind it are invisible otherwise.
     *
     * Prefers the values the *keyboard* resolved over this activity's own: in
     * multi-window the two Configurations need not agree, and when they disagree that
     * is precisely the thing worth seeing.
     */
    private fun reportDetection() {
        val pref = findPreference("profile_explain") ?: return
        val profile = LatinIME.sKeyboardSettings.activeProfile
        val report = LatinIME.sKeyboardSettings.activeProfileReport
        val detail = if (profile != null && report != null) {
            getString(R.string.profile_detected, profileName(profile), report)
        } else {
            val threshold = thresholdMm()
            val metrics = ScreenMetrics.of(this)
            getString(R.string.profile_detected_here,
                profileName(KeyboardProfile.of(metrics, threshold)), metrics.report(threshold))
        }
        pref.summary = "${getString(R.string.profile_explain)}\n\n$detail"
    }

    private fun profileName(p: KeyboardProfile) = getString(when (p) {
        KeyboardProfile.SMALL_PORTRAIT -> R.string.profile_small_portrait
        KeyboardProfile.SMALL_LANDSCAPE -> R.string.profile_small_landscape
        KeyboardProfile.LARGE_PORTRAIT -> R.string.profile_large_portrait
        KeyboardProfile.LARGE_LANDSCAPE -> R.string.profile_large_landscape
    })

    private fun thresholdMm(): Int = preferenceManager.sharedPreferences
        .getString(LatinIME.PREF_LARGE_SCREEN_MM, null)?.trim()?.toFloatOrNull()?.toInt()
        ?: KeyboardProfile.DEFAULT_LARGE_WIDTH_MM

    private fun activeProfile(): KeyboardProfile =
        KeyboardProfile.of(ScreenMetrics.of(this), thresholdMm())

    private fun markActiveProfile() {
        val active = LatinIME.sKeyboardSettings.activeProfile ?: activeProfile()
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
