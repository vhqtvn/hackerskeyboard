package com.lge.ime.util.p118f;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import com.lge.ime.util.VersionUtils;

/* renamed from: com.lge.ime.util.f.a */
public class DualKeyboardManager {

    /* renamed from: a */
    private static DualKeyboardManager instance;

    /* renamed from: b */
    private final Context context;

    /* renamed from: c */
    private InputMethodService inputMethodService;

    private DualKeyboardManager(Context context) {
        this.context = context;
    }

    /* renamed from: a */
    public static DualKeyboardManager setContext(Context context) {
        if (context == null) {
            return null;
        }
        Context applicationContext = context.getApplicationContext();
        DualKeyboardManager aVar = instance;
        if (aVar == null || aVar.context != applicationContext) {
            instance = new DualKeyboardManager(applicationContext);
        }
        return instance;
    }

    /* renamed from: b */
    public void requestEnableDual() {
        requestEnableDual(this.context, true);
    }

    /* renamed from: a */
    public static void m8793a() {
        instance = null;
    }

    /* renamed from: a */
    public void setInputMethodService(InputMethodService inputMethodService) {
        this.inputMethodService = inputMethodService;
    }

    /* renamed from: a */
    public void requestCollapse(boolean force) {
        requestEnableDual(this.context, false);
        if (force && inputMethodService != null) {
            inputMethodService.requestHideSelf(0);
        }
    }

    /* renamed from: a */
    private void requestEnableDual(Context context, boolean enable) {
        if (LGMultiDisplayUtils.supportDualScreen()) {
            if (enable) {
                sendIntent(context, "com.lge.android.intent.action.COVER_TOOL_REQUEST_COLLAPSE");
            } else if (VersionUtils.m8758a()) {
                sendIntent(context, "com.lge.ime.TEST_ACTIVITY_END");

            }
            InputMethodManager inputMethodManager = (InputMethodManager) context.getSystemService(Service.INPUT_METHOD_SERVICE);
            try {
                Class.forName("android.view.inputmethod.InputMethodManager")
                        .getMethod("setForceInputMethodLandscape", Boolean.TYPE)
                        .invoke(inputMethodManager, Boolean.valueOf(enable));
                LGMultiDisplayUtils.setCollapsed(enable);
                String simpleName = getClass().getSimpleName();
                Log.i(simpleName, "setForceInputMethodLandscape " + enable);
            } catch (Exception e) {
                String simpleName2 = DualKeyboardManager.class.getSimpleName();
                Log.w(simpleName2, "setForceInputMethodLandscape failed. reason : " + e.toString());
                LGMultiDisplayUtils.setCollapsed(false);
            }
        }
    }

    /* renamed from: a */
    private static void sendIntent(Context context, String str) {
        if (context != null && !TextUtils.isEmpty(str)) {
            Intent intent = new Intent();
            intent.setAction(str);
            context.sendBroadcast(intent);
        }
    }
}