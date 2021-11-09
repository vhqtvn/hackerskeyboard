package org.pocketworkstation.pckeyboard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

public class ScreenOrientationLocker {
    private static final String TAG = "ScreenOrientationLocker";
    private final Context mContext;
    static WindowManager mLastWindowManager = null;
    static WindowManager mSavedWindowManager = null;
    private static View mOverlayViewForOrientationLock = null;

    public ScreenOrientationLocker(Context context) {
        this.mContext = context;
    }

    public void saveCurrentWindowManager(WindowManager wm) {
        Log.i(TAG, "Saving Current window: " + wm.getDefaultDisplay().getName());
        mSavedWindowManager = wm;
    }

    public boolean lock(int orientation) {
        if (mOverlayViewForOrientationLock == null)
            mOverlayViewForOrientationLock = new View(mContext);
        if (!Settings.canDrawOverlays(mContext)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + mContext.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            return false;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        final WindowManager wm = mSavedWindowManager != null ? mSavedWindowManager : (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        Log.i(TAG, "Current window: " + wm.getDefaultDisplay().getName() + " (" + wm.getDefaultDisplay().getDisplayId() + ")");
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            if (wm.getDefaultDisplay().getDisplayId() != 0) {
                orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            } else {
//                ((Application)mContext.getApplicationContext()).
            }
        }
        params.screenOrientation = orientation;
        if (wm.getDefaultDisplay() != mOverlayViewForOrientationLock.getDisplay() && mLastWindowManager != null) {
            try {
                mLastWindowManager.removeView(mOverlayViewForOrientationLock);
            } catch (Exception ex) {
            }
        }
        try {
            wm.addView(mOverlayViewForOrientationLock, params);
            mLastWindowManager = wm;
        } catch (Exception ex) {
            wm.updateViewLayout(mOverlayViewForOrientationLock, params);
            mLastWindowManager = wm;
        }

        return true;
    }

    public void unlock() {
        if (mLastWindowManager != null) {
            mLastWindowManager.removeView(mOverlayViewForOrientationLock);
            mLastWindowManager = null;
        }
    }
}
