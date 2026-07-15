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
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try { setDisplayIdMethod?.invoke(ev, displayId) } catch (_: Throwable) {}
        try { inj.invoke(imgr, ev, 0) } catch (t: Throwable) { Log.e(TAG, "inject scroll failed", t) }
        ev.recycle()
    }
}
