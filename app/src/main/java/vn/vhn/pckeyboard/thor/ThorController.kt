package vn.vhn.pckeyboard.thor

import vn.vhn.pckeyboard.LatinIME

/**
 * In-process coordination between the Thor overlay keyboard service and the
 * LatinIME service (both run in the app's process).
 *
 * While the Thor deck service is running, the main-screen IME view is always
 * suppressed (screen 1 never shows a keyboard) -- since our app is the selected
 * IME, no other keyboard pops up either. When a text field gains focus and the
 * deck is minimized, it auto-shows on the second screen. The deck greys out while
 * no editor is focused.
 */
object ThorController {
    /** True while the second-screen keyboard deck is visible. */
    @Volatile
    var deckShown = false
        private set

    /** True while the overlay deck service is alive (Thor mode active). */
    @Volatile
    var serviceRunning = false
        private set

    /** True while an editor on the main screen is focused. */
    @Volatile
    var editorFocused = false
        private set

    /** The running IME, if any, used to re-evaluate input-view visibility. */
    @Volatile
    var ime: LatinIME? = null

    /** Package of the currently focused editor (from EditorInfo). */
    @Volatile
    var currentEditorPackage: String? = null

    /**
     * Packages that set HIDE_NON_SYSTEM_OVERLAY_WINDOWS (anti-tapjacking), which
     * force-hides our second-screen deck even on the other display. For these we
     * fall back to a normal on-screen IME on the main screen instead of the deck.
     */
    private val overlayHidingPackages = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.odin.settings"
    )

    /** True if the focused editor's app hides overlays (so the deck can't show). */
    fun editorHidesOverlays(): Boolean = currentEditorPackage in overlayHidingPackages

    /** Set by the service: request the deck be shown (auto-show on focus). */
    @Volatile
    var expandRequest: (() -> Unit)? = null

    fun setDeckShown(shown: Boolean) {
        deckShown = shown
        reevalIme()
    }

    fun setServiceRunning(running: Boolean) {
        serviceRunning = running
        if (!running) {
            deckShown = false
            editorFocused = false
        }
        reevalIme()
    }

    /** Called by LatinIME when an editor gains/loses focus. */
    fun onEditorFocus(focused: Boolean) {
        editorFocused = focused
        // Auto-show the deck on the second screen when a field is focused and the
        // deck is currently minimized.
        if (focused && serviceRunning && !deckShown) expandRequest?.invoke()
    }

    private fun reevalIme() {
        val i = ime ?: return
        i.mDefaultHandler.post {
            try { i.updateInputViewShown() } catch (_: Exception) {}
        }
    }
}
