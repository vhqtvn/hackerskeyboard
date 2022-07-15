/*
 * Copyright (C) 2008-2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package vn.vhn.pckeyboard

import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.PreferenceActivity
import android.preference.PreferenceGroup
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.*
import java.text.Collator
import java.util.*

class InputLanguageSelection : PreferenceActivity() {
    private var mAvailableLanguages = ArrayList<Loc?>()

    companion object {
        private const val TAG = "PCKeyboardILS"
        private val BLACKLIST_LANGUAGES = arrayOf(
            "ko", "ja", "zh"
        )

        // Languages for which auto-caps should be disabled
        val NOCAPS_LANGUAGES: MutableSet<String?> = HashSet()

        // Languages which should not use dead key logic. The modifier is entered after the base character.
        val NODEADKEY_LANGUAGES: MutableSet<String?> = HashSet()

        // Languages which should not auto-add space after completions
        val NOAUTOSPACE_LANGUAGES: MutableSet<String?> = HashSet()

        // Run the GetLanguages.sh script to update the following lists based on
        // the available keyboard resources and dictionaries.
        private val KBD_LOCALIZATIONS = arrayOf(
            "ar", "bg", "bg_ST", "ca", "cs", "cs_QY", "da", "de", "de_NE",
            "el", "en", "en_CX", "en_DV", "en_GB", "es", "es_LA", "es_US",
            "fa", "fi", "fr", "fr_CA", "he", "hr", "hu", "hu_QY", "hy", "in",
            "it", "iw", "ja", "ka", "ko", "lo", "lt", "lv", "nb", "nl", "pl",
            "pt", "pt_PT", "rm", "ro", "ru", "ru_PH", "si", "sk", "sk_QY", "sl",
            "sr", "sv", "ta", "th", "tl", "tr", "uk", "vi", "zh_CN", "zh_TW"
        )
        private val KBD_5_ROW = arrayOf(
            "ar", "bg", "bg_ST", "cs", "cs_QY", "da", "de", "de_NE", "el",
            "en", "en_CX", "en_DV", "en_GB", "es", "es_LA", "fa", "fi", "fr",
            "fr_CA", "he", "hr", "hu", "hu_QY", "hy", "it", "iw", "lo", "lt",
            "nb", "pt_PT", "ro", "ru", "ru_PH", "si", "sk", "sk_QY", "sl",
            "sr", "sv", "ta", "th", "tr", "uk"
        )
        private val KBD_4_ROW = arrayOf(
            "ar", "bg", "bg_ST", "cs", "cs_QY", "da", "de", "de_NE", "el",
            "en", "en_CX", "en_DV", "es", "es_LA", "es_US", "fa", "fr", "fr_CA",
            "he", "hr", "hu", "hu_QY", "iw", "nb", "ru", "ru_PH", "sk", "sk_QY",
            "sl", "sr", "sv", "tr", "uk"
        )

        private fun getLocaleName(l: Locale): String {
            val lang = l.language
            val country = l.country
            return if (lang == "en" && country == "DV") {
                "English (Dvorak)"
            } else if (lang == "en" && country == "EX") {
                "English (4x11)"
            } else if (lang == "en" && country == "CX") {
                "English (Carpalx)"
            } else if (lang == "es" && country == "LA") {
                "Español (Latinoamérica)"
            } else if (lang == "cs" && country == "QY") {
                "Čeština (QWERTY)"
            } else if (lang == "de" && country == "NE") {
                "Deutsch (Neo2)"
            } else if (lang == "hu" && country == "QY") {
                "Magyar (QWERTY)"
            } else if (lang == "sk" && country == "QY") {
                "Slovenčina (QWERTY)"
            } else if (lang == "ru" && country == "PH") {
                "Русский (Phonetic)"
            } else if (lang == "bg") {
                if (country == "ST") {
                    "български език (Standard)"
                } else {
                    "български език (Phonetic)"
                }
            } else {
                LanguageSwitcher.Companion.toTitleCase(l.getDisplayName(l))
            }
        }

        private fun asString(set: Set<String>): String {
            val out = StringBuilder()
            out.append("set(")
            var parts = set.toTypedArray()
            Arrays.sort(parts)
            for (i in parts.indices) {
                if (i > 0) out.append(", ")
                out.append(parts[i])
            }
            out.append(")")
            return out.toString()
        }

        init {
            NOCAPS_LANGUAGES.add("ar")
            NOCAPS_LANGUAGES.add("iw")
            NOCAPS_LANGUAGES.add("th")
        }

        init {
            NODEADKEY_LANGUAGES.add("ar")
            NODEADKEY_LANGUAGES.add("iw") // TODO: currently no niqqud in the keymap?
            NODEADKEY_LANGUAGES.add("th")
        }

        init {
            NOAUTOSPACE_LANGUAGES.add("th")
        }
    }

    class Loc(var label: String, var locale: Locale) : Comparable<Any> {
        override fun toString(): String {
            return label
        }

        override fun compareTo(o: Any): Int {
            return sCollator.compare(label, (o as Loc).label)
        }

        companion object {
            var sCollator = Collator.getInstance()
        }
    }

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        addPreferencesFromResource(R.xml.language_prefs)
        // Get the settings preferences
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedLanguagePref = sp.getString(LatinIME.Companion.PREF_SELECTED_LANGUAGES, "")
        Log.i(TAG, "selected languages: $selectedLanguagePref")
        val languageList = selectedLanguagePref!!.split(",").toTypedArray()
        mAvailableLanguages = getUniqueLocales()

        // Compatibility hack for v1.22 and older - if a selected language 5-code isn't
        // found in the current list of available languages, try adding the 2-letter
        // language code. For example, "en_US" is no longer listed, so use "en" instead.
        val availableLanguages: MutableSet<String> = HashSet()
        for (i in mAvailableLanguages.indices) {
            val locale = mAvailableLanguages[i]!!.locale
            availableLanguages.add(get5Code(locale))
        }
        val languageSelections: MutableSet<String> = HashSet()
        for (i in languageList.indices) {
            val spec = languageList[i]
            if (availableLanguages.contains(spec)) {
                languageSelections.add(spec)
            } else if (spec.length > 2) {
                val lang = spec.substring(0, 2)
                if (availableLanguages.contains(lang)) languageSelections.add(lang)
            }
        }
        val parent: PreferenceGroup = preferenceScreen
        for (i in mAvailableLanguages.indices) {
            val pref = CheckBoxPreference(this)
            val locale = mAvailableLanguages[i]!!.locale
            pref.title = mAvailableLanguages[i]!!.label +
                    " [" + locale.toString() + "]"
            val fivecode = get5Code(locale)
            val language = locale.language
            val checked = languageSelections.contains(fivecode)
            pref.isChecked = checked
            val has4Row = arrayContains(KBD_4_ROW, fivecode) || arrayContains(KBD_4_ROW, language)
            val has5Row = arrayContains(KBD_5_ROW, fivecode) || arrayContains(KBD_5_ROW, language)
            val summaries: MutableList<String> = ArrayList(3)
            if (has5Row) summaries.add("5-row")
            if (has4Row) summaries.add("4-row")
            if (!summaries.isEmpty()) {
                val summary = StringBuilder()
                for (j in summaries.indices) {
                    if (j > 0) summary.append(", ")
                    summary.append(summaries[j])
                }
                pref.summary = summary.toString()
            }
            parent.addPreference(pref)
        }
    }

    private fun get5Code(locale: Locale): String {
        val country = locale.country
        return (locale.language
                + if (TextUtils.isEmpty(country)) "" else "_$country")
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Save the selected languages
        var checkedLanguages: String? = ""
        val parent: PreferenceGroup = preferenceScreen
        val count = parent.preferenceCount
        for (i in 0 until count) {
            val pref = parent.getPreference(i) as CheckBoxPreference
            if (pref.isChecked) {
                val locale = mAvailableLanguages[i]!!.locale
                checkedLanguages += get5Code(locale) + ","
            }
        }
        if (checkedLanguages!!.length < 1) checkedLanguages = null // Save null
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = sp.edit()
        editor.putString(LatinIME.Companion.PREF_SELECTED_LANGUAGES, checkedLanguages)
        SharedPreferencesCompat.apply(editor)
    }

    fun getUniqueLocales(): ArrayList<Loc?> {
        val localeSet: MutableSet<String> = HashSet()
        val langSet: Set<String> = HashSet()
        // Ignore the system (asset) locale list, it's inconsistent and incomplete
//        String[] sysLocales = getAssets().getLocales();
//        
//        // First, add zz_ZZ style full language+country locales
//        for (int i = 0; i < sysLocales.length; ++i) {
//        	String sl = sysLocales[i];
//        	if (sl.length() != 5) continue;
//        	localeSet.add(sl);
//        	langSet.add(sl.substring(0, 2));
//        }
//        
//        // Add entries for system languages without country, but only if there's
//        // no full locale for that language yet.
//        for (int i = 0; i < sysLocales.length; ++i) {
//        	String sl = sysLocales[i];
//        	if (sl.length() != 2 || langSet.contains(sl)) continue;
//        	localeSet.add(sl);
//        }

        // Add entries for additional languages supported by the keyboard.
        for (i in KBD_LOCALIZATIONS.indices) {
            var kl = KBD_LOCALIZATIONS[i]
            if (kl.length == 2 && langSet.contains(kl)) continue
            // replace zz_rYY with zz_YY
            if (kl.length == 6) kl = kl.substring(0, 2) + "_" + kl.substring(4, 6)
            localeSet.add(kl)
        }
        Log.i(TAG, "localeSet=" + asString(localeSet))
        Log.i(TAG, "langSet=" + asString(langSet))

        // Now build the locale list for display
        var locales = localeSet.toTypedArray()
        Arrays.sort(locales)
        val uniqueLocales = ArrayList<Loc?>()
        val origSize = locales.size
        val preprocess = arrayOfNulls<Loc>(origSize)
        var finalSize = 0
        for (i in 0 until origSize) {
            val s = locales[i]
            val len = s!!.length
            if (len == 2 || len == 5 || len == 6) {
                val language = s.substring(0, 2)
                var l: Locale
                l = if (len == 5) {
                    // zz_YY
                    val country = s.substring(3, 5)
                    Locale(language, country)
                } else if (len == 6) {
                    // zz_rYY
                    Locale(language, s.substring(4, 6))
                } else {
                    Locale(language)
                }

                // Exclude languages that are not relevant to LatinIME
                if (arrayContains(BLACKLIST_LANGUAGES, language)) continue
                if (finalSize == 0) {
                    preprocess[finalSize++] =
                        Loc(LanguageSwitcher.Companion.toTitleCase(l.getDisplayName(l)), l)
                } else {
                    // check previous entry:
                    //  same lang and a country -> upgrade to full name and
                    //    insert ours with full name
                    //  diff lang -> insert ours with lang-only name
                    if (preprocess[finalSize - 1]!!.locale.language ==
                        language
                    ) {
                        preprocess[finalSize - 1]!!.label = getLocaleName(
                            preprocess[finalSize - 1]!!.locale)
                        preprocess[finalSize++] = Loc(getLocaleName(l), l)
                    } else {
                        var displayName: String
                        if (s == "zz_ZZ") {
                        } else {
                            displayName = getLocaleName(l)
                            preprocess[finalSize++] = Loc(displayName, l)
                        }
                    }
                }
            }
        }
        for (i in 0 until finalSize) {
            uniqueLocales.add(preprocess[i])
        }
        return uniqueLocales
    }

    private fun arrayContains(array: Array<String>, value: String): Boolean {
        for (i in array.indices) {
            if (array[i].equals(value, ignoreCase = true)) return true
        }
        return false
    }
}