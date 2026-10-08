package vn.vhn.pckeyboard.thor

import android.os.Handler
import android.os.HandlerThread

/**
 * Drives smooth gamepad scrolling. The daemon forwards the left-stick analog scroll level
 * (signed, [-1000,1000] per axis) only when it changes; we run a ~60Hz frame loop while the
 * level is non-zero, injecting a fractional ACTION_SCROLL each frame via [ImInjector]. The
 * sig-grant module rewrites each event's coordinates to the live mouse cursor, so scrolling
 * lands on whatever the cursor hovers. The loop stops itself when the level returns to zero,
 * so it costs nothing while the stick is centred.
 */
object ThorScroller {
    private const val FRAME_MS = 16L
    private const val LEVELS = 1000f
    /** Wheel-notch units per frame at full stick deflection (tune for feel). */
    private const val V_SPEED = 0.6f
    private const val H_SPEED = 0.6f

    @Volatile private var vLevel = 0
    @Volatile private var hLevel = 0
    @Volatile private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Fallback scroll target; the module rewrites this to the live cursor. Set to the main
     *  display centre by the service once it knows the dimensions. */
    @Volatile var fallbackX = 960f
    @Volatile var fallbackY = 540f

    /** New scroll level from the daemon (background thread). */
    fun update(v: Int, h: Int) {
        vLevel = v
        hLevel = h
        if (v != 0 || h != 0) start()
    }

    /** Stop and release the loop thread (e.g. on service destroy). */
    fun shutdown() {
        vLevel = 0; hLevel = 0
        stop()
    }

    @Synchronized
    private fun start() {
        if (running) return
        running = true
        val t = HandlerThread("thor-scroll").apply { start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
    }

    @Synchronized
    private fun stop() {
        if (!running) return
        running = false
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val v = vLevel
            val h = hLevel
            if (v == 0 && h == 0) { stop(); return }
            // Stick down (v > 0) scrolls content down => negative AXIS_VSCROLL.
            val vs = -(v / LEVELS) * V_SPEED
            val hs = (h / LEVELS) * H_SPEED
            ImInjector.injectScroll(0, fallbackX, fallbackY, vs, hs)
            handler?.postDelayed(this, FRAME_MS)
        }
    }
}
