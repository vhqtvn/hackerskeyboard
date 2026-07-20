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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
    private var displayManager: DisplayManager? = null
    private var secondaryDisplayId = Display.INVALID_DISPLAY
    private var healAttempts = 0
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
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
        mainHandler.removeCallbacksAndMessages(null)
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
        // Waking the second screen (and possibly other, unreproduced events) can leave
        // the open deck mis-laid-out: the weighted top bar + nav rows overflow and push
        // their right-edge children -- the toggle buttons, the End key -- off-screen.
        // The display geometry does NOT change on wake, so we can't key off that; instead
        // a global-layout listener (see expand) detects the bad layout and self-heals.
        // This listener just nudges a re-check after any display event on our screen.
        dm.registerDisplayListener(displayListener, mainHandler)
        Log.d(TAG, "using display ${display.displayId} '${display.name}'")
        return true
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != secondaryDisplayId) return
            healAttempts = 0   // a new display event (e.g. wake) gets a fresh heal budget
            mainHandler.postDelayed({ healDeckIfBroken() }, 300)
        }
    }

    /** Physical width (px) of the second screen in its current rotation, or 0. */
    private fun secondaryScreenWidth(): Int {
        val d = displayManager?.getDisplay(secondaryDisplayId) ?: return 0
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") d.getRealSize(p)
        return p.x
    }

    /**
     * The wake-from-sleep bug: the whole deck sometimes lays out at the MAIN display's
     * width (~1920) instead of this screen's (~1240) -- the tree-boundary arithmetic on
     * a captured bad frame gives row width ~= 1920 (Home key ~= width/10 of the 1:8:1
     * nav row). Because the ENTIRE tree (root included) scales up together, children
     * still look in-bounds *relative to the deck*, so we must judge against the physical
     * screen width, not deck.width. Symptom: deck wider than the screen, or the gamepad
     * toggle laid out at/past the screen's right edge.
     */
    private fun deckIsBroken(): Boolean {
        val deck = deckView ?: return false
        val gp = gamepadButton ?: return false
        val screenW = secondaryScreenWidth()
        if (!expanded || deck.width == 0 || gp.width == 0 || screenW == 0) return false
        val loc = IntArray(2)
        gp.getLocationInWindow(loc)
        val gpRightAbs = loc[0] + gp.width
        return deck.width > screenW || loc[0] >= screenW || gpRightAbs > screenW
    }

    private val healRunnable = Runnable {
        if (!deckIsBroken()) { healAttempts = 0; return@Runnable }
        if (healAttempts >= 4) { Log.w(TAG, "deck still broken after $healAttempts rebuilds; giving up"); return@Runnable }
        healAttempts++
        val gp = gamepadButton; val loc = IntArray(2); gp?.getLocationInWindow(loc)
        Log.w(TAG, "deck wider than screen (deckW=${deckView?.width} screenW=${secondaryScreenWidth()} gpRight=${loc[0] + (gp?.width ?: 0)}); rebuild #$healAttempts")
        rebuildDeck()
    }

    /**
     * Debounced self-heal: whatever produced the bad layout, the bad layout itself fires
     * the deck's global-layout listener. Wait 400ms for the (wrong) layout to settle,
     * then rebuild if still bad. A fresh [buildDeck] lays out at the correct width; the
     * attempt cap (reset on any good layout) stops a persistently-bad deck from looping.
     */
    private fun healDeckIfBroken() {
        mainHandler.removeCallbacks(healRunnable)
        if (deckIsBroken()) mainHandler.postDelayed(healRunnable, 400)
        else healAttempts = 0
    }

    /** Tear down and re-create the open deck so it re-measures cleanly. */
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
            // Self-heal: if a layout pass ever leaves the toggle buttons off-screen
            // (the observed wake-from-sleep bug), rebuild the deck. healAttempts is NOT
            // reset here -- it resets only when a good layout is observed, so a deck
            // that stays broken across rebuilds gives up instead of looping forever.
            deck.viewTreeObserver.addOnGlobalLayoutListener { healDeckIfBroken() }
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

        root.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
