package com.lge.ime.util.p118f

import android.app.Service
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.TextUtils
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.lge.ime.util.VersionUtils
import com.lge.ime.util.p118f.DualKeyboardManager

/* renamed from: com.lge.ime.util.f.a */
class DualKeyboardManager private constructor(
    /* renamed from: b */
    private val context: Context
) {
    /* renamed from: c */
    private var inputMethodService: InputMethodService? = null

    /* renamed from: b */
    fun requestEnableDual() {
        requestEnableDual(context, true)
    }

    /* renamed from: a */
    fun setInputMethodService(inputMethodService: InputMethodService?) {
        this.inputMethodService = inputMethodService
    }

    /* renamed from: a */
    fun requestCollapse(force: Boolean) {
        requestEnableDual(context, false)
        if (force && inputMethodService != null) {
            inputMethodService!!.requestHideSelf(0)
        }
    }

    /* renamed from: a */
    private fun requestEnableDual(context: Context, enable: Boolean) {
        if (LGMultiDisplayUtils.supportDualScreen()) {
            if (enable) {
                sendIntent(context, "com.lge.android.intent.action.COVER_TOOL_REQUEST_COLLAPSE")
            } else if (VersionUtils.m8758a()) {
                sendIntent(context, "com.lge.ime.TEST_ACTIVITY_END")
            }
            val inputMethodManager =
                context.getSystemService(Service.INPUT_METHOD_SERVICE) as InputMethodManager
            try {
                Class.forName("android.view.inputmethod.InputMethodManager")
                    .getMethod("setForceInputMethodLandscape", java.lang.Boolean.TYPE)
                    .invoke(inputMethodManager, java.lang.Boolean.valueOf(enable))
                LGMultiDisplayUtils.isCollapsed = enable
                val simpleName = javaClass.simpleName
                Log.i(simpleName, "setForceInputMethodLandscape $enable")
            } catch (e: Exception) {
                val simpleName2 = DualKeyboardManager::class.java.simpleName
                Log.w(simpleName2, "setForceInputMethodLandscape failed. reason : $e")
                LGMultiDisplayUtils.isCollapsed = false
            }
        }
    }

    companion object {
        /* renamed from: a */
        private var instance: DualKeyboardManager? = null

        /* renamed from: a */
        @JvmStatic
        fun setContext(context: Context?): DualKeyboardManager? {
            if (context == null) {
                return null
            }
            val applicationContext = context.applicationContext
            val aVar = instance
            if (aVar == null || aVar.context !== applicationContext) {
                instance = DualKeyboardManager(applicationContext)
            }
            return instance
        }

        /* renamed from: a */
        fun m8793a() {
            instance = null
        }

        /* renamed from: a */
        private fun sendIntent(context: Context?, str: String) {
            if (context != null && !TextUtils.isEmpty(str)) {
                val intent = Intent()
                intent.action = str
                context.sendBroadcast(intent)
            }
        }
    }
}