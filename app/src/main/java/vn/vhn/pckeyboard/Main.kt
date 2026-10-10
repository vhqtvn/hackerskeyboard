/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.preference.PreferenceManager
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.TextView.BufferType
import vn.vhn.pckeyboard.InputLanguageSelection
import vn.vhn.pckeyboard.LatinIMESettings

class Main : Activity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        padForSystemBars()
        requestNotificationPermissionIfNeeded()
        var html = getString(R.string.main_body)
        html += "<p><i>Version: " + getString(R.string.auto_version) + "</i></p>"
        val content = Html.fromHtml(html)
        val description = findViewById<View>(R.id.main_description) as TextView
        description.movementMethod = LinkMovementMethod.getInstance()
        description.setText(content, BufferType.SPANNABLE)
        val setup1 = findViewById<View>(R.id.main_setup_btn_configure_imes) as Button
        setup1.setOnClickListener {
            startActivityForResult(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                0)
        }
        val setup2 = findViewById<View>(R.id.main_setup_btn_set_ime) as Button
        setup2.setOnClickListener {
            val mgr = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            mgr.showInputMethodPicker()
        }
        val that: Activity = this
        val setup4 = findViewById<View>(R.id.main_setup_btn_input_lang) as Button
        setup4.setOnClickListener {
            startActivityForResult(Intent(that,
                InputLanguageSelection::class.java), 0)
        }
        val setup5 = findViewById<View>(R.id.main_setup_btn_settings) as Button
        setup5.setOnClickListener {
            startActivityForResult(Intent(that,
                LatinIMESettings::class.java), 0)
        }
        // Device-specific controls (e.g. the AYN Thor deck) are added by the flavor;
        // on generic builds the row stays empty.
        vn.vhn.pckeyboard.device.DeviceIntegration.onMainActivityCreated(
            that, findViewById(R.id.main_device_extras))
    }

    /**
     * The ongoing "tap to open the keyboard" notification needs a runtime permission
     * from API 33. The IME itself has no activity to ask from, so this screen does it,
     * and only when that setting is actually switched on -- there is no reason to
     * prompt someone who does not use the notification.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val wanted = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(LatinIME.PREF_KEYBOARD_NOTIFICATION,
                resources.getBoolean(R.bool.default_keyboard_notification))
        if (!wanted) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS)
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001

        private const val MARKET_URI = "market://search?q=pub:\"Klaus Weidner\""
    }
}