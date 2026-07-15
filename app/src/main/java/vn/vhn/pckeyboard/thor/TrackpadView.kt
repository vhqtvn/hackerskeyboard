package vn.vhn.pckeyboard.thor

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A relative trackpad surface for the deck's utility area.
 *
 * - Single-finger drag moves the mouse cursor (virtual mouse -> focused display),
 *   tracked here so scrolling can anchor under the cursor.
 * - Quick single-finger tap = left click.
 * - Two-finger drag scrolls by injecting a real finger drag on the main screen
 *   (Android 13 ignores mouse-wheel hi-res, so a touch drag is the only smooth,
 *   kinetic option). To avoid a stationary two-finger touch being read as a *tap*
 *   (which would click whatever is under it), the touch-down is deferred until the
 *   gesture has actually moved. The drag is anchored at the cursor's X column (so it
 *   scrolls the pane/list you're pointing at) and re-anchors at centre near a screen
 *   edge so scrolling is unbounded.
 */
@SuppressLint("ClickableViewAccessibility")
class TrackpadView(context: Context, private val mainDisplayWidth: Int) : View(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    // Estimated cursor X on the main display (px), for anchoring the scroll column.
    private var cursorX = mainDisplayWidth / 2f

    // Two-finger touch-scroll state.
    private var scrolling = false
    private var scrollStarted = false      // touch-down actually injected yet?
    private var scrollAnchorX = TOUCH_CENTER
    private var scrollTouchY = TOUCH_CENTER
    private var scrollLastFingerY = 0f
    private var scrollPending = 0f          // movement accumulated before start threshold

    private val sensitivity = 1.6f
    private val tapSlop = 14f * resources.displayMetrics.density
    private val tapMaxMs = 250L
    private val density = resources.displayMetrics.density
    // Normalised touch units per pixel of two-finger movement (scroll speed).
    private val scrollNormPerPx = 70f / density
    // Movement (px) required before a two-finger gesture becomes a scroll (vs tap).
    private val scrollStartSlop = 10f * density

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
                if (event.pointerCount == 2 && !scrolling) beginScroll(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    doScroll(event)
                } else if (!scrolling && event.pointerCount == 1) {
                    val dxPx = event.x - lastX
                    val dyPx = event.y - lastY
                    val dx = (dxPx * sensitivity).toInt()
                    val dy = (dyPx * sensitivity).toInt()
                    if (dx != 0 || dy != 0) {
                        InputInjector.moveMouse(dx, dy)
                        cursorX = (cursorX + dx).coerceIn(0f, mainDisplayWidth.toFloat())
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

    private fun beginScroll(event: MotionEvent) {
        scrolling = true
        scrollStarted = false
        scrollPending = 0f
        scrollLastFingerY = event.getY(0)
        // Anchor the scroll column under the cursor.
        scrollAnchorX = (cursorX / mainDisplayWidth * TOUCH_MAX).toInt()
            .coerceIn(TOUCH_MARGIN, TOUCH_MAX - TOUCH_MARGIN)
    }

    private fun doScroll(event: MotionEvent) {
        val fy = event.getY(0)
        val dPx = fy - scrollLastFingerY
        scrollLastFingerY = fy
        if (!scrollStarted) {
            // Defer the touch-down until real movement, so a stationary two-finger
            // touch never lands as a tap/click on the main screen.
            scrollPending += dPx
            if (abs(scrollPending) < scrollStartSlop) return
            scrollStarted = true
            scrollTouchY = TOUCH_CENTER
            InputInjector.touchDown(scrollAnchorX, scrollTouchY)
            applyScroll(scrollPending)
        } else {
            applyScroll(dPx)
        }
    }

    private fun applyScroll(dPx: Float) {
        scrollTouchY += (dPx * scrollNormPerPx).toInt()
        if (scrollTouchY <= TOUCH_MARGIN || scrollTouchY >= TOUCH_MAX - TOUCH_MARGIN) {
            // Re-anchor at centre so the drag can continue past a screen edge.
            InputInjector.touchUp()
            scrollTouchY = TOUCH_CENTER
            InputInjector.touchDown(scrollAnchorX, scrollTouchY)
        } else {
            InputInjector.touchMove(scrollAnchorX, scrollTouchY)
        }
    }

    private fun endScroll() {
        if (scrollStarted) InputInjector.touchUp()
        scrolling = false
        scrollStarted = false
    }

    companion object {
        private const val TOUCH_MAX = 32767
        private const val TOUCH_CENTER = TOUCH_MAX / 2
        private const val TOUCH_MARGIN = 3000
    }
}
