package vn.vhn.pckeyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen

/**
 * Per-profile layout overrides: a picker for which profile you are editing, and one set of
 * controls writing keys suffixed with that profile ("settings_height@ll" and so on). A
 * setting never touched is simply absent and [ProfilePrefs] falls back to the general one.
 *
 * Built in code rather than from XML because the four profiles differ only in that suffix.
 * As four XML blocks it was 297 lines of near-duplicate, and every new per-profile setting
 * had to be added four times -- which is exactly how one of them came to be missing before.
 */
class PrefScreenProfiles : SettingsFragment() {

    private var editing: KeyboardProfile = KeyboardProfile.SMALL_PORTRAIT

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        editing = activeProfile()
        preferenceScreen = buildScreen()
    }

    override fun onResume() {
        super.onResume()
        updateExplanation()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        super.onSharedPreferenceChanged(prefs, key)
        // The keyboard re-measures on its own listener; whichever of us runs first, the
        // report formats from the threshold as it stands, so it cannot go stale.
        updateExplanation()
    }

    // --- the screen ---------------------------------------------------------------

    private fun buildScreen(): PreferenceScreen {
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)

        screen.addPreference(Preference(ctx).apply {
            key = KEY_EXPLAIN
            isSelectable = false
            isPersistent = false
        })

        screen.addPreference(slider(ctx, LatinIME.PREF_LARGE_SCREEN_DP,
            R.string.title_large_screen_dp, R.string.summary_large_screen_dp,
            default = "600", min = 320f, max = 1200f, step = 10f, format = "%.0fdp"))

        screen.addPreference(AutoSummaryListPreference(ctx).apply {
            key = KEY_EDITING
            isPersistent = false
            setTitle(R.string.title_profile_pick)
            entries = KeyboardProfile.entries.map { getString(nameOf(it)) }.toTypedArray()
            entryValues = KeyboardProfile.entries.map { it.suffix }.toTypedArray()
            value = editing.suffix
            setOnPreferenceChangeListener { _, newValue ->
                editing = KeyboardProfile.entries.first { it.suffix == newValue }
                preferenceScreen = buildScreen()
                updateExplanation()
                true
            }
        })

        screen.addPreference(PreferenceCategory(ctx).apply {
            title = getString(nameOf(editing)).let {
                if (editing == activeProfile()) getString(R.string.profile_active_marker, it) else it
            }
            summary = getString(R.string.summary_profile_pick)
        }.also { category ->
            screen.addPreference(category)   // must be attached before children are added
            category.addPreference(heightSlider(ctx))
            category.addPreference(list(ctx, LatinIME.PREF_KEYBOARD_MODE,
                R.string.title_profile_keyboard_mode,
                R.array.profile_keyboard_mode_entries, R.array.profile_keyboard_mode_values))
            category.addPreference(check(ctx, LatinIME.PREF_FKEY_ROW,
                R.string.title_profile_fkey_row, R.string.summary_profile_fkey_row, true))
            category.addPreference(check(ctx, LatinIME.PREF_EXTENSION_ROW,
                R.string.title_profile_extension_row, R.string.summary_profile_extension_row,
                false))
            category.addPreference(slider(ctx, keyFor(LatinIME.PREF_SPLIT_GAP),
                R.string.title_profile_split_gap, R.string.summary_profile_split_gap,
                default = "0", min = 0f, max = 60f, step = 1f, format = "%.0f%%"))
            category.addPreference(slider(ctx, keyFor(LatinIME.PREF_CORE_PADDING),
                R.string.title_profile_core_padding, R.string.summary_profile_core_padding,
                default = "0", min = 0f, max = 5f, step = 1f, format = "%.0f%%"))
            category.addPreference(list(ctx, LatinIME.PREF_HINT_MODE,
                R.string.title_hint_mode,
                R.array.profile_hint_mode_entries, R.array.profile_hint_mode_values))
        })
        return screen
    }

    private fun keyFor(base: String) = ProfilePrefs.key(base, editing)

    private fun slider(
        ctx: Context, prefKey: String, title: Int, summary: Int?,
        default: String, min: Float, max: Float, step: Float, format: String,
    ) = SeekBarPreferenceString(ctx).apply {
        key = prefKey
        setTitle(title)
        summary?.let { setSummary(it) }
        configure(min, max, step, displayFormat = format)
        // Passed through as written: some of these defaults carry their unit, and
        // SeekBarPreferenceString is the thing that knows how to read that.
        setDefaultValue(default)
    }

    /**
     * The height slider also says when it is showing a value it inherited rather than one
     * stored for this profile, which is otherwise indistinguishable from a chosen value.
     */
    private fun heightSlider(ctx: Context): SeekBarPreferenceString {
        val portrait = editing.isPortrait
        val legacyKey =
            if (portrait) LatinIME.PREF_HEIGHT_PORTRAIT else LatinIME.PREF_HEIGHT_LANDSCAPE
        val legacyDefault = getString(
            if (portrait) R.string.default_height_portrait else R.string.default_height_landscape)
        val pref = slider(ctx, keyFor(LatinIME.PREF_HEIGHT), R.string.title_profile_height, null,
            default = legacyDefault, min = 15f, max = 75f, step = 1f, format = "%.0f%%")
        val prefs = preferenceManager.sharedPreferences
        if (prefs != null && !prefs.contains(pref.key)) {
            pref.inheritedNote = getString(R.string.profile_inherited_note)
            pref.setDisplayedValue(ProfilePrefs.getInt(prefs, LatinIME.PREF_HEIGHT, editing,
                legacyKey = legacyKey,
                default = LatinIME.getHeight(prefs, legacyKey, legacyDefault)).toFloat())
        }
        return pref
    }

    private fun list(ctx: Context, base: String, title: Int, entries: Int, values: Int) =
        AutoSummaryListPreference(ctx).apply {
            key = keyFor(base)
            setTitle(title)
            setEntries(entries)
            setEntryValues(values)
            setDefaultValue("-1")
        }

    private fun check(ctx: Context, base: String, title: Int, summary: Int, default: Boolean) =
        CheckBoxPreference(ctx).apply {
            key = keyFor(base)
            setTitle(title)
            setSummary(summary)
            setDefaultValue(default)
        }

    // --- detection report ----------------------------------------------------------

    /**
     * Spells out what the detection actually saw. Profile detection is the one thing here
     * that can look simply broken -- a screen classed as the wrong size silently applies
     * the wrong layout -- and the numbers behind it are invisible otherwise.
     *
     * Prefers the values the *keyboard* resolved over this screen's own: in multi-window
     * the two Configurations need not agree, and when they disagree that is precisely the
     * thing worth seeing.
     */
    private fun updateExplanation() {
        val pref = findPreference<Preference>(KEY_EXPLAIN) ?: return
        val ctx = context ?: return
        val threshold = thresholdDp()
        val fromKeyboard = LatinIME.sKeyboardSettings.activeMetrics
        val metrics = fromKeyboard ?: ScreenMetrics.of(ctx)
        val name = getString(nameOf(KeyboardProfile.of(metrics, threshold)))
        pref.summary = getString(R.string.profile_explain) + "\n\n" + if (fromKeyboard != null) {
            getString(R.string.profile_detected, name, metrics.report(threshold))
        } else {
            getString(R.string.profile_detected_here, name, metrics.report(threshold))
        }
    }

    private fun nameOf(p: KeyboardProfile) = when (p) {
        KeyboardProfile.SMALL_PORTRAIT -> R.string.profile_small_portrait
        KeyboardProfile.SMALL_LANDSCAPE -> R.string.profile_small_landscape
        KeyboardProfile.LARGE_PORTRAIT -> R.string.profile_large_portrait
        KeyboardProfile.LARGE_LANDSCAPE -> R.string.profile_large_landscape
    }

    private fun thresholdDp(): Int = preferenceManager.sharedPreferences
        ?.getString(LatinIME.PREF_LARGE_SCREEN_DP, null)?.trim()?.toFloatOrNull()?.toInt()
        ?: KeyboardProfile.DEFAULT_LARGE_WIDTH_DP

    private fun activeProfile(): KeyboardProfile = KeyboardProfile.of(
        LatinIME.sKeyboardSettings.activeMetrics
            ?: ScreenMetrics.of(preferenceManager.context), thresholdDp())

    private companion object {
        const val KEY_EXPLAIN = "profile_explain"
        const val KEY_EDITING = "profile_editing"
    }
}
