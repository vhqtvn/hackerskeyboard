package com.lge.ime.util

import android.os.Build

/* renamed from: com.lge.ime.util.aa */
object VersionUtils {
    /* renamed from: a */
    fun m8758a(): Boolean {
        return Build.VERSION.SDK_INT == 28
    }

    /* renamed from: b */
    fun m8759b(): Boolean {
        return Build.VERSION.SDK_INT == 29 || "Q" == Build.VERSION.CODENAME
    }
}