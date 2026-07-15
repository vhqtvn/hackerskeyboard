package vn.vhn.pckeyboard.thor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Cursor-navigation pad: press and drag to emit arrow keys by *quantized
 * displacement*, so the text cursor on the focused field/terminal (main screen)
 * follows the finger and can be nudged with precision.
 *
 * Model (per axis, X and Y independent):
 *  - The touch-down point is the origin. Emitted arrows always equal
 *    `round((finger - origin) / STEP)`, so dragging back toward the origin emits
 *    the opposite arrows and the cursor returns to where it started -- fully
 *    symmetric. Move right ~2 steps -> `-> ->`; slide back -> `<- <-`.
 *  - When the finger reaches the pad border (cannot move further), the arrow
 *    auto-repeats at the key-repeat rate. Each border tick shifts the origin
 *    *outward* by one STEP so the invariant above still holds: those border
 *    moves become a new center and are NOT undone on the way back. Border travel
 *    converts into center travel, as opposed to the symmetric (returnable) zone.
 *
 * Arrows are injected as DPAD keys via [InputInjector].
 */
class NavPadView(context: Context) : View(context) {

    private var originX = 0f
    private var originY = 0f
    private var emittedX = 0   // net arrows this gesture: +right / -left
    private var emittedY = 0   // +down / -up
    private var pinX = 0       // border-repeat direction: -1 / 0 / +1
    private var pinY = 0
    private var active = false

    private val handler = Handler(Looper.getMainLooper())
    private val repeatTick = object : Runnable {
        override fun run() {
            if (pinX != 0) borderStep(true, pinX)
            if (pinY != 0) borderStep(false, pinY)
            if (pinX != 0 || pinY != 0) handler.postDelayed(this, REPEAT_MS)
        }
    }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x55FFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                originX = event.x; originY = event.y
                emittedX = 0; emittedY = 0; pinX = 0; pinY = 0
                active = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (active) { handleMove(event.x, event.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false; pinX = 0; pinY = 0
                handler.removeCallbacks(repeatTick)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return active
    }

    private fun handleMove(rawX: Float, rawY: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = rawX.coerceIn(0f, w); val cy = rawY.coerceIn(0f, h)
        // Border pinning: finger at / past an edge -> repeat outward from that edge.
        pinX = when { rawX <= EDGE -> -1; rawX >= w - EDGE -> 1; else -> 0 }
        pinY = when { rawY <= EDGE -> -1; rawY >= h - EDGE -> 1; else -> 0 }
        // Symmetric displacement (clamped so the border repeat is the only extra source).
        drive(true, Math.round((cx - originX) / STEP_X))
        drive(false, Math.round((cy - originY) / STEP_Y))
        if (pinX != 0 || pinY != 0) {
            handler.removeCallbacks(repeatTick); handler.postDelayed(repeatTick, REPEAT_MS)
        } else {
            handler.removeCallbacks(repeatTick)
        }
    }

    /** Emit arrows to bring the emitted count to [target] on the given axis. */
    private fun drive(xAxis: Boolean, target: Int) {
        if (xAxis) {
            while (emittedX < target) { emit(KeyEvent.KEYCODE_DPAD_RIGHT); emittedX++ }
            while (emittedX > target) { emit(KeyEvent.KEYCODE_DPAD_LEFT); emittedX-- }
        } else {
            while (emittedY < target) { emit(KeyEvent.KEYCODE_DPAD_DOWN); emittedY++ }
            while (emittedY > target) { emit(KeyEvent.KEYCODE_DPAD_UP); emittedY-- }
        }
    }

    /** One auto-repeat step at the border; shifts origin outward so it isn't returned. */
    private fun borderStep(xAxis: Boolean, dir: Int) {
        if (xAxis) {
            emit(if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            emittedX += dir
            originX -= dir * STEP_X
        } else {
            emit(if (dir > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            emittedY += dir
            originY -= dir * STEP_Y
        }
    }

    private fun emit(keyCode: Int) {
        InputInjector.injectAndroidKey(keyCode, 0)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 2f
        rect.set(pad, pad, width - pad, height - pad)
        val r = height * 0.18f
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, border)
        label.textSize = height * 0.16f
        canvas.drawText("✚ cursor", width / 2f, height * 0.42f, label)
        label.textSize = height * 0.13f
        canvas.drawText("drag ←↑↓→  ·  edge = repeat",
            width / 2f, height * 0.68f, label)
    }

    companion object {
        // Distance the finger must travel per emitted arrow (px; screen ~419 dpi
        // -> STEP_X ~2.9 mm, STEP_Y ~2.5 mm). Y is finer since lines are shorter moves.
        private const val STEP_X = 48f
        private const val STEP_Y = 42f
        private const val EDGE = 24f     // proximity to an edge that starts border repeat
        private const val REPEAT_MS = 90L
    }
}
