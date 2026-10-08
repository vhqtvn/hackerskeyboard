package vn.vhn.pckeyboard.thor

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import vn.vhn.pckeyboard.Unikey
import vn.vhn.pckeyboard.VnComposer

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
    private var displayManager: DisplayManager? = null
    private var secondaryDisplayId = Display.INVALID_DISPLAY
    private var lastRotation = -1
    private var lastRealW = 0
    private var lastRealH = 0
    private var host: ThorKeyboardHost? = null
    private var deckView: View? = null
    private var floatingButton: View? = null
    private var gamepadButton: TextView? = null
    private var langButton: TextView? = null
    private var expanded = false
    private var gamepadOn = false
    private var vnOn = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mainDisplayWidth = 1920
    private var mainDisplayHeight = 1080
    private val keyguardManager by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private var deckLockedMode = false   // the current deck was built keyboard-only (locked)

    /** True when the keyguard is up: the deck must expose ONLY the keyboard. */
    private fun deviceLocked() = keyguardManager.isKeyguardLocked

    // Rebuild the deck when the lock state flips (lock -> keyboard-only, unlock -> full).
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (expanded && deviceLocked() != deckLockedMode) rebuildDeck()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // There is no stock type for "draws a keyboard on the second screen and
            // injects input", so this is specialUse; see the manifest for the subtype.
            startForeground(NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        // Self-configure via root (overlay permission + default IME) so the deck
        // works standalone, however it was started (button or boot).
        ThorSetup.ensure(this)
        InputInjector.start(this)
        // Gamepad buttons (from the daemon) routed through the keyboard path on the
        // main thread, so the deck's active modifiers apply (Shift + D-pad = select).
        InputInjector.gamepadKeyListener = { code, down ->
            mainHandler.post { host?.gamepadKey(code, down) }
        }
        // Left-stick analog scroll level -> smooth injected ACTION_SCROLL frame loop.
        InputInjector.scrollListener = { v, h -> ThorScroller.update(v, h) }
        ImInjector.init(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        gamepadOn = prefs.getBoolean(PREF_GAMEPAD, false)
        InputInjector.setGamepad(gamepadOn)
        // Vietnamese (Unikey) input engine + saved on/off state.
        Unikey.init()
        vnOn = prefs.getBoolean(PREF_VN, false)
        // Event-driven recents (hidden APIs are unsealed by ImInjector.init above).
        TaskWatcher.register()
        if (!setupDisplay()) {
            Log.e(TAG, "no secondary display found; stopping")
            stopSelf()
            return
        }
        // Fallback scroll target (module rewrites to the live cursor); main display centre.
        ThorScroller.fallbackX = mainDisplayWidth / 2f
        ThorScroller.fallbackY = mainDisplayHeight / 2f
        ThorController.expandRequest = { expand() }
        ThorController.setServiceRunning(true)
        androidx.core.content.ContextCompat.registerReceiver(this, lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)      // woke to lock screen
                addAction(Intent.ACTION_USER_PRESENT)   // unlocked
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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
        InputInjector.gamepadKeyListener = null
        InputInjector.scrollListener = null
        try { unregisterReceiver(lockReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacks(rebuildRunnable)
        displayManager?.unregisterDisplayListener(displayListener)
        ThorScroller.shutdown()
        TaskWatcher.unregister()
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
        displayManager = dm
        secondaryDisplayId = display.displayId
        rememberDisplayGeometry(display)
        // The deck width is derived from the keyboard, which is built from the second
        // screen's physical mode at expand time. That screen isn't meant to rotate, but
        // if it ever rotates/resizes the derived width goes stale -- just rebuild so it
        // re-derives. (Brightness/refresh-only onDisplayChanged events are ignored.)
        dm.registerDisplayListener(displayListener, mainHandler)
        Log.d(TAG, "using display ${display.displayId} '${display.name}'")
        return true
    }

    private fun rememberDisplayGeometry(display: Display) {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") display.getRealSize(p)
        lastRotation = display.rotation
        lastRealW = p.x
        lastRealH = p.y
    }

    private val rebuildRunnable = Runnable { rebuildDeck() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != secondaryDisplayId) return
            val d = displayManager?.getDisplay(displayId) ?: return
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") d.getRealSize(p)
            if (d.rotation == lastRotation && p.x == lastRealW && p.y == lastRealH) return
            rememberDisplayGeometry(d)
            mainHandler.removeCallbacks(rebuildRunnable)
            mainHandler.postDelayed(rebuildRunnable, 300)
        }
    }

    /** Tear down and re-create the open deck so it re-derives its width from the keyboard. */
    private fun rebuildDeck() {
        if (!expanded) return
        deckView?.let { safeRemove(it) }
        deckView = null
        host?.destroy()
        host = null
        expanded = false
        expand()
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
        // While locked, TYPE_APPLICATION_OVERLAY sits BELOW the secondary-display keyguard
        // (SecondaryKeyguard4), which then eats the deck's touches. TYPE_SYSTEM_ERROR layers
        // above the keyguard and is touchable (needs INTERNAL_SYSTEM_WINDOW, which we hold),
        // so the keyboard-only deck can actually be used to type the unlock credential.
        @Suppress("DEPRECATION")
        val windowType = if (deviceLocked()) WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                         else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                // Show/receive touches over the keyguard so the deck is usable while
                // locked (e.g. to type the unlock password) -- otherwise the keyguard
                // sits above this overlay on the 2nd screen and eats its touches.
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
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

        // Lock-screen mode: expose ONLY the keyboard (no recents / app-launch / nav pad /
        // toggle buttons) so the deck can type the unlock credential but can never be used
        // to bypass the lock or reach anything while the device is secured.
        deckLockedMode = deviceLocked()
        if (deckLockedMode) {
            val kb = host!!.rootView
            (kb.parent as? ViewGroup)?.removeView(kb)
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
            }
            col.addView(kb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val w = host!!.keyboardWidth().takeIf { it > 0 } ?: FrameLayout.LayoutParams.MATCH_PARENT
            root.addView(col, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.TOP or Gravity.START
            })
            return root
        }

        // Vertical column so the top area distributes cleanly (no dead band / no
        // fragile measured-height offsets): recents on top, nav pad fills the middle,
        // keyboard takes its natural height at the bottom.
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Recent-apps strip (tap = switch to app on main screen, swipe an icon down =
        // kill it). Inset horizontally to clear the corner buttons. Self-refreshes.
        val recents = RecentAppsView(ctx)
        // The deck renders on the second screen, whose density differs from the main
        // display (which the service's dp() uses). Pad vertically in the *display*
        // density so the strip wraps taller than the icons -- otherwise they clip.
        val ddens = ctx.resources.displayMetrics.density
        val vpad = (8f * ddens).toInt()
        recents.setPadding(0, vpad, 0, vpad)
        // Left-align so the most-recent icons start at the left edge; the strip lives
        // in a HorizontalScrollView (built into the top bar below) so extra apps stay
        // reachable by scrolling rather than being clipped. The toggle buttons are
        // real bar siblings, so the strip can never grow over them however many apps
        // accumulate.
        recents.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val recentsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(recents, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        // Nav line (one keyboard-row tall, fills the gap): [Home] [cursor pad] [End].
        // Home/End are key-styled buttons at the same height as the keyboard rows;
        // they inject with the deck's active modifiers (so Shift+Home selects).
        val navPad = NavPadView(ctx).apply { metaProvider = { host?.navMetaState() ?: 0 } }
        // Home/End are sent via the soft-keyboard InputConnection (not uinput): they
        // reach the focused app directly (text fields, terminals, editors all honour
        // them) and skip the system policy that turns an injected Home into device-home.
        val homeBtn = navKey(ctx, "⇱") { host?.sendEditKey(android.view.KeyEvent.KEYCODE_MOVE_HOME) }
        val endBtn = navKey(ctx, "⇲") { host?.sendEditKey(android.view.KeyEvent.KEYCODE_MOVE_END) }
        val navRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val m = dp(4f)
        navRow.addView(homeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { marginStart = m; topMargin = m; bottomMargin = m; marginEnd = m })
        navRow.addView(navPad, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 8f)
            .apply { topMargin = m; bottomMargin = m })
        navRow.addView(endBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { marginStart = m; topMargin = m; bottomMargin = m; marginEnd = m })
        col.addView(navRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))   // weight: fills remaining (~1 row)

        // Keyboard (natural height ~KEYBOARD_HEIGHT_PERCENT) at the bottom.
        val kb = host!!.rootView
        (kb.parent as? ViewGroup)?.removeView(kb)
        col.addView(kb, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Pin the deck's content to the keyboard's fixed intrinsic width, left-aligned.
        // The keyboard view ignores its parent's width (LatinKeyboardBaseView.onMeasure),
        // so it is the one node that always reflects the real second-screen width. If the
        // overlay window is ever measured at the wrong (main-display ~1920) width -- which
        // happens on wake -- the weighted top bar + nav rows would otherwise stretch to it
        // and push the toggle buttons / End key off the visible screen. Pinning col to the
        // keyboard width keeps every row exactly as wide as the keyboard, window be damned.
        val contentW = host!!.keyboardWidth().takeIf { it > 0 }
            ?: FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(col, FrameLayout.LayoutParams(contentW, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP or Gravity.START
        })

        // --- top bar: [lang] [recents strip] [gamepad] [minimize] ---
        // The toggle buttons are real row siblings (not overlays), so the recents
        // strip can never grow over them however many apps accumulate.
        val s = dp(40f)
        val minimize = TextView(ctx).apply {
            text = "▾"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt()) }
            setOnClickListener { minimize() }
        }
        val gp = TextView(ctx).apply {
            text = "🎮"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt()) }
            alpha = if (gamepadOn) 1f else 0.4f
            setOnClickListener { toggleGamepad() }
        }
        gamepadButton = gp
        val lang = TextView(ctx).apply {
            text = if (vnOn) "Vi" else "En"; gravity = Gravity.CENTER
            setTextColor(if (vnOn) 0xFFF3CB63.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt()) }
            setOnClickListener { toggleVn() }
        }
        langButton = lang

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(lang, LinearLayout.LayoutParams(s, s).apply {
            marginStart = dp(8f); marginEnd = dp(8f)
        })
        // Weighted middle: the scroll strip gets whatever width is left after the
        // buttons (width=0 + weight), so it can never encroach on them; apps past
        // that width scroll into view instead of being clipped.
        topBar.addView(recentsScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(gp, LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(8f) })
        topBar.addView(minimize, LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(8f) })
        col.addView(topBar, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f)
        })

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

    /** A key-styled button (black body, gold edit/nav border) for the nav line. */
    private fun navKey(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8f).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(2f), 0x88F3CB63.toInt())
            }
            setOnClickListener { onClick() }
        }
    }

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
        private const val KEYBOARD_HEIGHT_PERCENT = 75f
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
