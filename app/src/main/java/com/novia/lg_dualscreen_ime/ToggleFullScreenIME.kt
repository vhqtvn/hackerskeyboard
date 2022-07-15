package com.novia.lg_dualscreen_ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import com.lge.ime.util.p118f.DualKeyboardManager.Companion.setContext
import com.lge.ime.util.p118f.LGMultiDisplayUtils.checkForceLandscape
import com.lge.ime.util.p118f.LGMultiDisplayUtils.checkRotation
import me.weishu.reflection.Reflection

object ToggleFullScreenIME {
    private var last_seal_hashcode = -13371337
    fun unseal(context: Context) {
        if (context.hashCode() == last_seal_hashcode) return
        last_seal_hashcode = context.hashCode()
        Reflection.unseal(context)
    }

    fun ToggleSimply(context: Context, isOn: Boolean) {
        unseal(context.applicationContext)
        val a2 = setContext(context)
        if (isOn) {
            a2!!.requestEnableDual()
        } else {
            a2!!.requestCollapse(false)
        }
    }

    fun Toggle(ims: InputMethodService): Boolean {
        unseal(ims.baseContext)
        //        case FunctionCodeConstants.PERFORM_DUAL_KEYBORAD /*{ENCODED_INT: -116}*/:
        return if (ims != null && ims.isInputViewShown) {
            val g = checkForceLandscape(ims)
            val a2 = setContext(ims as Context)
            if (checkRotation(ims)) {
                val z = !g
                //set prefs,ignore
//                PreferenceUtil.m8870b(f4639e, "boolean_dual_keyboard_enabled", z);
//                if (z) {
//                    LDBLoggerManager.sendMessage(this.f4639e, LDBLogEvent.EVENT_MODE_CHANGE, LDBLoggingConstant.DUALKEYBOARD_ON);
//                } else {
//                    LDBLoggerManager.sendMessage(this.f4639e, LDBLogEvent.EVENT_MODE_CHANGE, LDBLoggingConstant.DUALKEYBOARD_OFF);
//                }

                //something not important maybe

//                if (this.f4634I != 0 && !z) {
//                    m6697c(0);
//                }
                if (z) {
                    a2!!.requestEnableDual()
                    true
                } else {
                    a2!!.requestCollapse(false)
                    false
                }
            } else if (g) {
                a2!!.requestCollapse(false)
                false
            } else {
                //something not important maybe
//                m6665D();
                false
            }
        } else {
            false
        }
    }
}