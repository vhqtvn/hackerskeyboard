package vn.vhn.pckeyboard.thor

import android.content.Context
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.ViewGroup
import vn.vhn.pckeyboard.Keyboard
import vn.vhn.pckeyboard.LanguageSwitcher
import vn.vhn.pckeyboard.LatinIME
import vn.vhn.pckeyboard.LatinKeyboard
import vn.vhn.pckeyboard.LatinKeyboardBaseView
import vn.vhn.pckeyboard.LatinKeyboardView
import vn.vhn.pckeyboard.R

/**
 * Hosts Hacker's Keyboard's own [LatinKeyboardView] (full PC layout) for a given
 * display context, and turns key presses into uinput injection via [InputInjector]
 * instead of the IME's InputConnection.
 *
 * Modifiers (Shift/Ctrl/Alt/Meta) support both usage styles:
 *  - **Chording**: hold the modifier (finger down) and tap another key -> applied.
 *  - **Sticky one-shot**: tap-and-release the modifier, then tap a key -> applied
 *    once (tap again to un-stick). Driven by onPress/onRelease so the held state is
 *    known while the other key fires (onKey of the modifier hasn't run yet).
 * The keyboard is never re-shifted; keys report base codes and Shift is applied at
 * injection, mirroring a physical PC keyboard.
 */
class ThorKeyboardHost(
    private val displayContext: Context,
    private val keyboardHeightPercent: Float = 40f,
) : LatinKeyboardBaseView.OnKeyboardActionListener {

    val rootView: ViewGroup
    private val inputView: LatinKeyboardView

    /** Invoked when the keyboard requests to hide itself (Cancel key). */
    var onHideRequested: (() -> Unit)? = null

    /** One modifier's state: held (finger down), latched (sticky), used (in a chord). */
    private class Mod {
        var held = false
        var latched = false
        var used = false
        fun active() = held || latched
    }

    private val shift = Mod()
    private val ctrl = Mod()
    private val alt = Mod()
    private val meta = Mod()
    private var caps = false   // caps lock: persistent letter-case flip

    // Fn layers: unlike Ctrl/Shift, these change which *code* the keyboard emits
    // (fn1codes/fn2codes), so we flip the keyboard's Fn state (setFn1/2Indicator)
    // whenever active. Fn1 (green) = IME/device functions (no-op on the deck);
    // Fn2 (blue) = PgUp/PgDn/Home/End (useful, injected).
    private val fn1 = Mod()
    private val fn2 = Mod()

    private fun modFor(code: Int): Mod? = when (code) {
        LatinKeyboardView.KEYCODE_SHIFT_LEFT, LatinKeyboardView.KEYCODE_SHIFT_RIGHT -> shift
        LatinKeyboardView.KEYCODE_CTRL_LEFT, LatinKeyboardView.KEYCODE_CTRL_RIGHT -> ctrl
        LatinKeyboardView.KEYCODE_ALT_LEFT, LatinKeyboardView.KEYCODE_ALT_RIGHT -> alt
        LatinKeyboardView.KEYCODE_META_LEFT, LatinKeyboardView.KEYCODE_META_RIGHT -> meta
        else -> null
    }

    private fun fnModFor(code: Int): Mod? = when (code) {
        LatinKeyboardView.KEYCODE_FN_1 -> fn1
        LatinKeyboardView.KEYCODE_FN_2 -> fn2
        else -> null
    }

    private fun syncFn() {
        inputView.setFn1Indicator(fn1.active())
        inputView.setFn2Indicator(fn2.active())
    }

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(displayContext)
        val res = displayContext.resources
        LatinIME.sKeyboardSettings.initPrefs(prefs, res)
        LatinIME.sKeyboardSettings.keyboardHeightPercent = keyboardHeightPercent

        val inflater = LayoutInflater.from(displayContext)
        rootView = inflater.inflate(R.layout.input_material_dark, null) as ViewGroup
        inputView = rootView.findViewById(R.id.LatinkeyboardBaseView)

        val lang = LanguageSwitcher(displayContext)
        lang.loadLocales(prefs)

        val kb = LatinKeyboard(displayContext, R.xml.kbd_thor, 0, keyboardHeightPercent)
        kb.setLanguageSwitcher(lang, false)
        inputView.setKeyboard(kb)
        inputView.setPreviewEnabled(false)
        inputView.setOnKeyboardActionListener(this)
    }

    // --- meta / modifier helpers ----------------------------------------------
    private fun metaWith(shiftBit: Boolean): Int {
        var m = 0
        if (shiftBit) m = m or KeyEvent.META_SHIFT_ON
        if (ctrl.active()) m = m or KeyEvent.META_CTRL_ON
        if (alt.active()) m = m or KeyEvent.META_ALT_ON
        if (meta.active()) m = m or KeyEvent.META_META_ON
        return m
    }

    /**
     * Meta state from the currently active (held or latched) modifiers, for the
     * nav pad: latched Shift -> arrows select, latched Ctrl -> word/line jumps, etc.
     * Read-only -- it does not consume the latch, so a modifier stays on across a
     * whole nav drag (e.g. extend a selection over many arrows).
     */
    fun navMetaState(): Int = metaWith(shift.active())

    private fun updateIndicators() {
        val s = shift.active() || caps
        inputView.setShiftIndicator(s, s)
        inputView.setCtrlIndicator(ctrl.active(), ctrl.active())
        inputView.setAltIndicator(alt.active(), alt.active())
        inputView.setMetaIndicator(meta.active(), meta.active())
    }

    /** After a non-modifier key: mark held modifiers as chorded, clear latched ones. */
    private fun consumeMods() {
        for (m in arrayOf(shift, ctrl, alt, meta)) {
            if (m.held) m.used = true
            if (m.latched) m.latched = false
        }
        updateIndicators()
        for (m in arrayOf(fn1, fn2)) {
            if (m.held) m.used = true
            if (m.latched) m.latched = false
        }
        syncFn()
    }

    // --- OnKeyboardActionListener ----------------------------------------------
    override fun onPress(primaryCode: Int) {
        modFor(primaryCode)?.let { it.held = true; it.used = false; updateIndicators(); return }
        fnModFor(primaryCode)?.let { it.held = true; it.used = false; syncFn(); return }
    }

    override fun onRelease(primaryCode: Int) {
        modFor(primaryCode)?.let { m ->
            if (m.held) {
                if (!m.used) m.latched = !m.latched   // pure tap toggles the sticky latch
                m.held = false; m.used = false; updateIndicators()
            }
            return
        }
        fnModFor(primaryCode)?.let { m ->
            if (m.held) {
                if (!m.used) m.latched = !m.latched
                m.held = false; m.used = false; syncFn()
            }
        }
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) {
        // Modifier / Fn keys are handled entirely by onPress/onRelease.
        if (modFor(primaryCode) != null || fnModFor(primaryCode) != null) return

        when (primaryCode) {
            LatinKeyboardView.KEYCODE_CAPS_LOCK -> { caps = !caps; updateIndicators() }
            Keyboard.KEYCODE_CANCEL -> onHideRequested?.invoke()
            // IME/device-only functions (mostly the green Fn1 layer) -- no-op on the deck.
            Keyboard.KEYCODE_MODE_CHANGE, Keyboard.KEYCODE_STICK, Keyboard.KEYCODE_NOIME,
            LatinKeyboardView.KEYCODE_ORIENTATION_LOCK, LatinKeyboardView.KEYCODE_OPTIONS,
            LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS, LatinKeyboardView.KEYCODE_FULLSCREEN_DUAL -> {
            }
            Keyboard.KEYCODE_DELETE -> {
                InputInjector.injectAndroidKey(KeyEvent.KEYCODE_DEL, metaWith(shift.active())); consumeMods()
            }
            9 -> {
                InputInjector.injectAndroidKey(KeyEvent.KEYCODE_TAB, metaWith(shift.active())); consumeMods()
            }
            else -> {
                if (primaryCode < 0) {
                    // Special key: -primaryCode is the Android keycode (see LatinIME).
                    InputInjector.injectAndroidKey(-primaryCode, metaWith(shift.active()))
                } else {
                    val ch = primaryCode.toChar()
                    if (ch == '\n') {
                        InputInjector.injectAndroidKey(KeyEvent.KEYCODE_ENTER, metaWith(shift.active()))
                    } else {
                        val ck = KeyMap.charToEvdev(ch)
                        if (ck != null) {
                            var shiftBit = ck.shift || shift.active()
                            if (ch.isLetter() && caps) shiftBit = !shiftBit
                            InputInjector.tapWithMeta(ck.evdev, metaWith(shiftBit))
                        }
                        // Unmappable (non-ASCII) chars can't be injected via uinput; skipped.
                    }
                }
                consumeMods()
            }
        }
    }

    override fun onText(text: CharSequence?) {
        if (text == null) return
        for (ch in text) {
            if (ch == '\n') { InputInjector.injectAndroidKey(KeyEvent.KEYCODE_ENTER, 0); continue }
            val ck = KeyMap.charToEvdev(ch) ?: continue
            InputInjector.tapWithMeta(ck.evdev, if (ck.shift) KeyEvent.META_SHIFT_ON else 0)
        }
    }

    override fun onCancel() {}
    override fun swipeLeft(): Boolean = false
    override fun swipeRight(): Boolean = false
    override fun swipeDown(): Boolean = false
    override fun swipeUp(): Boolean = false

    /**
     * Force the keyboard to re-measure and repaint its offscreen buffer after it is
     * attached to a window (software render can otherwise draw before it has a size).
     */
    fun forceRedraw() {
        val redraw = Runnable {
            try {
                inputView.requestLayout()
                inputView.invalidateAllKeys()
            } catch (_: Exception) {}
        }
        inputView.post(redraw)
        inputView.postDelayed(redraw, 300)
    }

    fun destroy() {
        try { inputView.closing() } catch (_: Exception) {}
    }
}
