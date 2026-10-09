package vn.vhn.pckeyboard

import android.util.Log

/**
 * Thin binding to the bundled Unikey engine (native, see cpp/ukengine + unikey_jni.cpp).
 * The engine is a global singleton driven from the UI thread; each [filter]/[backspace]
 * call updates the shared output which [output] reads. Output is UTF-8 (Vietnamese
 * precomposed, all BMP).
 *
 * Feed it a keystroke via [filter]; it returns how many characters to delete from the
 * text before appending [output] (tone/mark re-composition). See [VnComposer] for the
 * preedit/InputConnection driving that mirrors ibus-unikey.
 *
 * Unprivileged NDK code, so it ships in every product flavor.
 */
object Unikey {
    // UkInputMethod values (keycons.h).
    const val TELEX = 0
    const val VNI = 1
    const val VIQR = 2
    const val SIMPLE_TELEX = 5
    const val SIMPLE_TELEX2 = 6

    @Volatile private var loaded = false
    @Volatile private var ready = false

    /** Load + initialise the engine. Safe to call repeatedly. Returns false if unavailable. */
    @Synchronized
    fun init(): Boolean {
        if (ready) return true
        if (!loaded) {
            try {
                System.loadLibrary("unikey")
                loaded = true
            } catch (e: Throwable) {
                // Swallowing this silently once cost an afternoon: the JNI symbols are
                // named after this class's package, so moving the class renames every
                // one of them, and the only symptom is Vietnamese quietly not working.
                Log.e(TAG, "Unikey: cannot load libunikey", e)
                return false
            }
        }
        return try {
            nativeSetup()
            ready = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Unikey: native setup failed -- do the JNI symbols in " +
                    "unikey_jni.cpp still match this class's package?", e)
            false
        }
    }

    private const val TAG = "PCKeyboardIME"

    val isReady: Boolean get() = ready

    fun setInputMethod(im: Int) { if (ready) nativeSetInputMethod(im) }
    fun reset() { if (ready) nativeReset() }
    fun setCaps(shift: Boolean, caps: Boolean) { if (ready) nativeSetCaps(if (shift) 1 else 0, if (caps) 1 else 0) }
    fun filter(ch: Int): Int = if (ready) nativeFilter(ch) else 0
    fun backspace(): Int = if (ready) nativeBackspace() else 0
    fun restore(): Int = if (ready) nativeRestore() else 0
    fun putChar(ch: Int) { if (ready) nativePutChar(ch) }
    fun atWordBeginning(): Boolean = if (ready) nativeAtWordBeginning() else true
    fun output(): String = if (ready) nativeOutput() else ""

    private external fun nativeSetup()
    private external fun nativeSetInputMethod(im: Int)
    private external fun nativeReset()
    private external fun nativeSetCaps(shift: Int, caps: Int)
    private external fun nativeFilter(ch: Int): Int
    private external fun nativeBackspace(): Int
    private external fun nativeRestore(): Int
    private external fun nativePutChar(ch: Int)
    private external fun nativeAtWordBeginning(): Boolean
    private external fun nativeOutput(): String
}
