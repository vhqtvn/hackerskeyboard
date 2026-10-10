package vn.vhn.pckeyboard

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import android.content.pm.PackageManager
import android.text.InputType

/**
 * The settings. One activity hosting the screens as fragments, rather than the five
 * separate `PreferenceActivity`s this used to be: those were on the framework's
 * `android.preference`, deprecated since API 29, which declared no theme of its own and so
 * came up white whatever the system was set to -- next to a keyboard that draws itself
 * black.
 */
class LatinIMESettings : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        padForSystemBars()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, RootFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /** A screen that links to another opens it as a fragment on the back stack. */
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat, pref: Preference,
    ): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader, pref.fragment ?: return false)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            title = getString(R.string.english_ime_settings)
            return true
        }
        return super.onSupportNavigateUp()
    }

    /** The top-level screen. */
    class RootFragment : SettingsFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
        }

        override fun onResume() {
            super.onResume()
            findPreference<Preference>("label_version")?.summary = version()
            findPreference<Preference>(INPUT_CONNECTION_INFO)?.summary = String.format(
                "%s type=%s",
                LatinIME.sKeyboardSettings.editorPackageName,
                inputTypeDesc(LatinIME.sKeyboardSettings.editorInputType))
        }

        private fun version(): String = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    companion object {
        const val PREF_SETTINGS_KEY = "settings_key"
        const val INPUT_CONNECTION_INFO = "input_connection_info"

        private val INPUT_CLASSES = mapOf(
            0x00000000 to "NULL", 0x00000001 to "TEXT", 0x00000002 to "NUMBER",
            0x00000003 to "PHONE", 0x00000004 to "DATETIME")
        private val DATETIME_VARIATIONS = mapOf(0x00000010 to "DATE", 0x00000020 to "TIME")
        private val NUMBER_VARIATIONS = mapOf(0x00000010 to "PASSWORD")
        private val TEXT_VARIATIONS = mapOf(
            0x00000010 to "URI", 0x00000020 to "EMAIL_ADDRESS", 0x00000030 to "EMAIL_SUBJECT",
            0x00000040 to "SHORT_MESSAGE", 0x00000050 to "LONG_MESSAGE",
            0x00000060 to "PERSON_NAME", 0x00000070 to "POSTAL_ADDRESS",
            0x00000080 to "PASSWORD", 0x00000090 to "VISIBLE_PASSWORD",
            0x000000a0 to "WEB_EDIT_TEXT", 0x000000b0 to "FILTER", 0x000000c0 to "PHONETIC",
            0x000000d0 to "WEB_EMAIL_ADDRESS", 0x000000e0 to "WEB_PASSWORD")

        private fun addBit(buf: StringBuilder, bit: Int, str: String) {
            if (bit != 0) buf.append("|").append(str)
        }

        /** Spells out an EditorInfo.inputType, for working out what a field is asking for. */
        fun inputTypeDesc(type: Int): String {
            val cls = type and 0x0000000f
            val flags = type and 0x00fff000
            val variation = type and 0x00000ff0
            val out = StringBuilder(INPUT_CLASSES[cls] ?: "?")
            when (cls) {
                InputType.TYPE_CLASS_TEXT -> {
                    TEXT_VARIATIONS[variation]?.let { out.append(".").append(it) }
                    addBit(out, flags and 0x00010000, "AUTO_COMPLETE")
                    addBit(out, flags and 0x00008000, "AUTO_CORRECT")
                    addBit(out, flags and 0x00001000, "CAP_CHARACTERS")
                    addBit(out, flags and 0x00004000, "CAP_SENTENCES")
                    addBit(out, flags and 0x00002000, "CAP_WORDS")
                    addBit(out, flags and 0x00040000, "IME_MULTI_LINE")
                    addBit(out, flags and 0x00020000, "MULTI_LINE")
                    addBit(out, flags and 0x00080000, "NO_SUGGESTIONS")
                }
                InputType.TYPE_CLASS_NUMBER -> {
                    NUMBER_VARIATIONS[variation]?.let { out.append(".").append(it) }
                    addBit(out, flags and 0x00002000, "DECIMAL")
                    addBit(out, flags and 0x00001000, "SIGNED")
                }
                InputType.TYPE_CLASS_DATETIME ->
                    DATETIME_VARIATIONS[variation]?.let { out.append(".").append(it) }
            }
            return out.toString()
        }
    }
}
