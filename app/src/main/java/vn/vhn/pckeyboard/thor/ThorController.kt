package vn.vhn.pckeyboard.thor

import vn.vhn.pckeyboard.LatinIME

/**
 * In-process coordination between the Thor overlay keyboard service and the
 * LatinIME service (both run in the app's process). While the second-screen deck
 * is shown, the IME suppresses its on-screen input view so no keyboard appears on
 * the main screen -- since our app is the selected IME, no other keyboard can pop
 * up either.
 */
object ThorController {
    /** True while the second-screen keyboard deck is visible. */
    @Volatile
    var deckShown = false
        private set

    /** The running IME, if any, used to re-evaluate input-view visibility. */
    @Volatile
    var ime: LatinIME? = null

    fun setDeckShown(shown: Boolean) {
        deckShown = shown
        val i = ime ?: return
        i.mDefaultHandler.post {
            try { i.updateInputViewShown() } catch (_: Exception) {}
        }
    }
}
