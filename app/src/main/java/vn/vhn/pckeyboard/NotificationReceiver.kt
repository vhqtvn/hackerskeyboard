package vn.vhn.pckeyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.InputMethodManager
import vn.vhn.pckeyboard.LatinIMESettings

class NotificationReceiver internal constructor(private val mIME: LatinIME) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "NotificationReceiver.onReceive called, action=$action")
        if (action == ACTION_SHOW) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm?.showSoftInputFromInputMethod(mIME.mToken, InputMethodManager.SHOW_FORCED)
        } else if (action == ACTION_SETTINGS) {
            context.startActivity(Intent(mIME, LatinIMESettings::class.java))
        }
    }

    companion object {
        const val TAG = "PCKeyboard/Notification"
        const val ACTION_SHOW = "vn.vhn.pckeyboard.SHOW"
        const val ACTION_SETTINGS = "vn.vhn.pckeyboard.SETTINGS"
    }

    init {
        Log.i(TAG, "NotificationReceiver created, ime=$mIME")
    }
}