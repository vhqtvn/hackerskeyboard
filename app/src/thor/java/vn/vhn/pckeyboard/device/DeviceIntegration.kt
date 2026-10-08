package vn.vhn.pckeyboard.device

import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import vn.vhn.pckeyboard.LatinIME
import vn.vhn.pckeyboard.thor.OverlayKeyboardService
import vn.vhn.pckeyboard.thor.ThorController

/**
 * AYN Thor implementation of the [DeviceIntegration] seam: the second screen runs a
 * keyboard deck, so the main screen never shows an input view while that deck's service
 * is alive. See docs/ayn-thor.md.
 */
object DeviceIntegration {
    val suppressMainScreenKeyboard: Boolean get() = ThorController.serviceRunning

    fun attachIme(ime: LatinIME) {
        ThorController.ime = ime
    }

    fun detachIme(ime: LatinIME) {
        if (ThorController.ime === ime) ThorController.ime = null
    }

    fun onEditorFocus(focused: Boolean) = ThorController.onEditorFocus(focused)

    fun onMainActivityCreated(activity: Activity, extras: ViewGroup) {
        // Launching the app (re)starts the deck -- handy if it ever stops/crashes.
        OverlayKeyboardService.start(activity)

        // The service self-configures (overlay permission + default IME) via root.
        extras.addView(Button(activity).apply {
            text = "Start Thor deck"
            setOnClickListener { OverlayKeyboardService.start(activity) }
        })
        extras.addView(Button(activity).apply {
            text = "Stop Thor deck"
            setOnClickListener { OverlayKeyboardService.stop(activity) }
        })
    }
}
