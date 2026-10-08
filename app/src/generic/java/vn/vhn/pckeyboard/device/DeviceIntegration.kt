package vn.vhn.pckeyboard.device

import android.app.Activity
import android.view.ViewGroup
import vn.vhn.pckeyboard.LatinIME

/**
 * Seam between the shared keyboard code and whatever device-specific integration the
 * current product flavor ships. Each flavor provides its own object with this exact
 * fully-qualified name; shared code only ever sees this API, so nothing device-specific
 * (root, uinput injection, overlay windows, task listing) is compiled into other flavors.
 *
 * This is the `generic` implementation -- phones, foldables, tablets. No special
 * privileges, no second screen: every hook is inert.
 */
object DeviceIntegration {
    /** True when a device-specific surface owns input and the main screen must stay bare. */
    val suppressMainScreenKeyboard: Boolean get() = false

    fun attachIme(ime: LatinIME) {}
    fun detachIme(ime: LatinIME) {}
    fun onEditorFocus(focused: Boolean) {}

    /** Lets a flavor add its own controls to the Main activity's extras container. */
    fun onMainActivityCreated(activity: Activity, extras: ViewGroup) {}
}
