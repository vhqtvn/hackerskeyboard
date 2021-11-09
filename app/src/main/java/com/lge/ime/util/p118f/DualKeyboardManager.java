package com.lge.ime.util.p118f;

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
    private static DualKeyboardManager f6481a;

    /* renamed from: b */
    private final Context f6482b;

    /* renamed from: c */
    private InputMethodService f6483c;

    private DualKeyboardManager(Context context) {
        this.f6482b = context;
    }

    /* renamed from: a */
    public static DualKeyboardManager m8792a(Context context) {
        if (context == null) {
            return null;
        }
        Context applicationContext = context.getApplicationContext();
        DualKeyboardManager aVar = f6481a;
        if (aVar == null || aVar.f6482b != applicationContext) {
            f6481a = new DualKeyboardManager(applicationContext);
        }
        return f6481a;
    }

    /* renamed from: b */
    public void mo6874b() {
        m8795a(this.f6482b, true);
    }

    /* renamed from: a */
    public static void m8793a() {
        f6481a = null;
    }

    /* renamed from: a */
    public void mo6872a(InputMethodService inputMethodService) {
        this.f6483c = inputMethodService;
    }

    /* renamed from: a */
    public void mo6873a(boolean z) {
        InputMethodService inputMethodService;
        m8795a(this.f6482b, false);
        if (z && (inputMethodService = this.f6483c) != null) {
            inputMethodService.requestHideSelf(0);
        }
    }

    /* renamed from: a */
    private void m8795a(Context context, boolean z) {
        if (MultiDisplayUtils.m8816d()) {
            if (z) {
                m8794a(context, "com.lge.android.intent.action.COVER_TOOL_REQUEST_COLLAPSE");
            } else if (VersionUtils.m8758a()) {
                m8794a(context, "com.lge.ime.TEST_ACTIVITY_END");
            }
            InputMethodManager inputMethodManager = (InputMethodManager) context.getSystemService("input_method");
            try {
                Class.forName("android.view.inputmethod.InputMethodManager").getMethod("setForceInputMethodLandscape", Boolean.TYPE).invoke(inputMethodManager, Boolean.valueOf(z));
                MultiDisplayUtils.m8809a(z);
                String simpleName = getClass().getSimpleName();
                Log.i(simpleName, "setForceInputMethodLandscape " + z);
            } catch (Exception e) {
                String simpleName2 = DualKeyboardManager.class.getSimpleName();
                Log.w(simpleName2, "setForceInputMethodLandscape failed. reason : " + e.toString());
                MultiDisplayUtils.m8809a(false);
            }
        }
    }

    /* renamed from: a */
    private static void m8794a(Context context, String str) {
        if (context != null && !TextUtils.isEmpty(str)) {
            Intent intent = new Intent();
            intent.setAction(str);
            context.sendBroadcast(intent);
        }
    }
}