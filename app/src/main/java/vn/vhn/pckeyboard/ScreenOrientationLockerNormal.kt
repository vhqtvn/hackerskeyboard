package vn.vhn.pckeyboard

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.io.IOException

class ScreenOrientationLockerNormal     //        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    (private val mContext: Context) {
    fun saveCurrentWindowManager(window: Window?, wm: WindowManager) {
        mSavedWindowManager = wm
        mSavedWindow = window
        mSavedFocus = mSavedWindow!!.currentFocus
        Log.i(TAG,
            "Saving Current window: " + wm.defaultDisplay.name + ", current focus: " + mSavedFocus)
    }

    fun lock(orientation: Int): Boolean {
        var orientation = orientation
        Log.d(TAG, "orientation lock: $orientation")
        if (mOverlayViewForOrientationLock == null) mOverlayViewForOrientationLock = View(mContext)
        if (!Settings.canDrawOverlays(mContext)) {
            Toast.makeText(mContext, "Need overlay perm to lock orientation", Toast.LENGTH_LONG)
                .show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + mContext.packageName))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mContext.startActivity(intent)
            return false
        }
        val params = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        val wm =
            if (mSavedWindowManager != null) mSavedWindowManager else mContext.getSystemService(
                Context.WINDOW_SERVICE) as WindowManager
        Log.i(TAG,
            "Current window: " + wm!!.defaultDisplay.name + " (" + wm.defaultDisplay.displayId + ")")
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            if (wm.defaultDisplay.displayId != 0) {
                orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
//                ((Application)mContext.getApplicationContext()).
            }
        }
        params.screenOrientation = orientation
        if (wm.defaultDisplay != mOverlayViewForOrientationLock!!.display && mLastWindowManager != null) {
            try {
                mLastWindowManager!!.removeView(mOverlayViewForOrientationLock)
            } catch (ex: Exception) {
            }
        }
        try {
            wm.addView(mOverlayViewForOrientationLock, params)
            mLastWindowManager = wm
        } catch (ex: Exception) {
            wm.updateViewLayout(mOverlayViewForOrientationLock, params)
            mLastWindowManager = wm
        }
        return true
    }

    fun unlock() {
        Log.d(TAG, "orientation unlock")
        if (mLastWindowManager != null) {
            mLastWindowManager!!.removeView(mOverlayViewForOrientationLock)
            mLastWindowManager = null
        }
    }

    fun cancelUnlock() {
        ++unlockToken
        Log.d(TAG, "Cancel unlock -> " + unlockToken)
    }

    fun postUnlock(delay: Int) {
        val currToken = ++unlockToken
        Log.d(TAG, "Post unlock $currToken")
        Handler().postDelayed({
            if (currToken == unlockToken) {
                Log.d(TAG, "unlock call " + currToken + " vs " + unlockToken)
                unlock()
            }
        }, delay.toLong())
    }

    fun showKeyboard() {
        Log.d(TAG, "show keyboard " + mSavedWindowManager)
        try {
            val keyCommand = "su -c input keyevent 108"
            val runtime = Runtime.getRuntime()
            val proc = runtime.exec(keyCommand)
        } catch (e: IOException) {
            // TODO Auto-generated catch block
            e.printStackTrace()
        }
        val inputManager =
            mContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED,
            InputMethodManager.HIDE_IMPLICIT_ONLY)
    }

    companion object {
        private const val TAG = "ScreenOrientationLocker"
        var mLastWindowManager: WindowManager? = null
        var mSavedWindowManager: WindowManager? = null
        private var mOverlayViewForOrientationLock: View? = null
        private var mSavedFocus: View? = null

        //    private DisplayManager mDisplayManager;
        private var mSavedWindow: Window? = null
        private var unlockToken = 0
    }
}