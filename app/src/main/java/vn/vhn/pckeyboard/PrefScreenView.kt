/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.backup.BackupManager
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.preference.ListPreference
import android.preference.PreferenceActivity

class PrefScreenView : PreferenceActivity(), OnSharedPreferenceChangeListener {
    private var mRenderModePreference: ListPreference? = null
    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        addPreferencesFromResource(R.xml.prefs_view)
        val prefs = preferenceManager.sharedPreferences
        prefs.registerOnSharedPreferenceChangeListener(this)
        mRenderModePreference =
            findPreference(LatinIME.Companion.PREF_RENDER_MODE) as ListPreference
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        BackupManager(this).dataChanged()
    }

    override fun onResume() {
        super.onResume()
        if (LatinKeyboardBaseView.Companion.sSetRenderMode == null) {
            mRenderModePreference!!.isEnabled = false
            mRenderModePreference!!.setSummary(R.string.render_mode_unavailable)
        }
    }
}