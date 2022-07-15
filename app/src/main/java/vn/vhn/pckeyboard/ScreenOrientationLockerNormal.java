package vn.vhn.pckeyboard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.io.IOException;

public class ScreenOrientationLockerNormal {
    private static final String TAG = "ScreenOrientationLocker";
    private final Context mContext;
    static WindowManager mLastWindowManager = null;
    static WindowManager mSavedWindowManager = null;
    private static View mOverlayViewForOrientationLock = null;
    private static View mSavedFocus = null;

    //    private DisplayManager mDisplayManager;
    static private Window mSavedWindow = null;

    public ScreenOrientationLockerNormal(Context context) {
        this.mContext = context;
//        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    public void saveCurrentWindowManager(Window window, WindowManager wm) {
        mSavedWindowManager = wm;
        mSavedWindow = window;
        mSavedFocus = mSavedWindow.getCurrentFocus();
        Log.i(TAG, "Saving Current window: " + wm.getDefaultDisplay().getName() + ", current focus: " + mSavedFocus);
    }

    public boolean lock(int orientation) {
        Log.d(TAG, "orientation lock: " + orientation);
        if (mOverlayViewForOrientationLock == null)
            mOverlayViewForOrientationLock = new View(mContext);
        if (!Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, "Need overlay perm to lock orientation", Toast.LENGTH_LONG).show();
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
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
        Log.d(TAG, "orientation unlock");
        if (mLastWindowManager != null) {
            mLastWindowManager.removeView(mOverlayViewForOrientationLock);
            mLastWindowManager = null;
        }
    }

    private static int unlockToken = 0;

    public void cancelUnlock() {
        ++unlockToken;
        Log.d(TAG, "Cancel unlock -> "+unlockToken);
    }

    public void postUnlock(int delay) {
        final int currToken = ++unlockToken;
        Log.d(TAG, "Post unlock "+currToken);
        (new Handler()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currToken == ScreenOrientationLockerNormal.this.unlockToken) {
                    Log.d(TAG, "unlock call " + currToken + " vs " + ScreenOrientationLockerNormal.this.unlockToken);
                    unlock();
                }
            }
        }, delay);
    }

    public void showKeyboard() {
        Log.d(TAG, "show keyboard " + mSavedWindowManager);
        try {
            String keyCommand = "su -c input keyevent 108";
            Runtime runtime = Runtime.getRuntime();
            Process proc = runtime.exec(keyCommand);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        InputMethodManager inputManager = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput (InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }
}
