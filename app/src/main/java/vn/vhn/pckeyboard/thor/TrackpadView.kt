package vn.vhn.pckeyboard.thor

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A relative trackpad surface for the deck's utility area.
 *
 * - Single-finger drag moves the mouse cursor (virtual mouse -> focused display).
 * - Quick single-finger tap = left click.
 * - Two-finger drag scrolls by injecting a real finger drag on the main screen
 *   (Android 13 ignores mouse wheel hi-res, so a touch drag is the only way to get
 *   smooth/kinetic scrolling). Coordinates are normalised 0..32767; when the drag
 *   nears a screen edge it lifts and re-anchors at centre so scrolling is unbounded.
 */
@SuppressLint("ClickableViewAccessibility")
class TrackpadView(context: Context) : View(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    // Two-finger touch-scroll state.
    private var scrolling = false
    private var scrollTouchY = TOUCH_CENTER
    private var scrollLastFingerY = 0f

    private val sensitivity = 1.6f
    private val tapSlop = 14f * resources.displayMetrics.density
    private val tapMaxMs = 250L
    // Normalised touch units per pixel of two-finger movement (scroll speed).
    private val scrollNormPerPx = 70f / resources.displayMetrics.density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                downTime = event.eventTime
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true // multi-touch is not a tap
                if (event.pointerCount == 2 && !scrolling) startScroll(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    doScroll(event)
                } else if (!scrolling && event.pointerCount == 1) {
                    val dx = ((event.x - lastX) * sensitivity).toInt()
                    val dy = ((event.y - lastY) * sensitivity).toInt()
                    if (dx != 0 || dy != 0) {
                        InputInjector.moveMouse(dx, dy)
                        lastX = event.x; lastY = event.y
                    }
                    if (abs(event.x - downX) > tapSlop || abs(event.y - downY) > tapSlop) moved = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifted; if we drop below two, end the scroll drag.
                if (scrolling && event.pointerCount <= 2) endScroll()
            }
            MotionEvent.ACTION_UP -> {
                if (scrolling) endScroll()
                else if (!moved && event.eventTime - downTime < tapMaxMs) InputInjector.click(0)
            }
            MotionEvent.ACTION_CANCEL -> if (scrolling) endScroll()
        }
        return true
    }

    private fun startScroll(event: MotionEvent) {
        scrolling = true
        scrollTouchY = TOUCH_CENTER
        scrollLastFingerY = event.getY(0)
        InputInjector.touchDown(TOUCH_CENTER, scrollTouchY)
    }

    private fun doScroll(event: MotionEvent) {
        val fy = event.getY(0)
        scrollTouchY += ((fy - scrollLastFingerY) * scrollNormPerPx).toInt()
        scrollLastFingerY = fy
        if (scrollTouchY <= TOUCH_MARGIN || scrollTouchY >= TOUCH_MAX - TOUCH_MARGIN) {
            // Re-anchor at centre so the drag can continue past a screen edge.
            InputInjector.touchUp()
            scrollTouchY = TOUCH_CENTER
            InputInjector.touchDown(TOUCH_CENTER, scrollTouchY)
        } else {
            InputInjector.touchMove(TOUCH_CENTER, scrollTouchY)
        }
    }

    private fun endScroll() {
        scrolling = false
        InputInjector.touchUp()
    }

    companion object {
        private const val TOUCH_MAX = 32767
        private const val TOUCH_CENTER = TOUCH_MAX / 2
        private const val TOUCH_MARGIN = 3000
    }
}
