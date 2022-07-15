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

import android.app.Activity
import android.content.Intent
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
    }

    companion object {
        private const val MARKET_URI = "market://search?q=pub:\"Klaus Weidner\""
    }
}