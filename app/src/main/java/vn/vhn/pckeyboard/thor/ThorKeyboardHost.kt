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
 * display context, and turns key presses into uinput injection via
 * [InputInjector] instead of the IME's InputConnection.
 *
 * Modifiers (Shift/Ctrl/Alt/Meta) are tracked here as one-shot toggles, applied
 * to the next key and then cleared -- matching the sticky-key UX. The keyboard is
 * never re-shifted (setShifted), so keys report their base codes and Shift is
 * applied purely at injection time, which mirrors a physical PC keyboard.
 */
class ThorKeyboardHost(
    private val displayContext: Context,
    private val keyboardHeightPercent: Float = 40f,
) : LatinKeyboardBaseView.OnKeyboardActionListener {

    val rootView: ViewGroup
    private val inputView: LatinKeyboardView

    /** Invoked when the keyboard requests to hide itself (Cancel key). */
    var onHideRequested: (() -> Unit)? = null

    // One-shot modifier toggles (left/right tracked to match HK indicators).
    private var shiftL = false
    private var shiftR = false
    private var ctrlL = false
    private var ctrlR = false
    private var altL = false
    private var altR = false
    private var metaL = false
    private var metaR = false
    private var caps = false   // caps lock: persistent letter-case flip

    private val shiftActive get() = shiftL || shiftR
    private val ctrlActive get() = ctrlL || ctrlR
    private val altActive get() = altL || altR
    private val metaActive get() = metaL || metaR

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(displayContext)
        val res = displayContext.resources
        // Ensure the shared keyboard settings are initialized even if the IME
        // service has never run in this process.
        LatinIME.sKeyboardSettings.initPrefs(prefs, res)
        // Fill more of the near-square second screen (both the view and the
        // Keyboard read this global height percent).
        LatinIME.sKeyboardSettings.keyboardHeightPercent = keyboardHeightPercent

        val inflater = LayoutInflater.from(displayContext)
        rootView = inflater.inflate(R.layout.input_material_dark, null) as ViewGroup
        inputView = rootView.findViewById(R.id.LatinkeyboardBaseView)

        val lang = LanguageSwitcher(displayContext)
        lang.loadLocales(prefs)

        val kb = LatinKeyboard(
            displayContext, R.xml.kbd_full, 0, keyboardHeightPercent
        )
        kb.setLanguageSwitcher(lang, false)
        inputView.setKeyboard(kb)
        inputView.setPreviewEnabled(false)
        inputView.setOnKeyboardActionListener(this)
    }

    // --- meta / modifier helpers ----------------------------------------------
    private fun buildMeta(shift: Boolean): Int {
        var meta = 0
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON
        if (ctrlActive) meta = meta or KeyEvent.META_CTRL_ON
        if (altActive) meta = meta or KeyEvent.META_ALT_ON
        if (metaActive) meta = meta or KeyEvent.META_META_ON
        return meta
    }

    private fun updateIndicators() {
        inputView.setShiftIndicator(shiftL || caps, shiftR || caps)
        inputView.setCtrlIndicator(ctrlL, ctrlR)
        inputView.setAltIndicator(altL, altR)
        inputView.setMetaIndicator(metaL, metaR)
    }

    private fun clearOneShotMods() {
        if (shiftL || shiftR || ctrlL || ctrlR || altL || altR || metaL || metaR) {
            shiftL = false; shiftR = false
            ctrlL = false; ctrlR = false
            altL = false; altR = false
            metaL = false; metaR = false
            updateIndicators()
        }
    }

    // --- OnKeyboardActionListener ----------------------------------------------
    override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) {
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE ->
                { InputInjector.injectAndroidKey(KeyEvent.KEYCODE_DEL, buildMeta(shiftActive)); clearOneShotMods() }
            LatinKeyboardView.KEYCODE_SHIFT_LEFT -> { shiftL = !shiftL; updateIndicators() }
            LatinKeyboardView.KEYCODE_SHIFT_RIGHT -> { shiftR = !shiftR; updateIndicators() }
            LatinKeyboardView.KEYCODE_CTRL_LEFT -> { ctrlL = !ctrlL; updateIndicators() }
            LatinKeyboardView.KEYCODE_CTRL_RIGHT -> { ctrlR = !ctrlR; updateIndicators() }
            LatinKeyboardView.KEYCODE_ALT_LEFT -> { altL = !altL; updateIndicators() }
            LatinKeyboardView.KEYCODE_ALT_RIGHT -> { altR = !altR; updateIndicators() }
            LatinKeyboardView.KEYCODE_META_LEFT -> { metaL = !metaL; updateIndicators() }
            LatinKeyboardView.KEYCODE_META_RIGHT -> { metaR = !metaR; updateIndicators() }
            LatinKeyboardView.KEYCODE_CAPS_LOCK -> { caps = !caps; updateIndicators() }
            Keyboard.KEYCODE_CANCEL -> onHideRequested?.invoke()
            Keyboard.KEYCODE_MODE_CHANGE, Keyboard.KEYCODE_STICK, Keyboard.KEYCODE_NOIME -> {
                // Not applicable to the overlay (full layout has all keys); ignore.
            }
            9 -> { InputInjector.injectAndroidKey(KeyEvent.KEYCODE_TAB, buildMeta(shiftActive)); clearOneShotMods() }
            else -> {
                if (primaryCode < 0) {
                    // Special key: -primaryCode is the Android keycode (see LatinIME).
                    InputInjector.injectAndroidKey(-primaryCode, buildMeta(shiftActive))
                } else {
                    val ch = primaryCode.toChar()
                    if (ch == '\n') {
                        InputInjector.injectAndroidKey(KeyEvent.KEYCODE_ENTER, buildMeta(shiftActive))
                    } else {
                        val ck = KeyMap.charToEvdev(ch)
                        if (ck != null) {
                            var shift = ck.shift || shiftActive
                            if (ch.isLetter() && caps) shift = !shift
                            InputInjector.tapWithMeta(ck.evdev, buildMeta(shift))
                        }
                        // Unmappable chars (non-ASCII) cannot be injected via uinput; skipped.
                    }
                }
                clearOneShotMods()
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

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onCancel() {}
    override fun swipeLeft(): Boolean = false
    override fun swipeRight(): Boolean = false
    override fun swipeDown(): Boolean = false
    override fun swipeUp(): Boolean = false

    /**
     * Force the keyboard to re-measure and repaint its offscreen buffer after it
     * is attached to a window. Without this the software-rendered view sometimes
     * draws before it has a valid size and stays black.
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
