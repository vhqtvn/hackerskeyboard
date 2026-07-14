package vn.vhn.pckeyboard.thor

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A relative trackpad surface for the deck's utility area. Single-finger drag
 * moves the mouse cursor (via [InputInjector], which injects a virtual mouse that
 * targets the focused display -- the main screen); a quick tap is a left click;
 * two-finger drag scrolls the wheel.
 */
@SuppressLint("ClickableViewAccessibility")
class TrackpadView(context: Context) : View(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var scrollAccum = 0f

    private val sensitivity = 1.6f
    private val tapSlop = 14f * resources.displayMetrics.density
    private val scrollStep = 26f * resources.displayMetrics.density
    private val tapMaxMs = 250L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                downTime = event.eventTime
                moved = false
                scrollAccum = 0f
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Reset reference point on finger-count change to avoid jumps.
                lastX = event.x; lastY = event.y
                moved = true // a multi-touch gesture is not a tap
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    scrollAccum += (lastY - event.y)
                    while (abs(scrollAccum) >= scrollStep) {
                        val dir = if (scrollAccum > 0) 1 else -1
                        InputInjector.scroll(dir)
                        scrollAccum -= dir * scrollStep
                    }
                    lastY = event.y; lastX = event.x
                } else {
                    val dx = ((event.x - lastX) * sensitivity).toInt()
                    val dy = ((event.y - lastY) * sensitivity).toInt()
                    if (dx != 0 || dy != 0) {
                        InputInjector.moveMouse(dx, dy)
                        lastX = event.x; lastY = event.y
                    }
                    if (abs(event.x - downX) > tapSlop || abs(event.y - downY) > tapSlop) moved = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && event.eventTime - downTime < tapMaxMs) InputInjector.click(0)
                return true
            }
        }
        return true
    }
}
