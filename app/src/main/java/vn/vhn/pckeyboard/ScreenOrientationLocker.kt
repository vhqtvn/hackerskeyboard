package vn.vhn.pckeyboard

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.util.Log
import android.view.Window
import android.view.WindowManager
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter

class ScreenOrientationLocker(private val mContext: Context) {
    private var mSavedRotation = ""
    private var mSavedOrientation = ""
    fun saveCurrentWindowManager(window: Window?, wm: WindowManager?) {}
    private fun rootRun(command: String, returnOuput: Boolean): String {
        try {
            val keyCommand = "su -c $command"
            val runtime = Runtime.getRuntime()
            val proc = runtime.exec(keyCommand)
            if (returnOuput) {
                return getStringFromInputStream(proc.inputStream).trim { it <= ' ' }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return ""
    }

    fun lock(orientation: Int): Boolean {
        if (mSavedRotation.isEmpty()) mSavedRotation =
            rootRun("settings get system accelerometer_rotation", true)
        if (mSavedOrientation.isEmpty()) mSavedOrientation =
            rootRun("settings get system user_rotation", true)
        rootRun("settings put system accelerometer_rotation 0", true)
        var targetOrientation = 0
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            val wm = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            targetOrientation = if (wm.defaultDisplay.displayId != 0) {
                1
            } else {
                3
            }
        }
        rootRun("wm set-user-rotation lock -d 0 $targetOrientation; wm set-user-rotation lock -d 1 $targetOrientation",
            true)
        return true
    }

    fun unlock() {
        if (!mSavedOrientation.isEmpty()) {
            rootRun("settings put system user_rotation $mSavedOrientation", false)
            mSavedRotation = ""
        }
        if (!mSavedRotation.isEmpty()) {
            rootRun("settings put system accelerometer_rotation $mSavedRotation", false)
            mSavedRotation = ""
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
        try {
            val keyCommand = "su -c input keyevent 108"
            val runtime = Runtime.getRuntime()
            val proc = runtime.exec(keyCommand)
        } catch (e: IOException) {
            // TODO Auto-generated catch block
            e.printStackTrace()
        }
        //        InputMethodManager inputManager = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
//        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    companion object {
        private const val TAG = "ScreenOrientationLocker"
        @Throws(IOException::class)
        private fun getStringFromInputStream(stream: InputStream): String {
            var n = 0
            val buffer = CharArray(1024 * 4)
            val reader = InputStreamReader(stream, "UTF8")
            val writer = StringWriter()
            while (-1 != reader.read(buffer).also { n = it }) writer.write(buffer, 0, n)
            return writer.toString()
        }

        private var unlockToken = 0
    }
}