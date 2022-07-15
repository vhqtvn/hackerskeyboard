package com.lge.ime.util.p118f

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log

/* renamed from: com.lge.ime.util.f.d */
object LGMultiDisplayUtils {
    /* renamed from: a */
    private val f6493a = EnumC1176a.NOT_INITIALIZED

    /* renamed from: b */
    private val f6494b: Boolean? = null

    /* renamed from: e *//* renamed from: a */
    /* renamed from: c */
    var isCollapsed = false

    /* renamed from: c *//* renamed from: b */
    /* renamed from: d */
    var isMoveToDisplayEx = false

    /* renamed from: a */
    val currentFocusDisplayId: Int
        get() = if (!supportDualScreen()) {
            0
        } else try {
            (windowManagerGlobal!!.getMethod("getCurrentFocusDisplayId", *arrayOfNulls(0)).invoke(
                windowManagerGlobalObject, *arrayOfNulls(0)) as Int).toInt()
        } catch (unused: Exception) {
            Log.w("MultiDisplayUtils", "Can't get focused display id")
            0
        }

    /* renamed from: b */
    val forceInputMethodLandScape: Boolean
        get() = if (!supportDualScreen()) {
            false
        } else try {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (windowManagerGlobal!!.getMethod("getForceInputMethodLandscape", *arrayOfNulls(0))
                .invoke(
                    windowManagerGlobalObject,
                    *arrayOfNulls(0)) as? java.lang.Boolean)?.booleanValue() ?: false
        } catch (unused: Exception) {
            Log.w("MultiDisplayUtils", "Can't get forceInputMethodLandscape state")
            false
        }

    /* renamed from: d */
    @JvmStatic
    fun supportDualScreen(): Boolean {
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
        return Build.MODEL == "special"
    }

    /* renamed from: f */
    fun m8819f(): Boolean {
        return currentFocusDisplayId == 1
    }

    @JvmStatic
    fun checkForceLandscape(context: Context?): Boolean {
        return checkFeatureDualKeyboard(context) && forceInputMethodLandScape
    }

    /* renamed from: i */
    fun checkFeatureDualKeyboard(context: Context?): Boolean {
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
        return supportDualScreen()
    }

    /* renamed from: h */
    @JvmStatic
    fun checkRotation(context: Context?): Boolean {
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
        return true
    }

    /* renamed from: g */
    private val windowManagerGlobal: Class<*>?
        private get() = try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            if (cls.getMethod("getWindowManagerService", *arrayOfNulls(0))
                    .invoke(cls, *arrayOfNulls(0)) != null
            ) {
                Class.forName("android.view.IWindowManager")
            } else null
        } catch (unused: Exception) {
            Log.w("MultiDisplayUtils", "Can't get IWindowManager class")
            null
        }

    /* renamed from: a */
    fun m8807a(context: Context?) {
        m8808a(context, "com.lge.ime.TEST_ACTIVITY_END")
    }

    /* renamed from: a */
    private fun m8808a(context: Context?, str: String) {
        if (context != null && !TextUtils.isEmpty(str)) {
            val intent = Intent()
            intent.action = str
            context.sendBroadcast(intent)
        }
    }

    /* renamed from: i */
    private fun m8825i(): Boolean {
        return try {
            (windowManagerGlobal!!.getMethod("isApplicationTypeWindow", *arrayOfNulls(0)).invoke(
                windowManagerGlobalObject, *arrayOfNulls(0)) as? java.lang.Boolean)?.booleanValue()
                ?: false
        } catch (unused: Exception) {
            Log.w("MultiDisplayUtils", "Can't get window type")
            false
        }
    }

    /* renamed from: h */
    private val windowManagerGlobalObject: Any?
        private get() = try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            cls.getMethod("getWindowManagerService", *arrayOfNulls(0)).invoke(cls, *arrayOfNulls(0))
        } catch (unused: Exception) {
            Log.w("MultiDisplayUtils", "Can't get WindowManagerGlobal instance")
            null
        }

    /* access modifiers changed from: private */ /* renamed from: com.lge.ime.util.f.d$a */ /* compiled from: MultiDisplayUtils */
    enum class EnumC1176a {
        NOT_INITIALIZED, SUPPORT, NOT_SUPPORT
    }
}