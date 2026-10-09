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
 * Which profile is in effect is decided by [ScreenMetrics] -- see
 * [DEFAULT_LARGE_WIDTH_DP] for the unit and why.
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
         * Default small/large line, in dp -- the scaled unit, the same one CSS media
         * queries work in.
         *
         * dp rather than a physical measurement because the display-size / minimum-width
         * setting is a deliberate statement of how much content the screen should hold,
         * and a layout decision should honour it: turn a screen "bigger" and you have
         * asked for the denser keyboard. It is also the unit Android's own `sw600dp`
         * resource qualifiers use, so the keyboard classifies screens the same way the
         * rest of the system does.
         *
         * 600dp is that qualifier's own tablet line, and a Fold crosses it when it
         * opens: cover screen around 411dp, inner screen around 673dp at stock density.
         */
        const val DEFAULT_LARGE_WIDTH_DP = 600

        fun of(metrics: ScreenMetrics, largeThresholdDp: Int = DEFAULT_LARGE_WIDTH_DP):
                KeyboardProfile {
            val large = metrics.smallestWidthDp >= largeThresholdDp
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
 * What the screen measures right now.
 *
 * [smallestWidthDp] is what decides the profile; the other two are carried only so the
 * settings screen and logcat can show them. Seeing all three side by side is what makes
 * a surprising classification diagnosable instead of mysterious -- in particular, dp
 * moves when the display-size setting does and the other two do not.
 *
 * [widthMm] is null when the panel reports an implausible physical dpi; nothing depends
 * on it, so that is only a gap in the report.
 */
class ScreenMetrics(
    val smallestWidthDp: Int,
    val smallestWidthPx: Int,
    val widthMm: Float?,
    val portrait: Boolean,
) {
    /** One line for the settings screen and logcat: the numbers detection acted on. */
    fun report(thresholdDp: Int): String {
        val physical = widthMm?.let { ", ~%.0fmm".format(it) } ?: ""
        return "sw${smallestWidthDp}dp (${smallestWidthPx}px$physical); " +
                "large at >=${thresholdDp}dp"
    }

    companion object {
        // Below a phone's ~160dpi or above a dense panel's ~900dpi, the value is junk.
        private const val MIN_PLAUSIBLE_DPI = 80f
        private const val MAX_PLAUSIBLE_DPI = 1200f

        fun of(context: Context): ScreenMetrics {
            val conf = context.resources.configuration
            val dm = context.resources.displayMetrics
            // Measure the panel's short side: the same side smallestScreenWidthDp names,
            // so the size half of a profile stays independent of the orientation half.
            val shortIsX = dm.widthPixels <= dm.heightPixels
            val px = if (shortIsX) dm.widthPixels else dm.heightPixels
            val dpi = if (shortIsX) dm.xdpi else dm.ydpi
            val mm = if (dpi in MIN_PLAUSIBLE_DPI..MAX_PLAUSIBLE_DPI) px / dpi * 25.4f else null
            return ScreenMetrics(
                smallestWidthDp = conf.smallestScreenWidthDp,
                smallestWidthPx = px,
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
