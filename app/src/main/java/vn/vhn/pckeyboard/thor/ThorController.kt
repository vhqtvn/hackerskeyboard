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

    /** Set by the service: request the deck be shown (auto-show on focus). */
    @Volatile
    var expandRequest: (() -> Unit)? = null

    /** Set by the deck: notified when the focused editor (and its IME action) changes. */
    @Volatile
    var onEditorChanged: (() -> Unit)? = null

    /**
     * True after the user explicitly minimizes the deck (▾) -- suppresses auto-show
     * on focus so switching activities won't pop the keyboard back up. Cleared when
     * the deck is manually reopened (floating button). Lets the second screen be
     * used freely while minimized.
     */
    @Volatile
    var suppressAutoShow = false

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
        onEditorChanged?.invoke()   // let the deck refresh the Enter key's IME-action hint
        // Auto-show the deck on the second screen when a field is focused and the
        // deck is currently minimized -- unless the user chose to keep it minimized.
        if (focused && serviceRunning && !deckShown && !suppressAutoShow) expandRequest?.invoke()
    }

    private fun reevalIme() {
        val i = ime ?: return
        i.mDefaultHandler.post {
            try { i.updateInputViewShown() } catch (_: Exception) {}
        }
    }
}
