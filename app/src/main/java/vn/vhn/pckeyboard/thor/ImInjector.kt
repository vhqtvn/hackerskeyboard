package vn.vhn.pckeyboard.thor

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * In-process event injection via the hidden InputManager.injectInputEvent API.
 * Requires INJECT_EVENTS (granted on the Thor by the LSPosed sig-grant module) and
 * hidden-API access (unsealed via FreeReflection).
 *
 * Experiment: inject generic ACTION_SCROLL (mouse-wheel) motion events directly at a
 * chosen position/display with fine fractional AXIS_VSCROLL deltas -- bypassing the
 * framework's coarse wheel quantization. Smoothness is then up to the target app.
 */
object ImInjector {
    private const val TAG = "ThorIm"

    /**
     * Private edgeFlags marker we stamp on injected ACTION_SCROLL events. The Thor sig-grant
     * module hooks InputManagerService.injectInputEvent in system_server and, for events
     * carrying this marker, rewrites their (x,y) to the live mouse-cursor position from
     * WindowManagerService.getLatestMousePosition() before dispatch -- so a scroll always
     * lands on whatever the cursor hovers, without the app ever needing the coordinate.
     * The (x,y) we pass below are only a fallback for when the module isn't present.
     * Must match SigGrantModule.SCROLL_MARKER.
     */
    const val SCROLL_MARKER = 0x7ABE0001

    @Volatile private var im: InputManager? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    fun init(ctx: Context): Boolean {
        return try {
            try { me.weishu.reflection.Reflection.unseal(ctx.applicationContext) } catch (_: Throwable) {}
            im = ctx.getSystemService(Context.INPUT_SERVICE) as InputManager
            injectMethod = InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            setDisplayIdMethod = try {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (_: Throwable) { null }
            Log.d(TAG, "init ok, inject=${injectMethod != null} setDisplayId=${setDisplayIdMethod != null}")
            injectMethod != null
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            false
        }
    }

    /**
     * Inject a KeyEvent by *keycode* (down then up) to the focused window. This goes
     * in as a keycode -- not a scancode -- so it bypasses the device keylayout (e.g.
     * KEYCODE_MOVE_HOME stays MOVE_HOME instead of being remapped to device HOME).
     * metaState carries modifiers (META_SHIFT_ON -> selection).
     */
    fun injectKey(keyCode: Int, metaState: Int) {
        val imgr = im ?: return
        val inj = injectMethod ?: return
        val now = SystemClock.uptimeMillis()
        fun ev(action: Int) = android.view.KeyEvent(
            now, now, action, keyCode, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        try {
            inj.invoke(imgr, ev(android.view.KeyEvent.ACTION_DOWN), 0)
            inj.invoke(imgr, ev(android.view.KeyEvent.ACTION_UP), 0)
        } catch (t: Throwable) { Log.e(TAG, "inject key $keyCode failed", t) }
    }

    /** Inject a generic ACTION_SCROLL at (x,y) on [displayId]; +vscroll = up, -vscroll = down. */
    fun injectVScroll(displayId: Int, x: Float, y: Float, vscroll: Float) {
        val imgr = im ?: return
        val inj = injectMethod ?: return
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, SCROLL_MARKER, InputDevice.SOURCE_MOUSE, 0
        )
        try { setDisplayIdMethod?.invoke(ev, displayId) } catch (_: Throwable) {}
        try { inj.invoke(imgr, ev, 0) } catch (t: Throwable) { Log.e(TAG, "inject scroll failed", t) }
        ev.recycle()
    }
}
