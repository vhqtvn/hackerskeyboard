package com.novia.lg_dualscreen_ime;

import android.content.Context;
import android.inputmethodservice.InputMethodService;

import com.lge.ime.util.p118f.DualKeyboardManager;
import com.lge.ime.util.p118f.LGMultiDisplayUtils;

import me.weishu.reflection.Reflection;

public class ToggleFullScreenIME {

    private static int last_seal_hashcode = -13371337;

    public static void unseal(Context context) {
        if (context.hashCode() == last_seal_hashcode) return;
        last_seal_hashcode = context.hashCode();
        Reflection.unseal(context);
    }

    public static void ToggleSimply(Context context, boolean isOn) {
        unseal(context.getApplicationContext());
        DualKeyboardManager a2 = DualKeyboardManager.setContext(context);
        if (isOn) {
            a2.requestEnableDual();
        } else {
            a2.requestCollapse(false);
        }
    }

    public static boolean Toggle(InputMethodService ims) {
        unseal(ims.getBaseContext());
//        case FunctionCodeConstants.PERFORM_DUAL_KEYBORAD /*{ENCODED_INT: -116}*/:
        InputMethodService inputMethodService = ims;
        if (inputMethodService != null && inputMethodService.isInputViewShown()) {
            boolean g = LGMultiDisplayUtils.checkForceLandscape(ims);
            DualKeyboardManager a2 = DualKeyboardManager.setContext((Context) ims);
            if (LGMultiDisplayUtils.checkRotation(ims)) {
                boolean z = !g;
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
                    a2.requestEnableDual();
                    return true;
                } else {
                    a2.requestCollapse(false);
                    return false;
                }
            } else if (g) {
                a2.requestCollapse(false);
                return false;
            } else {
                //something not important maybe
//                m6665D();
                return false;
            }
        } else {
            return false;
        }
    }
}
