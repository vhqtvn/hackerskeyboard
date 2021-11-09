package com.lge.ime.util;

import android.os.Build;

/* renamed from: com.lge.ime.util.aa */
public class VersionUtils {
    /* renamed from: a */
    public static boolean m8758a() {
        return Build.VERSION.SDK_INT == 28;
    }

    /* renamed from: b */
    public static boolean m8759b() {
        return Build.VERSION.SDK_INT == 29 || "Q".equals(Build.VERSION.CODENAME);
    }
}