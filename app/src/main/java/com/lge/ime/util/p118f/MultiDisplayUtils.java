package com.lge.ime.util.p118f;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.inputmethod.EditorInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/* renamed from: com.lge.ime.util.f.d */
public class MultiDisplayUtils {

    /* renamed from: a */
    private static EnumC1176a f6493a = EnumC1176a.NOT_INITIALIZED;

    /* renamed from: b */
    private static Boolean f6494b;

    /* renamed from: c */
    private static boolean f6495c = false;

    /* renamed from: d */
    private static boolean f6496d = false;

    /* access modifiers changed from: private */
    /* renamed from: com.lge.ime.util.f.d$a */
    /* compiled from: MultiDisplayUtils */
    public enum EnumC1176a {
        NOT_INITIALIZED,
        SUPPORT,
        NOT_SUPPORT
    }

    /* renamed from: a */
    public static int m8805a() {
        if (!m8816d()) {
            return 0;
        }
        try {
            return ((Integer) m8821g().getMethod("getCurrentFocusDisplayId", new Class[0]).invoke(m8823h(), new Object[0])).intValue();
        } catch (Exception unused) {
            Log.w("MultiDisplayUtils", "Can't get focused display id");
            return 0;
        }
    }

    /* renamed from: b */
    public static boolean m8811b() {
        if (!m8816d()) {
            return false;
        }
        try {
            return ((Boolean) m8821g().getMethod("getForceInputMethodLandscape", new Class[0]).invoke(m8823h(), new Object[0])).booleanValue();
        } catch (Exception unused) {
            Log.w("MultiDisplayUtils", "Can't get forceInputMethodLandscape state");
            return false;
        }
    }

    /* renamed from: d */
    public static boolean m8816d() {
//        boolean z;
//        if (f6493a != EnumC1176a.NOT_INITIALIZED) {
//            return f6493a == EnumC1176a.SUPPORT;
//        }
//        try {
//            if (VersionUtils.m8759b()) {
//                Method method = Class.forName("com.lge.config.Features2").getMethod("multi_display", new Class[0]);
//                method.setAccessible(true);
//                z = ((Boolean) ((Optional) method.invoke(null, new Object[0])).orElse(false)).booleanValue();
//            } else {
//                Field field = Class.forName("com.lge.config.Features").getField("LGE_FEATURE_MULTI_DISPLAY");
//                field.setAccessible(true);
//                z = Boolean.parseBoolean((String) field.get(null));
//            }
//            f6493a = z ? EnumC1176a.SUPPORT : EnumC1176a.NOT_SUPPORT;
//            return z;
//        } catch (Exception unused) {
//            Log.w("MultiDisplayUtils", "Can't get MULTI_DISPLAY feature");
//            f6493a = EnumC1176a.NOT_SUPPORT;
//            return false;
//        }
        return true;
    }

    /* renamed from: e */
    public static boolean m8817e() {
        return f6495c;
    }

    /* renamed from: f */
    public static boolean m8819f() {
        return m8805a() == 1;
    }

    public static boolean m8822g(Context context) {
        return m8826i(context) && m8811b();
    }

    /* renamed from: i */
    public static boolean m8826i(Context context) {
//        Resources resources;
//        FeatureManager a = FeatureManager.m8957a(context);
//        if (a != null && a.mo6926g()) {
//            return false;
//        }
//        if (f6494b == null && (resources = context.getResources()) != null) {
//            f6494b = Boolean.valueOf(resources.getBoolean(R.bool.ime_feature_dual_keyboard));
//        }
//        Boolean bool = f6494b;
//        if (bool == null || !bool.booleanValue()) {
//            return false;
//        }
        return true;
    }

    /* renamed from: h */
    public static boolean m8824h(Context context) {
//        if (context == null || m8828k(context)) {
//            return false;
//        }
//        C1180o a = C1180o.m8909a(context);
//        Display i = a.mo6916i();
//        Display c = a.mo6910c();
//        if (i == null || c == null) {
//            return false;
//        }
//        int a2 = m8805a();
//        boolean z = a2 == 0 && i.getRotation() == 1;
//        boolean z2 = a2 == 1 && c.getRotation() == 3;
//        if (z || z2) {
//            return true;
//        }
//        return false;

        //guess rotation,return true
        return true;
    }



    /* renamed from: g */
    private static Class<?> m8821g() {
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            if (cls.getMethod("getWindowManagerService", new Class[0]).invoke(cls, new Object[0]) != null) {
                return Class.forName("android.view.IWindowManager");
            }
            return null;
        } catch (Exception unused) {
            Log.w("MultiDisplayUtils", "Can't get IWindowManager class");
            return null;
        }
    }

    /* renamed from: a */
    public static void m8807a(Context context) {
        m8808a(context, "com.lge.ime.TEST_ACTIVITY_END");
    }

    /* renamed from: b */
    public static void m8810b(boolean z) {
        f6496d = z;
    }

    /* renamed from: a */
    private static void m8808a(Context context, String str) {
        if (context != null && !TextUtils.isEmpty(str)) {
            Intent intent = new Intent();
            intent.setAction(str);
            context.sendBroadcast(intent);
        }
    }

    /* renamed from: i */
    private static boolean m8825i() {
        try {
            return ((Boolean) m8821g().getMethod("isApplicationTypeWindow", new Class[0]).invoke(m8823h(), new Object[0])).booleanValue();
        } catch (Exception unused) {
            Log.w("MultiDisplayUtils", "Can't get window type");
            return false;
        }
    }

    /* renamed from: c */
    public static boolean m8814c() {
        return f6496d;
    }

    /* renamed from: h */
    private static Object m8823h() {
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            return cls.getMethod("getWindowManagerService", new Class[0]).invoke(cls, new Object[0]);
        } catch (Exception unused) {
            Log.w("MultiDisplayUtils", "Can't get WindowManagerGlobal instance");
            return null;
        }
    }

    /* renamed from: a */
    public static void m8809a(boolean z) {
        f6495c = z;
    }
}