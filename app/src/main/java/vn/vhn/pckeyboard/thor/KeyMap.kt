package vn.vhn.pckeyboard.thor

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent] keycodes to Linux evdev key codes (linux/input-event-codes.h)
 * for uinput injection. Covers a full PC keyboard: letters, digits, punctuation,
 * modifiers, navigation, function keys, and the numeric keypad.
 */
object KeyMap {
    // Modifiers (exposed for bracketing in InputInjector).
    const val KEY_LEFTCTRL = 29
    const val KEY_LEFTSHIFT = 42
    const val KEY_LEFTALT = 56
    const val KEY_LEFTMETA = 125

    private val map: Map<Int, Int> = HashMap<Int, Int>().apply {
        // Letters A..Z -> evdev
        put(KeyEvent.KEYCODE_A, 30); put(KeyEvent.KEYCODE_B, 48); put(KeyEvent.KEYCODE_C, 46)
        put(KeyEvent.KEYCODE_D, 32); put(KeyEvent.KEYCODE_E, 18); put(KeyEvent.KEYCODE_F, 33)
        put(KeyEvent.KEYCODE_G, 34); put(KeyEvent.KEYCODE_H, 35); put(KeyEvent.KEYCODE_I, 23)
        put(KeyEvent.KEYCODE_J, 36); put(KeyEvent.KEYCODE_K, 37); put(KeyEvent.KEYCODE_L, 38)
        put(KeyEvent.KEYCODE_M, 50); put(KeyEvent.KEYCODE_N, 49); put(KeyEvent.KEYCODE_O, 24)
        put(KeyEvent.KEYCODE_P, 25); put(KeyEvent.KEYCODE_Q, 16); put(KeyEvent.KEYCODE_R, 19)
        put(KeyEvent.KEYCODE_S, 31); put(KeyEvent.KEYCODE_T, 20); put(KeyEvent.KEYCODE_U, 22)
        put(KeyEvent.KEYCODE_V, 47); put(KeyEvent.KEYCODE_W, 17); put(KeyEvent.KEYCODE_X, 45)
        put(KeyEvent.KEYCODE_Y, 21); put(KeyEvent.KEYCODE_Z, 44)

        // Digits row 0..9
        put(KeyEvent.KEYCODE_1, 2); put(KeyEvent.KEYCODE_2, 3); put(KeyEvent.KEYCODE_3, 4)
        put(KeyEvent.KEYCODE_4, 5); put(KeyEvent.KEYCODE_5, 6); put(KeyEvent.KEYCODE_6, 7)
        put(KeyEvent.KEYCODE_7, 8); put(KeyEvent.KEYCODE_8, 9); put(KeyEvent.KEYCODE_9, 10)
        put(KeyEvent.KEYCODE_0, 11)

        // Punctuation / symbols
        put(KeyEvent.KEYCODE_MINUS, 12); put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_BACKSLASH, 43); put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40); put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_COMMA, 51); put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)

        // Whitespace / editing
        put(KeyEvent.KEYCODE_SPACE, 57); put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_TAB, 15); put(KeyEvent.KEYCODE_DEL, 14)          // Backspace
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111); put(KeyEvent.KEYCODE_ESCAPE, 1)
        put(KeyEvent.KEYCODE_MENU, 139)   // KEY_MENU

        // Modifiers
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42); put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29); put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56); put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_META_LEFT, 125); put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)

        // Navigation
        put(KeyEvent.KEYCODE_DPAD_UP, 103); put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105); put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102); put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_PAGE_UP, 104); put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)

        // Function keys F1..F12
        put(KeyEvent.KEYCODE_F1, 59); put(KeyEvent.KEYCODE_F2, 60); put(KeyEvent.KEYCODE_F3, 61)
        put(KeyEvent.KEYCODE_F4, 62); put(KeyEvent.KEYCODE_F5, 63); put(KeyEvent.KEYCODE_F6, 64)
        put(KeyEvent.KEYCODE_F7, 65); put(KeyEvent.KEYCODE_F8, 66); put(KeyEvent.KEYCODE_F9, 67)
        put(KeyEvent.KEYCODE_F10, 68); put(KeyEvent.KEYCODE_F11, 87); put(KeyEvent.KEYCODE_F12, 88)

        // Numeric keypad
        put(KeyEvent.KEYCODE_NUM_LOCK, 69)
        put(KeyEvent.KEYCODE_NUMPAD_0, 82); put(KeyEvent.KEYCODE_NUMPAD_1, 79)
        put(KeyEvent.KEYCODE_NUMPAD_2, 80); put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75); put(KeyEvent.KEYCODE_NUMPAD_5, 76)
        put(KeyEvent.KEYCODE_NUMPAD_6, 77); put(KeyEvent.KEYCODE_NUMPAD_7, 71)
        put(KeyEvent.KEYCODE_NUMPAD_8, 72); put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98); put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74); put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 83); put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 117)
    }

    /** Returns the evdev code for an Android keycode, or null if unmapped. */
    fun androidToEvdev(androidKeyCode: Int): Int? = map[androidKeyCode]

    /** evdev code + whether Shift must be held to produce a character. */
    data class CharKey(val evdev: Int, val shift: Boolean)

    // US-QWERTY printable-ASCII -> (evdev, shift). Built once.
    private val charMap: Map<Char, CharKey> = HashMap<Char, CharKey>().apply {
        // Letters
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val letterEvdev = intArrayOf(
            30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,   // a..m
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44    // n..z
        )
        for (i in letters.indices) {
            put(letters[i], CharKey(letterEvdev[i], false))            // lower
            put(letters[i].uppercaseChar(), CharKey(letterEvdev[i], true)) // upper
        }
        // Digit row: unshifted digit / shifted symbol
        val digits = "1234567890"
        val digitEvdev = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        val digitShift = "!@#$%^&*()"
        for (i in digits.indices) {
            put(digits[i], CharKey(digitEvdev[i], false))
            put(digitShift[i], CharKey(digitEvdev[i], true))
        }
        // Other keys: base char (unshifted) and shifted char share one evdev code
        fun pair(base: Char, shifted: Char, evdev: Int) {
            put(base, CharKey(evdev, false))
            put(shifted, CharKey(evdev, true))
        }
        pair('-', '_', 12); pair('=', '+', 13)
        pair('[', '{', 26); pair(']', '}', 27); pair('\\', '|', 43)
        pair(';', ':', 39); pair('\'', '"', 40); pair('`', '~', 41)
        pair(',', '<', 51); pair('.', '>', 52); pair('/', '?', 53)
        put(' ', CharKey(57, false))   // Space
    }

    /** Returns the (evdev, shift) for a printable US-layout char, or null. */
    fun charToEvdev(ch: Char): CharKey? = charMap[ch]
}
