package vn.vhn.pckeyboard

import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * A keyboard layout profile: one of the four combinations of screen size and
 * orientation that a device can present.
 *
 * A book-style foldable is really two keyboards. Folded in portrait you want a
 * narrow thumb layout; unfolded in landscape you have a slate wide enough for a
 * split keyboard, and the height and key scaling that suit one are wrong for the
 * other. Android's single pair of portrait/landscape settings cannot express that,
 * so every layout-shaped preference can be overridden per profile.
 *
 * Overrides are stored under `"<base key>@<suffix>"`. A profile that has never been
 * touched has no such keys, and [ProfilePrefs] falls back to the global preference --
 * so a device that only ever sees one profile behaves exactly as it did before.
 */
enum class KeyboardProfile(val suffix: String, val isLarge: Boolean, val isPortrait: Boolean) {
    SMALL_PORTRAIT("sp", false, true),
    SMALL_LANDSCAPE("sl", false, false),
    LARGE_PORTRAIT("lp", true, true),
    LARGE_LANDSCAPE("ll", true, false);

    companion object {
        /**
         * Default for the small/large line: Android's own "this pane is tablet-sized"
         * threshold, and the one a book-style foldable crosses when it opens. A Fold's
         * cover screen is around 411dp and its inner screen around 673dp, so 600 sits
         * with a comfortable margin either side. Adjustable, because panel sizes vary
         * and nobody should have to rebuild the app to move a number.
         */
        const val DEFAULT_LARGE_SMALLEST_WIDTH_DP = 600

        /**
         * Why [Configuration.smallestScreenWidthDp] and not `screenWidthDp`: the size
         * half of a profile has to be independent of the orientation half, or a cover
         * screen held in landscape (around 912dp wide) would be classed as a large
         * screen. `smallestScreenWidthDp` is the smaller of the two dimensions, so
         * folding changes it and rotating does not.
         *
         * Caveat worth knowing: for an Activity this reflects the *window* in
         * multi-window mode rather than the display. Whether an InputMethodService sees
         * the same is not something this code assumes -- the settings screen reports
         * the value the keyboard actually resolved, so the device can answer it.
         */
        fun of(conf: Configuration, largeThresholdDp: Int = DEFAULT_LARGE_SMALLEST_WIDTH_DP):
                KeyboardProfile {
            val large = conf.smallestScreenWidthDp >= largeThresholdDp
            val portrait = conf.orientation != Configuration.ORIENTATION_LANDSCAPE
            return when {
                large && portrait -> LARGE_PORTRAIT
                large -> LARGE_LANDSCAPE
                portrait -> SMALL_PORTRAIT
                else -> SMALL_LANDSCAPE
            }
        }

        fun ofSuffix(suffix: String?): KeyboardProfile =
            entries.firstOrNull { it.suffix == suffix } ?: SMALL_PORTRAIT
    }
}

/**
 * Reads a preference for a given [KeyboardProfile], falling back to the global value.
 *
 * Lookup order is always: the profile override, then the legacy per-orientation key if
 * the setting had one, then the plain global key, then the supplied default.
 */
object ProfilePrefs {
    fun key(base: String, profile: KeyboardProfile) =
        "$base${LatinIME.PROFILE_KEY_SEPARATOR}${profile.suffix}"

    /** True when this profile has its own value for [base] rather than inheriting one. */
    fun hasOverride(prefs: SharedPreferences, base: String, profile: KeyboardProfile) =
        prefs.contains(key(base, profile))

    fun getString(
        prefs: SharedPreferences,
        base: String,
        profile: KeyboardProfile,
        legacyKey: String? = null,
        default: String? = null,
    ): String? {
        prefs.getString(key(base, profile), null)?.let { return it }
        if (legacyKey != null) prefs.getString(legacyKey, null)?.let { return it }
        return prefs.getString(base, default) ?: default
    }

    fun getInt(
        prefs: SharedPreferences,
        base: String,
        profile: KeyboardProfile,
        legacyKey: String? = null,
        default: Int,
    ): Int {
        val raw = getString(prefs, base, profile, legacyKey, null) ?: return default
        // Preferences here are stored as strings (SeekBarPreferenceString, ListPreference),
        // and a half-written value should not take the keyboard down.
        return raw.trim().toFloatOrNull()?.toInt() ?: default
    }

    fun getBoolean(
        prefs: SharedPreferences,
        base: String,
        profile: KeyboardProfile,
        default: Boolean,
    ): Boolean {
        val k = key(base, profile)
        if (prefs.contains(k)) return prefs.getBoolean(k, default)
        return prefs.getBoolean(base, default)
    }
}
