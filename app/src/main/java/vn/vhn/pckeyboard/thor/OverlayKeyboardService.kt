package vn.vhn.pckeyboard.thor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper

/**
 * Foreground service that shows the Hacker's Keyboard on the AYN Thor's second
 * screen (display 4) as a full-screen overlay "deck": a utility strip on top
 * (reserved for a trackpad / dev tools) and the keyboard filling the rest.
 * Keystrokes are injected (via [InputInjector]) into whatever is focused on the
 * main screen.
 *
 * The deck is opaque so the launcher behind it never shows through or catches
 * stray touches. A small floating toggle button appears when minimized so the
 * second screen stays free for other uses (DSKeyboard-style).
 */
class OverlayKeyboardService : Service() {

    private var displayContext: Context? = null
    private var windowManager: WindowManager? = null
    private var host: ThorKeyboardHost? = null
    private var deckView: View? = null
    private var floatingButton: View? = null
    private var gamepadButton: TextView? = null
    private var langButton: TextView? = null
    private var expanded = false
    private var gamepadOn = false
    private var vnOn = false
    private var mainDisplayWidth = 1920
    private var mainDisplayHeight = 1080

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        // Self-configure via root (overlay permission + default IME) so the deck
        // works standalone, however it was started (button or boot).
        ThorSetup.ensure(this)
        InputInjector.start(this)
        ImInjector.init(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        gamepadOn = prefs.getBoolean(PREF_GAMEPAD, false)
        InputInjector.setGamepad(gamepadOn)
        // Vietnamese (Unikey) input engine + saved on/off state.
        Unikey.init()
        vnOn = prefs.getBoolean(PREF_VN, false)
        if (!setupDisplay()) {
            Log.e(TAG, "no secondary display found; stopping")
            stopSelf()
            return
        }
        ThorController.expandRequest = { expand() }
        ThorController.setServiceRunning(true)
        expand()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_TEST_SCROLL -> {
                // Experiment: fire a burst of fine ACTION_SCROLL events at a point on
                // the main display to see if apps scroll (and how smoothly).
                val x = mainDisplayWidth * intent.getFloatExtra("xf", 0.5f)
                val y = mainDisplayHeight * intent.getFloatExtra("yf", 0.5f)
                val amount = intent.getFloatExtra("v", -0.3f)
                Thread {
                    repeat(40) {
                        ImInjector.injectVScroll(Display.DEFAULT_DISPLAY, x, y, amount)
                        try { Thread.sleep(16) } catch (_: InterruptedException) {}
                    }
                }.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ThorController.setServiceRunning(false)
        ThorController.expandRequest = null
        deckView?.let { safeRemove(it) }
        floatingButton?.let { safeRemove(it) }
        deckView = null
        floatingButton = null
        host?.destroy()
        host = null
        InputInjector.stop()
        super.onDestroy()
    }

    // --- display ---------------------------------------------------------------
    private fun setupDisplay(): Boolean {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = pickSecondaryDisplay(dm) ?: return false
        val ctx = createDisplayContext(display)
        displayContext = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "using display ${display.displayId} '${display.name}'")
        return true
    }

    private fun pickSecondaryDisplay(dm: DisplayManager): Display? {
        // Record the main display's width (for mapping the cursor to touch-scroll X).
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.let {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") it.getRealSize(p)
            if (p.x > 0) mainDisplayWidth = p.x
            if (p.y > 0) mainDisplayHeight = p.y
        }
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()?.let { return it }
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    // --- expand / minimize -----------------------------------------------------
    private fun expand() {
        val wm = windowManager ?: return
        val ctx = displayContext ?: return
        ThorController.suppressAutoShow = false   // reopened -> re-enable auto-show
        if (expanded) return
        floatingButton?.let { safeRemove(it); floatingButton = null }
        if (host == null) {
            host = ThorKeyboardHost(ctx, KEYBOARD_HEIGHT_PERCENT).apply {
                onHideRequested = { minimize() }
                setVnMethod(Unikey.TELEX)
                setVnMode(vnOn)
            }
        }
        val deck = buildDeck(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        try {
            wm.addView(deck, lp)
            deckView = deck
            expanded = true
            ThorController.setDeckShown(true)
            host?.forceRedraw()
        } catch (e: Exception) {
            Log.e(TAG, "addView(deck) failed", e)
        }
    }

    private fun minimize() {
        if (!expanded) return
        ThorController.suppressAutoShow = true   // stay hidden until manually reopened
        deckView?.let { safeRemove(it) }
        deckView = null
        // Destroy the keyboard host; a fresh one is built on the next expand.
        // Re-parenting the same LatinKeyboardView breaks its software-rendered
        // buffer (draws all black), so we never reuse it.
        host?.destroy()
        host = null
        expanded = false
        ThorController.setDeckShown(false)
        addFloatingButton()
    }

    private fun toggle() { if (expanded) minimize() else expand() }

    private fun toggleGamepad() {
        gamepadOn = !gamepadOn
        InputInjector.setGamepad(gamepadOn)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(PREF_GAMEPAD, gamepadOn).apply()
        gamepadButton?.alpha = if (gamepadOn) 1f else 0.4f
    }

    private fun toggleVn() {
        vnOn = !vnOn
        host?.setVnMode(vnOn)
        // commit (not apply): the deck service can be killed right after a toggle
        // (phantom-process killer), which would lose an async apply().
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(PREF_VN, vnOn).commit()
        langButton?.apply {
            text = if (vnOn) "Vi" else "En"
            setTextColor(if (vnOn) 0xFFF3CB63.toInt() else Color.WHITE)
        }
    }

    // --- deck view -------------------------------------------------------------
    // Full-screen opaque backdrop with the keyboard bottom-anchored (same layout
    // path as the standalone window, which renders correctly) and the utility
    // content placed in the exposed top area.
    private fun buildDeck(ctx: Context): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        // Recent-apps strip along the top (tap = switch to app on main screen, swipe
        // an icon down = kill it). Inset horizontally to clear the corner buttons.
        val recents = RecentAppsView(ctx)
        recents.setPadding(dp(52f), 0, dp(100f), 0)
        root.addView(recents, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(56f)).apply {
            gravity = Gravity.TOP; topMargin = dp(4f)
        })
        recents.refresh()

        // Trackpad fills the whole deck but is only exposed in the top area not
        // covered by the (bottom-anchored) keyboard; relative moves work anywhere.
        // Disabled for now (the strip is mostly taken by the keyboard + nav pad);
        // flip TRACKPAD_ENABLED to bring it back.
        if (TRACKPAD_ENABLED) {
            val trackpad = TrackpadView(ctx, mainDisplayWidth, mainDisplayHeight)
            root.addView(trackpad, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val hint = TextView(ctx).apply {
                text = "trackpad — drag to move, tap = click, 2-finger = scroll"
                setTextColor(0xFF555555.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                isClickable = false
            }
            root.addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(12f)
            })
        }


        val minimize = TextView(ctx).apply {
            text = "▾"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt())
            }
            setOnClickListener { minimize() }
        }
        val s = dp(40f)
        root.addView(minimize, FrameLayout.LayoutParams(s, s).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(8f); rightMargin = dp(8f)
        })

        // Gamepad-mouse toggle (folded joyscroll).
        val gp = TextView(ctx).apply {
            text = "🎮"  // gamepad emoji
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt())
            }
            alpha = if (gamepadOn) 1f else 0.4f
            setOnClickListener { toggleGamepad() }
        }
        gamepadButton = gp
        root.addView(gp, FrameLayout.LayoutParams(s, s).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(8f); rightMargin = dp(8f) + s + dp(8f)
        })

        // Language toggle (top-left): En <-> Vi (Vietnamese via Unikey).
        val lang = TextView(ctx).apply {
            text = if (vnOn) "Vi" else "En"
            gravity = Gravity.CENTER
            setTextColor(if (vnOn) 0xFFF3CB63.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt())
            }
            setOnClickListener { toggleVn() }
        }
        langButton = lang
        root.addView(lang, FrameLayout.LayoutParams(s, s).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(8f); leftMargin = dp(8f)
        })

        val kb = host!!.rootView
        (kb.parent as? ViewGroup)?.removeView(kb)
        root.addView(kb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // Cursor-navigation pad: drag = quantized arrow keys (symmetric return),
        // edge = auto-repeat. A symmetric-displacement control wants equal room
        // around the origin, so it's centred horizontally and dropped to the top
        // edge of the keyboard -- as close to the deck's vertical middle as the
        // strip allows, reachable by both thumbs. Added last so it sits on top of
        // the trackpad/keyboard and captures gestures that start inside it.
        val navPad = NavPadView(ctx).apply { metaProvider = { host?.navMetaState() ?: 0 } }
        val navLp = FrameLayout.LayoutParams(dp(240f), dp(56f)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8f)
        }
        root.addView(navPad, navLp)
        val placeNav = Runnable {
            val kbh = kb.height
            if (kbh > 0) { navLp.bottomMargin = kbh + dp(2f); navPad.layoutParams = navLp }
        }
        root.post(placeNav)
        root.postDelayed(placeNav, 320)
        return root
    }

    // When minimized, the only footprint on the second screen is a thin transparent
    // strip along the top edge: swipe down from it to bring the deck back. This keeps
    // the screen almost fully usable for other apps (no floating button in the way).
    private fun addFloatingButton() {
        val wm = windowManager ?: return
        val ctx = displayContext ?: return
        val strip = FrameLayout(ctx)
        // Faint handle hint at top-centre so the gesture is discoverable.
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3f).toFloat()
                setColor(0x66FFFFFF)
            }
        }
        strip.addView(handle, FrameLayout.LayoutParams(dp(90f), dp(5f)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(3f)
        })
        var downY = 0f
        var triggered = false
        strip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = ev.rawY; triggered = false }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (!triggered && ev.rawY - downY > dp(20f)) { triggered = true; expand() }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(18f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        try {
            wm.addView(strip, lp)
            floatingButton = strip
        } catch (e: Exception) {
            Log.e(TAG, "addView(show-handle) failed", e)
        }
    }

    private fun safeRemove(v: View) {
        try { if (v.parent != null) windowManager?.removeView(v) }
        catch (e: Exception) { Log.w(TAG, "removeView failed", e) }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    // --- notification ----------------------------------------------------------
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Thor Keyboard", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Thor Keyboard active")
            .setContentText("Second-screen keyboard is running")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ThorOverlay"
        private const val CHANNEL_ID = "thor_keyboard"
        private const val NOTIF_ID = 0x54484f52 // "THOR"
        private const val KEYBOARD_HEIGHT_PERCENT = 74f
        // Trackpad is kept in the codebase but not shown (little strip space left).
        private const val TRACKPAD_ENABLED = false
        private const val PREF_GAMEPAD = "thor_gamepad_mouse"
        private const val PREF_VN = "thor_vn_input"
        const val ACTION_TOGGLE = "vn.vhn.pckeyboard.thor.TOGGLE"
        const val ACTION_TEST_SCROLL = "vn.vhn.pckeyboard.thor.TEST_SCROLL"

        fun start(context: Context) {
            val i = Intent(context, OverlayKeyboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayKeyboardService::class.java))
        }
    }
}
