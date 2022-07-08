package org.pocketworkstation.pckeyboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.reflect.Method;

import android.provider.Settings;

public class ScreenOrientationLocker {
    private static final String TAG = "ScreenOrientationLocker";

    private String mSavedRotation = "";
    private String mSavedOrientation = "";

    private Context mContext;

    public ScreenOrientationLocker(Context context) {
        mContext = context;
    }

    public void saveCurrentWindowManager(Window window, WindowManager wm) {
    }

    private static String getStringFromInputStream(InputStream stream) throws IOException {
        int n = 0;
        char[] buffer = new char[1024 * 4];
        InputStreamReader reader = new InputStreamReader(stream, "UTF8");
        StringWriter writer = new StringWriter();
        while (-1 != (n = reader.read(buffer))) writer.write(buffer, 0, n);
        return writer.toString();
    }

    private String rootRun(String command, boolean returnOuput) {
        try {
            String keyCommand = "su -c " + command;
            Runtime runtime = Runtime.getRuntime();
            Process proc = runtime.exec(keyCommand);
            if (returnOuput) {
                return getStringFromInputStream(proc.getInputStream()).trim();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    public boolean lock(int orientation) {
        if (mSavedRotation.isEmpty())
            mSavedRotation = rootRun("settings get system accelerometer_rotation", true);
        if (mSavedOrientation.isEmpty())
            mSavedOrientation = rootRun("settings get system user_rotation", true);
        rootRun("settings put system accelerometer_rotation 0", true);
        int targetOrientation = 0;
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            final WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm.getDefaultDisplay().getDisplayId() != 0) {
                targetOrientation = 1;
            } else {
                targetOrientation = 3;
            }
        }
        rootRun("wm set-user-rotation lock -d 0 " + targetOrientation + "; wm set-user-rotation lock -d 1 " + targetOrientation + "", true);
        return true;
    }

    public void unlock() {
        if (!mSavedOrientation.isEmpty()) {
            rootRun("settings put system user_rotation " + mSavedOrientation, false);
            mSavedRotation = "";
        }
        if (!mSavedRotation.isEmpty()) {
            rootRun("settings put system accelerometer_rotation " + mSavedRotation, false);
            mSavedRotation = "";
        }
    }

    private static int unlockToken = 0;

    public void cancelUnlock() {
        ++unlockToken;
        Log.d(TAG, "Cancel unlock -> " + unlockToken);
    }

    public void postUnlock(int delay) {
        final int currToken = ++unlockToken;
        Log.d(TAG, "Post unlock " + currToken);
        (new Handler()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currToken == ScreenOrientationLocker.this.unlockToken) {
                    Log.d(TAG, "unlock call " + currToken + " vs " + ScreenOrientationLocker.this.unlockToken);
                    unlock();
                }
            }
        }, delay);
    }

    public void showKeyboard() {
        try {
            String keyCommand = "su -c input keyevent 108";
            Runtime runtime = Runtime.getRuntime();
            Process proc = runtime.exec(keyCommand);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
//        InputMethodManager inputManager = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
//        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }
}
