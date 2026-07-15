package vn.vhn.pckeyboard.thor

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Bridges the app to the bundled `kbdinject` root daemon (shipped as
 * libkbdinject.so, see cpp/kbdinject.c). The daemon owns a uinput virtual
 * keyboard whose events Android routes to the focused window on the top-focused
 * display -- so a non-focusable keyboard overlay on the second screen types into
 * whatever is focused on the main screen.
 *
 * The daemon is started via `su` and fed a line protocol on its stdin:
 *   D <evdev>  down   U <evdev>  up   T <evdev>  tap   S sync   P ping   Q quit
 */
object InputInjector {
    private const val TAG = "ThorInject"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val lock = Any()

    val isRunning: Boolean
        get() = synchronized(lock) { process?.isAlive == true }

    /** Path to the extracted native binary (libkbdinject.so in nativeLibraryDir). */
    private fun binaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libkbdinject.so").absolutePath

    /**
     * Start (or restart) the root daemon. Returns true if the process is alive
     * and answered the readiness ping.
     */
    fun start(context: Context): Boolean = synchronized(lock) {
        if (process?.isAlive == true) return true
        stopLocked()
        val bin = binaryPath(context)
        return try {
            // Interactive su so we keep the daemon's stdin open for the session.
            val p = ProcessBuilder("su", "-c", bin)
                .redirectErrorStream(false)
                .start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            process = p
            writer = w
            // Handshake: the daemon replies "OK" to a ping once uinput is up.
            w.write("P\n"); w.flush()
            val reply = r.readLine()
            val ok = p.isAlive && reply != null && reply.contains("OK")
            Log.d(TAG, "daemon start ok=$ok reply=$reply bin=$bin")
            if (!ok) stopLocked()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "failed to start daemon", e)
            stopLocked()
            false
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        try { writer?.apply { write("Q\n"); flush() } } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        writer = null
        process = null
    }

    private fun send(line: String): Boolean = synchronized(lock) {
        val w = writer ?: return false
        if (process?.isAlive != true) return false
        return try {
            w.write(line); w.write("\n"); w.flush(); true
        } catch (e: Exception) {
            Log.e(TAG, "send failed: $line", e)
            stopLocked()
            false
        }
    }

    // --- Raw evdev-level API ---------------------------------------------------
    fun down(evdev: Int) = send("D$evdev")
    fun up(evdev: Int) = send("U$evdev")
    fun tap(evdev: Int) = send("T$evdev")
    fun sync() = send("S")

    // --- Mouse (trackpad) ------------------------------------------------------
    fun moveMouse(dx: Int, dy: Int) = send("M $dx $dy")
    fun click(button: Int = 0) = send("C$button")
    fun scroll(dy: Int) = send("W$dy")
    /** High-resolution scroll (1/120 notch units); Android 13 ignores it, kept as a note. */
    fun scrollHiRes(dy: Int) = send("H$dy")

    // --- Touch (smooth scroll via injected finger drag on the main display) -----
    // Coordinates are normalised 0..32767 (Android maps to the display's pixels).
    fun touchDown(x: Int, y: Int) = send("GD $x $y")
    fun touchMove(x: Int, y: Int) = send("GM $x $y")
    fun touchUp() = send("GU")

    // --- Gamepad -> mouse/scroll (folded joyscroll) ----------------------------
    fun setGamepad(enabled: Boolean) = send(if (enabled) "J1" else "J0")

    // --- Android-keycode-level API --------------------------------------------
    /**
     * Tap an evdev key, bracketing it with modifier key down/up derived from an
     * Android meta state (Shift/Ctrl/Alt/Meta).
     */
    fun tapWithMeta(evdev: Int, metaState: Int) {
        val mods = pressWithMeta(evdev, metaState)
        releaseWithMeta(evdev, mods)
    }

    private fun modKeysFor(metaState: Int): IntArray {
        val mods = ArrayList<Int>(4)
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods.add(KeyMap.KEY_LEFTSHIFT)
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods.add(KeyMap.KEY_LEFTCTRL)
        if (metaState and KeyEvent.META_ALT_ON != 0) mods.add(KeyMap.KEY_LEFTALT)
        if (metaState and KeyEvent.META_META_ON != 0) mods.add(KeyMap.KEY_LEFTMETA)
        return mods.toIntArray()
    }

    /**
     * Press the modifiers, then the key, and leave them all held. The key stays
     * down (so Android auto-repeats it while a finger holds it) until the matching
     * [releaseWithMeta]. Returns the modifier evdev codes that were pressed.
     */
    fun pressWithMeta(evdev: Int, metaState: Int): IntArray =
        pressWithMods(evdev, modKeysFor(metaState))

    /** As [pressWithMeta] but with explicit modifier evdev codes (left/right exact). */
    fun pressWithMods(evdev: Int, mods: IntArray): IntArray {
        for (m in mods) down(m)
        down(evdev)
        return mods
    }

    /** Release the key, then its modifiers (reverse order). */
    fun releaseWithMeta(evdev: Int, mods: IntArray) {
        up(evdev)
        for (i in mods.indices.reversed()) up(mods[i])
    }

    /**
     * Inject an Android [KeyEvent] keyCode with the given meta state. Returns
     * false if the keycode has no evdev mapping.
     */
    fun injectAndroidKey(androidKeyCode: Int, metaState: Int = 0): Boolean {
        val evdev = KeyMap.androidToEvdev(androidKeyCode) ?: return false
        tapWithMeta(evdev, metaState)
        return true
    }
}
