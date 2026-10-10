package vn.vhn.pckeyboard.thor

import android.content.Context
import android.preference.PreferenceManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import vn.vhn.pckeyboard.Keyboard
import vn.vhn.pckeyboard.LanguageSwitcher
import vn.vhn.pckeyboard.LatinIME
import vn.vhn.pckeyboard.LatinKeyboard
import vn.vhn.pckeyboard.LatinKeyboardBaseView
import vn.vhn.pckeyboard.LatinKeyboardView
import vn.vhn.pckeyboard.R
import vn.vhn.pckeyboard.Unikey
import vn.vhn.pckeyboard.ToolPanelView
import vn.vhn.pckeyboard.ClipboardPanelView
import vn.vhn.pckeyboard.EmojiPanelView
import vn.vhn.pckeyboard.VnComposer

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
    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(displayContext)
    private val vibrator = displayContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Invoked when the keyboard requests to hide itself (Cancel key). */
    var onHideRequested: (() -> Unit)? = null

    /**
     * Asks the service to flip Vietnamese. Routed back rather than toggled here so the
     * deck's En/Vi button and the stored preference move with it -- the key and the
     * button are two ways to the same switch, not two switches.
     */
    var onToggleVnRequested: (() -> Unit)? = null

    /**
     * One modifier key's state: held (finger down), latched (sticky), used (in a
     * chord), and the exact evdev code it injects. Left and right variants are
     * tracked independently and inject their own code, so e.g. right-Alt reaches
     * apps as AltGr and left/right Ctrl/Shift are distinct.
     */
    private class Mod(val evdev: Int) {
        var held = false
        var latched = false
        var used = false
        fun active() = held || latched
    }

    private val ctrlL = Mod(KeyMap.KEY_LEFTCTRL)
    private val ctrlR = Mod(KeyMap.KEY_RIGHTCTRL)
    private val altL = Mod(KeyMap.KEY_LEFTALT)
    private val altR = Mod(KeyMap.KEY_RIGHTALT)
    private val shiftL = Mod(KeyMap.KEY_LEFTSHIFT)
    private val shiftR = Mod(KeyMap.KEY_RIGHTSHIFT)
    private val metaL = Mod(KeyMap.KEY_LEFTMETA)
    private val metaR = Mod(KeyMap.KEY_RIGHTMETA)
    private val allMods = arrayOf(ctrlL, ctrlR, altL, altR, shiftL, shiftR, metaL, metaR)
    private var caps = false   // caps lock: persistent letter-case flip

    // Fn layers: unlike Ctrl/Shift, these change which *code* the keyboard emits
    // (fn1codes/fn2codes), so we flip the keyboard's Fn state (setFn1/2Indicator)
    // whenever active. Fn1 (green) = IME/device functions (no-op on the deck);
    // Fn2 (blue) = PgUp/PgDn/Home/End (useful, injected).
    private val fn1 = Mod(0)
    private val fn2 = Mod(0)

    private fun anyShift() = shiftL.active() || shiftR.active()

    private fun modFor(code: Int): Mod? = when (code) {
        LatinKeyboardView.KEYCODE_SHIFT_LEFT -> shiftL
        LatinKeyboardView.KEYCODE_SHIFT_RIGHT -> shiftR
        LatinKeyboardView.KEYCODE_CTRL_LEFT -> ctrlL
        LatinKeyboardView.KEYCODE_CTRL_RIGHT -> ctrlR
        LatinKeyboardView.KEYCODE_ALT_LEFT -> altL
        LatinKeyboardView.KEYCODE_ALT_RIGHT -> altR
        LatinKeyboardView.KEYCODE_META_LEFT -> metaL
        LatinKeyboardView.KEYCODE_META_RIGHT -> metaR
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
        // Set before the keyboard so the first draw uses deck styling + hints.
        inputView.colorCodeKeys = true   // OLED-black keys with group-coloured borders
        inputView.setBackgroundColor(android.graphics.Color.BLACK)   // true black (power saving)

        val lang = LanguageSwitcher(displayContext)
        lang.loadLocales(prefs)

        val kb = LatinKeyboard(displayContext, R.xml.kbd_thumb, 0, keyboardHeightPercent)
        // The deck shares kbd_thumb with the cover screen, where both of these earn their
        // keycap. Here neither does: there is no input-method picker to open from a deck,
        // and hiding is what the strip's own minimise button is for.
        kb.removeFnActions(setOf(
            LatinKeyboardView.KEYCODE_IME_PICKER,
            Keyboard.KEYCODE_CANCEL))
        kb.setLanguageSwitcher(lang, false)
        inputView.setKeyboard(kb)
        ThorController.onEditorChanged = { inputView.post { updateEnterActionHint() } }
        updateEnterActionHint()   // reflect a field that's already focused
        inputView.setPreviewEnabled(false)
        inputView.setOnKeyboardActionListener(this)
    }

    /**
     * The keyboard's fixed intrinsic width in px. [LatinKeyboardBaseView.onMeasure]
     * ignores the width spec and always reports `mKeyboard.minWidth + padding` (sized
     * from the display when the keyboard was built), so this is the correct deck width
     * regardless of what (possibly wrong) width the overlay window reports. Returns 0
     * if the keyboard isn't ready.
     */
    fun keyboardWidth(): Int {
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        inputView.measure(unspec, unspec)
        return inputView.measuredWidth
    }

    // --- meta / modifier helpers ----------------------------------------------
    /**
     * Meta state from the currently active modifiers, for the nav pad: latched
     * Shift -> arrows select, latched Ctrl -> word/line jumps, etc. Read-only -- it
     * does not consume the latch, so a modifier stays on across a whole nav drag.
     */
    fun navMetaState(): Int {
        var m = 0
        if (anyShift()) m = m or KeyEvent.META_SHIFT_ON
        if (ctrlL.active() || ctrlR.active()) m = m or KeyEvent.META_CTRL_ON
        if (altL.active() || altR.active()) m = m or KeyEvent.META_ALT_ON
        if (metaL.active() || metaR.active()) m = m or KeyEvent.META_META_ON
        return m
    }

    /**
     * The exact modifier evdev codes to hold around a key press. Ctrl/Alt/Meta use
     * their pressed side (left vs right kept distinct). Shift is special: it's
     * injected only when the resulting glyph needs it ([shiftBit], which already
     * folds in caps), using the active shift key's side, or left shift when a
     * shifted glyph is needed with no shift key held (e.g. '!' or a caps letter).
     */
    private fun activeModEvdevs(shiftBit: Boolean): IntArray {
        val list = ArrayList<Int>(4)
        for (m in arrayOf(ctrlL, ctrlR, altL, altR, metaL, metaR)) if (m.active()) list.add(m.evdev)
        if (shiftBit) list.add(if (shiftR.active()) shiftR.evdev else shiftL.evdev)
        return list.toIntArray()
    }

    private fun updateIndicators() {
        // Per-side, since left/right modifiers are independent (caps lights both shifts).
        inputView.setShiftIndicator(shiftL.active() || caps, shiftR.active() || caps)
        inputView.setCtrlIndicator(ctrlL.active(), ctrlR.active())
        inputView.setAltIndicator(altL.active(), altR.active())
        inputView.setMetaIndicator(metaL.active(), metaR.active())
    }

    /** After a non-modifier key: mark held modifiers as chorded, clear latched ones. */
    private fun consumeMods() {
        for (m in allMods) {
            if (m.held) m.used = true
            if (m.latched) m.latched = false
        }
        updateIndicators()
        // Fn layers act as a *lock*: a latched Fn stays on across many keys so the
        // vim-style nav layer (Fn+hjkl, Fn+u/d) and repeated arrows work; tap Fn
        // again to release. A held (finger-down) Fn is momentary as usual.
        for (m in arrayOf(fn1, fn2)) {
            if (m.held) m.used = true
        }
        syncFn()
    }

    // --- key press/release ------------------------------------------------------
    // A pressed injectable key is held down (evdev down on finger-down, up on
    // finger-up) so the target sees a real key-hold and Android auto-repeats it
    // while held -- matching a physical keyboard, instead of a tap on release.
    private var downEvdev: Int? = null
    private var downMods: IntArray = IntArray(0)
    private var vnConsumed = false   // last press was composed by VN, no uinput up needed
    private var actionConsumed = false   // last press fired the field's IME action (no uinput up)
    private val keyReturn = 10   // @integer/key_return -- the Enter key's primaryCode

    /** Action keys perform a one-shot side effect (no evdev key to hold). */
    private fun isActionCode(c: Int): Boolean = when (c) {
        LatinKeyboardView.KEYCODE_CAPS_LOCK, Keyboard.KEYCODE_CANCEL,
        LatinKeyboardView.KEYCODE_TOGGLE_VN, LatinKeyboardView.KEYCODE_EMOJI,
        LatinKeyboardView.KEYCODE_CLIPBOARD,
        Keyboard.KEYCODE_MODE_CHANGE, Keyboard.KEYCODE_STICK, Keyboard.KEYCODE_NOIME,
        LatinKeyboardView.KEYCODE_ORIENTATION_LOCK, LatinKeyboardView.KEYCODE_OPTIONS,
        LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS, LatinKeyboardView.KEYCODE_FULLSCREEN_DUAL -> true
        else -> false
    }

    /** Resolve a key to (evdev, shiftBit) given the current modifier/caps state. */
    private fun resolveKey(primaryCode: Int): Pair<Int, Boolean>? {
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> return KeyMap.androidToEvdev(KeyEvent.KEYCODE_DEL)?.let { it to anyShift() }
            9 -> return KeyMap.androidToEvdev(KeyEvent.KEYCODE_TAB)?.let { it to anyShift() }
        }
        if (primaryCode < 0) {
            // Special key: -primaryCode is the Android keycode (see LatinIME).
            return KeyMap.androidToEvdev(-primaryCode)?.let { it to anyShift() }
        }
        val ch = primaryCode.toChar()
        if (ch == '\n') return KeyMap.androidToEvdev(KeyEvent.KEYCODE_ENTER)?.let { it to anyShift() }
        val ck = KeyMap.charToEvdev(ch) ?: return null
        var shiftBit = ck.shift || anyShift()
        if (ch.isLetter() && caps) shiftBit = !shiftBit
        return ck.evdev to shiftBit
    }

    private fun startKeyPress(primaryCode: Int) {
        if (downEvdev != null) endKeyPress()   // guard against overlapping presses
        val (evdev, shiftBit) = resolveKey(primaryCode) ?: return
        downMods = InputInjector.pressWithMods(evdev, activeModEvdevs(shiftBit))
        downEvdev = evdev
    }

    // --- Vietnamese (Unikey) ---------------------------------------------------
    // In VN mode, letters/symbols of a word are composed via the IME's
    // InputConnection (Unicode commit), which the uinput path can't do. Everything
    // else (chords, arrows, terminal focus with no InputConnection) stays on uinput.
    private val vnComposer = VnComposer()
    var vnMode = false
        private set

    fun setVnMode(on: Boolean) {
        vnMode = on
        // Light the key carrying the toggle, as on the main-screen keyboard.
        inputView.setVnIndicator(on)
        if (!on) vnComposer.reset(currentIc())
    }

    fun setVnMethod(method: Int) = vnComposer.setMethod(method)

    // --- tool dock (emoji) -------------------------------------------------------
    // Reaches the field the same way Vietnamese does: an emoji is Unicode, and the
    // uinput path can only press keys that exist on a keyboard. With no editor focused
    // -- a terminal that never opened an InputConnection -- there is nowhere to put it,
    // so the dock stays shut rather than opening onto a dead end.
    private var toolPanel: ToolPanelView? = null

    /**
     * The key for a tool opens the dock on it, switches to it when the dock is open on
     * the other, and closes the dock when it is already the one showing.
     */
    private fun toggleTool(tool: Int) {
        if (toolPanel?.visibility == View.VISIBLE) {
            if (toolPanel?.active == tool) hideTools() else toolPanel?.select(tool)
            return
        }
        if (currentIc() == null) return
        vnComposer.reset(currentIc())   // a half-composed word would swallow what follows
        val panel = toolPanel ?: ToolPanelView(rootView.context, compact = false).also {
            it.onClose = { hideTools() }
            it.onBackspace = { InputInjector.injectAndroidKey(KeyEvent.KEYCODE_DEL, 0) }
            it.setTools(listOf(
                ToolPanelView.Tool("\uD83D\uDE00") { ctx ->
                    EmojiPanelView(ctx, compact = false).also { grid ->
                        grid.onEmoji = { e -> currentIc()?.commitText(e, 1) }
                    }
                },
                ToolPanelView.Tool("\uD83D\uDCCB") { ctx ->
                    ClipboardPanelView(ctx).also { clips ->
                        clips.onPaste = { text -> currentIc()?.commitText(text, 1) }
                    }
                }
            ))
            rootView.addView(it, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, inputView.height))
            toolPanel = it
        }
        panel.layoutParams.height = inputView.height
        panel.requestLayout()
        panel.select(tool)
        panel.visibility = View.VISIBLE
        inputView.visibility = View.INVISIBLE
    }

    private companion object {
        const val TOOL_EMOJI = 0
        const val TOOL_CLIPBOARD = 1
    }

    private fun hideTools() {
        toolPanel?.visibility = View.GONE
        inputView.visibility = View.VISIBLE
    }

    private fun currentIc() =
        if (ThorController.editorFocused) ThorController.ime?.currentInputConnection else null

    /**
     * Fire the focused field's IME action (Go / Search / Send / Done / Next / ...) via the
     * InputConnection. Prefers a custom EditorInfo.actionId, else the imeOptions action.
     * Returns false when the field declares no action, so the caller can fall back to Enter.
     */
    private fun performImeAction(): Boolean {
        val ic = currentIc() ?: return false
        val ei = ThorController.ime?.currentInputEditorInfo ?: return false
        val masked = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        val action = when {
            ei.actionId != 0 -> ei.actionId
            masked != EditorInfo.IME_ACTION_NONE && masked != EditorInfo.IME_ACTION_UNSPECIFIED -> masked
            else -> return false
        }
        ic.performEditorAction(action)   // handled regardless of its return value
        return true
    }

    /** US-QWERTY shifted glyphs, so VN sees the character the user actually typed. */
    private val shiftMap = hashMapOf(
        '`' to '~', '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')', '-' to '_',
        '=' to '+', '[' to '{', ']' to '}', '\\' to '|', ';' to ':', '\'' to '"',
        ',' to '<', '.' to '>', '/' to '?')

    private fun effectiveChar(primaryCode: Int): Int {
        val ch = primaryCode.toChar()
        if (ch.isLetter()) {
            val upper = anyShift() != caps
            return (if (upper) ch.uppercaseChar() else ch.lowercaseChar()).code
        }
        return (if (anyShift()) shiftMap[ch] ?: ch else ch).code
    }

    /** Returns true if Vietnamese input consumed the key (no uinput injection needed). */
    private fun tryVn(primaryCode: Int): Boolean {
        if (!vnMode || !Unikey.isReady) return false
        val ic = currentIc() ?: return false
        // Modifier chords are shortcuts, not text: end the word, let uinput handle them.
        if (ctrlL.active() || ctrlR.active() || altL.active() || altR.active() ||
            metaL.active() || metaR.active()) { vnComposer.reset(ic); return false }
        if (primaryCode == Keyboard.KEYCODE_DELETE) return vnComposer.onBackspace(ic)
        if (primaryCode in 32..126) return vnComposer.onChar(effectiveChar(primaryCode), anyShift(), caps, ic)
        // Special keys (Tab/Enter/arrows/F-keys/Esc...) end the word and pass through.
        vnComposer.reset(ic)
        return false
    }

    private fun endKeyPress() {
        val e = downEvdev ?: return
        InputInjector.releaseWithMeta(e, downMods)
        downEvdev = null
        downMods = IntArray(0)
    }

    // Gamepad button routed as a keyboard key (raw evdev), held down/up with the
    // deck's active modifiers -- so latched/held Shift/Ctrl/... combine with it
    // (e.g. Shift + D-pad = select). Modifiers are not consumed (persist across it).
    private var gpDownEvdev: Int? = null
    private var gpDownMods: IntArray = IntArray(0)

    /**
     * Send an edit key (MOVE_HOME/MOVE_END) via uinput, following focus like every other
     * injected key. Works generically in text fields AND terminals because the Sig-Grant
     * LSPosed module hooks PhoneWindowManager.interceptKeyBeforeDispatching to neutralize the
     * AYN ROM's MOVE_HOME->device-home mapping (see ~/devices/ayn-thor/framework/README.md),
     * so MOVE_HOME/END now reach the focused app instead of launching the launcher. The deck
     * modifiers ride along (Shift -> selection).
     */
    fun sendEditKey(keyCode: Int) {
        InputInjector.injectAndroidKey(keyCode, navMetaState())
    }

    fun gamepadKey(evdev: Int, down: Boolean) {
        if (down) {
            gpDownEvdev?.let { InputInjector.releaseWithMeta(it, gpDownMods) }
            gpDownMods = InputInjector.pressWithMods(evdev, activeModEvdevs(anyShift()))
            gpDownEvdev = evdev
        } else {
            val e = gpDownEvdev ?: return
            InputInjector.releaseWithMeta(e, gpDownMods)
            gpDownEvdev = null
            gpDownMods = IntArray(0)
        }
    }

    // --- OnKeyboardActionListener ----------------------------------------------
    /**
     * Soft key-press vibration for the deck, gated by the shared "vibrate_on" pref and
     * sized by the "vibrate_len" slider (both from the existing Feedback settings). The
     * deck bypasses LatinIME.onKey, so it fires its own. Duration-based one-shot (like
     * LatinIME.vibrate) so the slider controls the strength -- drag it down for softer.
     */
    private fun hapticTap() {
        if (!sharedPrefs.getBoolean("vibrate_on", false)) return   // mirrors LatinIME.PREF_VIBRATE_ON
        // On this ROM (probed on-device): KEYBOARD_TAP -> CLICK (strong), KEYBOARD_RELEASE
        // -> TICK (light), CLOCK_TICK -> TEXTURE_TICK (softest, subtle) -- using the softest.
        // FLAG_IGNORE_GLOBAL_SETTING is REQUIRED: the device's system "vibrate on touch" is
        // off, which drops every non-bypassed vibration, so this bypass path is the only one
        // that plays (and needs no system-setting change).
        // Continuous strength via a custom one-shot: the vibrate_len slider (5..200ms)
        // drives BOTH duration and amplitude, so short=soft, long=firm -- smoothly.
        // The device's system "vibrate on touch" is off, which would drop this, EXCEPT we
        // set flag 2 (FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF): VibratorManagerService
        // keeps that flag because the app holds WRITE_SECURE_SETTINGS (see its
        // fixupVibrationAttributes), and shouldIgnoreVibration then skips the settings gate.
        val len = (sharedPrefs.getString("vibrate_len", "40") ?: "40").toFloatOrNull() ?: 40f
        val v = vibrator ?: return
        val amp = (len / 200f * 255f).toInt().coerceIn(1, 255)     // slider -> strength
        val dur = len.toLong().coerceIn(1L, 45L)                   // cap so it stays a crisp tap
        val effect = VibrationEffect.createOneShot(dur, amp)
        val attrs = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH)
            .setFlags(2, 2)   // FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF (kept via WRITE_SECURE_SETTINGS)
            .build()
        v.vibrate(effect, attrs)
    }

    override fun onPress(primaryCode: Int) {
        hapticTap()
        modFor(primaryCode)?.let { it.held = true; it.used = false; updateIndicators(); return }
        fnModFor(primaryCode)?.let { it.held = true; it.used = false; syncFn(); return }
        if (isActionCode(primaryCode)) return
        // Fn + Enter -> the focused field's IME action (Go/Search/Send/Done/Next/...)
        // instead of a newline. Falls through to a normal Enter if the field has none.
        if (primaryCode == keyReturn && (fn1.active() || fn2.active()) && performImeAction()) {
            actionConsumed = true; return
        }
        if (tryVn(primaryCode)) { vnConsumed = true; return }   // composed via InputConnection
        startKeyPress(primaryCode)   // inject key DOWN now; held until release
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
            return
        }
        if (isActionCode(primaryCode)) return
        if (actionConsumed) { actionConsumed = false; consumeMods(); return }   // IME action, no uinput up
        if (vnConsumed) { vnConsumed = false; consumeMods(); return }   // no uinput key to release
        endKeyPress()   // inject key UP
        consumeMods()
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) {
        // Modifiers / Fn / injectable keys are handled by onPress + onRelease.
        // Only one-shot action keys act here (they fire once per press).
        var acted = true
        when (primaryCode) {
            LatinKeyboardView.KEYCODE_CAPS_LOCK -> { caps = !caps; updateIndicators() }
            LatinKeyboardView.KEYCODE_TOGGLE_VN -> onToggleVnRequested?.invoke()
            LatinKeyboardView.KEYCODE_EMOJI -> toggleTool(TOOL_EMOJI)
            LatinKeyboardView.KEYCODE_CLIPBOARD -> toggleTool(TOOL_CLIPBOARD)
            Keyboard.KEYCODE_CANCEL -> onHideRequested?.invoke()
            else -> acted = false
        }
        // These are reached through Fn and do their work once. The Fn lock exists so
        // Fn+hjkl can repeat without holding the layer down; it is not meant to leave
        // you on the Fn layer after opening a panel, with the next letter you type
        // coming out as something else.
        if (acted) releaseFnLatch()
    }

    /** Drops a tapped-on Fn layer. A held (finger-down) Fn is momentary and unaffected. */
    private fun releaseFnLatch() {
        var changed = false
        for (m in arrayOf(fn1, fn2)) if (m.latched) { m.latched = false; changed = true }
        if (changed) syncFn()
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
        ThorController.onEditorChanged = null
        try { inputView.closing() } catch (_: Exception) {}
    }

    /**
     * Show the focused field's IME action (Go/Search/Send/Done/Next/...) as the Enter
     * key's Fn hint (drawn in the Fn colour, same style as PgDn/arrows) so the user can
     * see what Fn+Enter will do. Cleared when no field/action is focused.
     */
    private fun updateEnterActionHint() {
        val enter = inputView.getKeyboard()?.keys?.firstOrNull { it?.codes?.getOrNull(0) == keyReturn } ?: return
        val label = imeActionHint()
        if (enter.fn2Label == label) return
        enter.fn2Label = label
        inputView.invalidateKey(enter)
    }

    private fun imeActionHint(): CharSequence? {
        if (!ThorController.editorFocused) return null
        val ei = ThorController.ime?.currentInputEditorInfo ?: return null
        ei.actionLabel?.let { return it }   // app-defined custom action label
        return when (ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> null
        }
    }
}
