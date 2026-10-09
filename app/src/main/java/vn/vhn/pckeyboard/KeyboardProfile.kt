package vn.vhn.pckeyboard

import android.content.Context
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
 * Which profile is in effect is decided by [ScreenMetrics], which measures the panel
 * physically rather than in dp -- see [DEFAULT_LARGE_WIDTH_MM] for why.
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
         * Default small/large line, as the physical width of the panel.
         *
         * Millimetres rather than dp, because the keys are laid out as percentages of
         * the keyboard width (`keyWidth="7.14%p"`), not in dp. A key's physical size is
         * therefore the panel's physical width divided by the column count, and the
         * display density has nothing to do with it. Classifying by dp would also drift
         * every time the display-size / minimum-width setting is changed, which is a
         * thing people do.
         *
         * 100mm separates the cases with room to spare: a Fold's cover screen is around
         * 64mm wide folded, its inner screen around 130mm. Below the line, 14 columns
         * means 4.6mm keys; above it, 9mm and up.
         */
        const val DEFAULT_LARGE_WIDTH_MM = 100

        /** 1dp is 1/160 inch, so the dp fallback threshold tracks the mm one. */
        fun mmToDp(mm: Int): Int = Math.round(mm / 25.4f * 160f)

        fun of(metrics: ScreenMetrics, largeThresholdMm: Int = DEFAULT_LARGE_WIDTH_MM):
                KeyboardProfile {
            val large = metrics.isLarge(largeThresholdMm)
            return when {
                large && metrics.portrait -> LARGE_PORTRAIT
                large -> LARGE_LANDSCAPE
                metrics.portrait -> SMALL_PORTRAIT
                else -> SMALL_LANDSCAPE
            }
        }

        fun ofSuffix(suffix: String?): KeyboardProfile =
            entries.firstOrNull { it.suffix == suffix } ?: SMALL_PORTRAIT
    }
}

/**
 * What the screen measures right now, in all three units that matter, so the choice of
 * unit stays a decision rather than an assumption.
 *
 * [widthMm] is null when the panel reports an implausible physical dpi. Most do report
 * a real one -- `xdpi` is documented as the exact physical pixels per inch and is not
 * supposed to move when the display-size setting does -- but some OEMs just echo the
 * density there, and a silently wrong answer is worse than falling back to dp.
 */
class ScreenMetrics(
    val smallestWidthDp: Int,
    val smallestWidthPx: Int,
    val widthMm: Float?,
    val portrait: Boolean,
) {
    fun isLarge(thresholdMm: Int): Boolean {
        widthMm?.let { return it >= thresholdMm }
        return smallestWidthDp >= KeyboardProfile.mmToDp(thresholdMm)
    }

    /** One line for the settings screen and logcat; the numbers detection acted on. */
    fun report(thresholdMm: Int): String {
        val mm = widthMm
        val basis = if (mm != null) "%.0fmm".format(mm)
        else "panel dpi implausible, using ${smallestWidthDp}dp"
        return "$basis (sw${smallestWidthDp}dp, ${smallestWidthPx}px); " +
                "large at >=${thresholdMm}mm"
    }

    companion object {
        // Below a phone's ~160dpi or above a dense panel's ~900dpi, the value is junk.
        private const val MIN_PLAUSIBLE_DPI = 80f
        private const val MAX_PLAUSIBLE_DPI = 1200f

        fun of(context: Context): ScreenMetrics {
            val conf = context.resources.configuration
            val dm = context.resources.displayMetrics
            val widthPx = minOf(dm.widthPixels, dm.heightPixels)
            // Measure the panel's short side: the same side smallestScreenWidthDp names,
            // so the size half of a profile stays independent of the orientation half.
            val shortIsX = dm.widthPixels <= dm.heightPixels
            val px = if (shortIsX) dm.widthPixels else dm.heightPixels
            val dpi = if (shortIsX) dm.xdpi else dm.ydpi
            val mm = if (dpi in MIN_PLAUSIBLE_DPI..MAX_PLAUSIBLE_DPI) px / dpi * 25.4f else null
            return ScreenMetrics(
                smallestWidthDp = conf.smallestScreenWidthDp,
                smallestWidthPx = widthPx,
                widthMm = mm,
                portrait = conf.orientation != Configuration.ORIENTATION_LANDSCAPE,
            )
        }
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
