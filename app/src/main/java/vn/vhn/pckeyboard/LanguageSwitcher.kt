/*
 * Copyright (C) 2010 Google Inc.
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

import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.Log
import java.util.*

/**
 * Keeps track of list of selected input languages and the current
 * input language that the user has selected.
 */
class LanguageSwitcher(private val mIme: LatinIME) {
    var locales: Array<Locale?>
        private set
    private var mSelectedLanguageArray: Array<String> = arrayOf()
    private var mSelectedLanguages: String? = null
    private var mCurrentIndex = 0
    private var mDefaultInputLanguage: String? = null
    private lateinit var mDefaultInputLocale: Locale
    private var mSystemLocale: Locale? = null
    val localeCount: Int
        get() = locales.size

    /**
     * Loads the currently selected input languages from shared preferences.
     * @param sp
     * @return whether there was any change
     */
    fun loadLocales(sp: SharedPreferences): Boolean {
        val selectedLanguages = sp.getString(LatinIME.PREF_SELECTED_LANGUAGES, null)
        val currentLanguage = sp.getString(LatinIME.PREF_INPUT_LANGUAGE, null)
        if (selectedLanguages == null || selectedLanguages.length < 1) {
            loadDefaults()
            if (locales.size == 0) {
                return false
            }
            locales = arrayOfNulls(0)
            return true
        }
        if (selectedLanguages == mSelectedLanguages) {
            return false
        }
        mSelectedLanguageArray = selectedLanguages.split(",").toTypedArray()
        mSelectedLanguages = selectedLanguages // Cache it for comparison later
        constructLocales()
        mCurrentIndex = 0
        if (currentLanguage != null) {
            // Find the index
            mCurrentIndex = 0
            for (i in locales.indices) {
                if (mSelectedLanguageArray[i] == currentLanguage) {
                    mCurrentIndex = i
                    break
                }
            }
            // If we didn't find the index, use the first one
        }
        return true
    }

    private fun loadDefaults() {
        mDefaultInputLocale = mIme.resources.configuration.locale
        val country = mDefaultInputLocale.getCountry()
        mDefaultInputLanguage = mDefaultInputLocale.getLanguage() +
                if (TextUtils.isEmpty(country)) "" else "_$country"
    }

    private fun constructLocales() {
        locales = arrayOfNulls(mSelectedLanguageArray.size)
        for (i in locales.indices) {
            val lang = mSelectedLanguageArray[i]
            if (lang == "") continue
            locales[i] = Locale(lang.substring(0, 2),
                if (lang.length > 4) lang.substring(3, 5) else "")
        }
    }

    /**
     * Returns the currently selected input language code, or the display language code if
     * no specific locale was selected for input.
     */
    fun getInputLanguage(): String? {
        return if (localeCount == 0) mDefaultInputLanguage else mSelectedLanguageArray[mCurrentIndex]
    }

    fun allowAutoCap(): Boolean {
        var lang = getInputLanguage()
        if (lang!!.length > 2) lang = lang.substring(0, 2)
        return !InputLanguageSelection.NOCAPS_LANGUAGES.contains(lang)
    }

    fun allowDeadKeys(): Boolean {
        var lang = getInputLanguage()
        if (lang!!.length > 2) lang = lang.substring(0, 2)
        return !InputLanguageSelection.NODEADKEY_LANGUAGES.contains(lang)
    }

    fun allowAutoSpace(): Boolean {
        var lang = getInputLanguage()
        if (lang!!.length > 2) lang = lang.substring(0, 2)
        return !InputLanguageSelection.NOAUTOSPACE_LANGUAGES.contains(lang)
    }

    /**
     * Returns the list of enabled language codes.
     */
    fun getEnabledLanguages(): Array<String> {
        return mSelectedLanguageArray
    }

    /**
     * Returns the currently selected input locale, or the display locale if no specific
     * locale was selected for input.
     * @return
     */
    val inputLocale: Locale?
        get() {
            val locale: Locale?
            locale = if (localeCount == 0) {
                mDefaultInputLocale
            } else {
                locales[mCurrentIndex]
            }
            LatinIME.sKeyboardSettings.inputLocale = locale ?: Locale.getDefault()
            return locale
        }

    /**
     * Returns the next input locale in the list. Wraps around to the beginning of the
     * list if we're at the end of the list.
     * @return
     */
    val nextInputLocale: Locale?
        get() = if (localeCount == 0) mDefaultInputLocale else locales[(mCurrentIndex + 1) % locales.size]

    /**
     * Sets the system locale (display UI) used for comparing with the input language.
     * @param locale the locale of the system
     */
    fun setSystemLocale(locale: Locale?) {
        mSystemLocale = locale
    }

    /**
     * Returns the system locale.
     * @return the system locale
     */
    val systemLocale: Locale?
        get() = mSystemLocale

    /**
     * Returns the previous input locale in the list. Wraps around to the end of the
     * list if we're at the beginning of the list.
     * @return
     */
    val prevInputLocale: Locale?
        get() = if (localeCount == 0) mDefaultInputLocale else locales[(mCurrentIndex - 1 + locales.size) % locales.size]

    fun reset() {
        mCurrentIndex = 0
        mSelectedLanguages = ""
        loadLocales(PreferenceManager.getDefaultSharedPreferences(mIme))
    }

    operator fun next() {
        mCurrentIndex++
        if (mCurrentIndex >= locales.size) mCurrentIndex = 0 // Wrap around
    }

    fun prev() {
        mCurrentIndex--
        if (mCurrentIndex < 0) mCurrentIndex = locales.size - 1 // Wrap around
    }

    fun persist() {
        val sp = PreferenceManager.getDefaultSharedPreferences(mIme)
        val editor = sp.edit()
        editor.putString(LatinIME.PREF_INPUT_LANGUAGE, getInputLanguage())
        SharedPreferencesCompat.apply(editor)
    }

    companion object {
        private const val TAG = "HK/LanguageSwitcher"
        fun toTitleCase(s: String): String {
            return if (s.length == 0) {
                s
            } else s[0].uppercaseChar().toString() + s.substring(1)
        }
    }

    init {
        locales = arrayOfNulls(0)
    }
}