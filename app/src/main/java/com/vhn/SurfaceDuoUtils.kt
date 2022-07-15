package com.vhn

import android.content.pm.PackageManager

object SurfaceDuoUtils {
    private var isDeviceSurfaceDuoComputed = -1
    const val feature = "com.microsoft.device.display.displaymask"
    fun isDeviceSurfaceDuo(pm: PackageManager): Boolean {
        if (isDeviceSurfaceDuoComputed == -1) isDeviceSurfaceDuoComputed = if (pm.hasSystemFeature(
                feature)
        ) 1 else 0
        return isDeviceSurfaceDuoComputed == 1
    }
}