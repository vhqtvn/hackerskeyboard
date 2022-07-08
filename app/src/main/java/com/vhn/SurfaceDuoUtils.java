package com.vhn;

import android.content.pm.PackageManager;

public class SurfaceDuoUtils {
    private static int isDeviceSurfaceDuoComputed = -1;
    static final String feature = "com.microsoft.device.display.displaymask";

    public static boolean isDeviceSurfaceDuo(PackageManager pm) {
        if (isDeviceSurfaceDuoComputed == -1)
            isDeviceSurfaceDuoComputed = pm.hasSystemFeature(feature) ? 1 : 0;
        return isDeviceSurfaceDuoComputed == 1;
    }
}
