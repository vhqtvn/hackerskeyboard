package vn.vhn.pckeyboard.thor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs

/**
 * A relative trackpad surface for the deck's utility area.
 *
 * - Single-finger drag moves the mouse cursor (virtual mouse -> focused display),
 *   tracked here so scrolling targets what the cursor is over.
 * - Quick single-finger tap = left click.
 * - Two-finger drag scrolls by injecting generic ACTION_SCROLL (mouse-wheel) events
 *   at the cursor (via [ImInjector], in-process). ACTION_SCROLL has no built-in fling,
 *   so on release we synthesize momentum: keep injecting a velocity-decayed scroll,
 *   giving kinetic scrolling even on native lists (which otherwise "stick").
 */
@SuppressLint("ClickableViewAccessibility")
class TrackpadView(
    context: Context,
    private val mainDisplayWidth: Int,
    private val mainDisplayHeight: Int,
) : View(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    // Estimated cursor position on the main display (px), for scroll targeting.
    private var cursorX = mainDisplayWidth / 2f
    private var cursorY = mainDisplayHeight / 2f

    // Two-finger scroll state.
    private var scrolling = false
    private var scrollLastY = 0f
    private var velocityTracker: VelocityTracker? = null

    // Fling.
    private val flingHandler = Handler(Looper.getMainLooper())
    private var flingVel = 0f  // px/s (finger-equivalent)

    private val sensitivity = 1.6f
    private val tapSlop = 14f * resources.displayMetrics.density
    private val tapMaxMs = 250L
    // AXIS_VSCROLL units per pixel of two-finger movement (scroll speed; tunable).
    private val vscrollPerPx = 0.06f
    private val minFlingVel = 300f      // px/s to start a fling
    private val flingDecay = 0.9f       // per 16ms frame
    private val flingStopVel = 60f      // px/s to end the fling

    private val flingRunnable = object : Runnable {
        override fun run() {
            val dyFrame = flingVel * 0.016f  // px this frame
            ImInjector.injectVScroll(0, cursorX, cursorY, dyFrame * vscrollPerPx)
            flingVel *= flingDecay
            if (abs(flingVel) > flingStopVel) flingHandler.postDelayed(this, 16)
        }
    }

    private fun stopFling() = flingHandler.removeCallbacks(flingRunnable)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                downTime = event.eventTime
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true // multi-touch is not a tap
                if (event.pointerCount == 2) {
                    scrolling = true
                    scrollLastY = event.getY(0)
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    velocityTracker?.addMovement(event)
                    val fy = event.getY(0)
                    val dy = fy - scrollLastY
                    scrollLastY = fy
                    if (dy != 0f) {
                        ImInjector.injectVScroll(0, cursorX, cursorY, dy * vscrollPerPx)
                    }
                } else if (!scrolling && event.pointerCount == 1) {
                    val dx = ((event.x - lastX) * sensitivity).toInt()
                    val dy = ((event.y - lastY) * sensitivity).toInt()
                    if (dx != 0 || dy != 0) {
                        InputInjector.moveMouse(dx, dy)
                        cursorX = (cursorX + dx).coerceIn(0f, mainDisplayWidth.toFloat())
                        cursorY = (cursorY + dy).coerceIn(0f, mainDisplayHeight.toFloat())
                        lastX = event.x; lastY = event.y
                    }
                    if (abs(event.x - downX) > tapSlop || abs(event.y - downY) > tapSlop) moved = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
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

    private fun endScroll() {
        scrolling = false
        val vt = velocityTracker
        if (vt != null) {
            vt.computeCurrentVelocity(1000)
            flingVel = vt.getYVelocity(0)
            vt.recycle()
            velocityTracker = null
            if (abs(flingVel) > minFlingVel) {
                stopFling()
                flingHandler.post(flingRunnable)
            }
        }
    }
}
