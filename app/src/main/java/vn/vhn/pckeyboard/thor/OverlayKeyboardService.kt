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
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        // Self-configure via root (overlay permission + default IME) so the deck
        // works standalone, however it was started (button or boot).
        ThorSetup.ensure(this)
        InputInjector.start(this)
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
        if (intent?.action == ACTION_TOGGLE) toggle()
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
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()?.let { return it }
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    // --- expand / minimize -----------------------------------------------------
    private fun expand() {
        val wm = windowManager ?: return
        val ctx = displayContext ?: return
        if (expanded) return
        floatingButton?.let { safeRemove(it); floatingButton = null }
        if (host == null) {
            host = ThorKeyboardHost(ctx, KEYBOARD_HEIGHT_PERCENT).apply {
                onHideRequested = { minimize() }
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
        } catch (e: Exception) {
            Log.e(TAG, "addView(deck) failed", e)
        }
    }

    private fun minimize() {
        if (!expanded) return
        deckView?.let { safeRemove(it) }
        // Detach the keyboard from the deck so it can be re-added on next expand.
        (host?.rootView?.parent as? ViewGroup)?.removeView(host?.rootView)
        deckView = null
        expanded = false
        ThorController.setDeckShown(false)
        addFloatingButton()
    }

    private fun toggle() { if (expanded) minimize() else expand() }

    // --- deck view -------------------------------------------------------------
    // Full-screen opaque backdrop with the keyboard bottom-anchored (same layout
    // path as the standalone window, which renders correctly) and the utility
    // content placed in the exposed top area.
    private fun buildDeck(ctx: Context): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        // Trackpad fills the whole deck but is only exposed in the top area not
        // covered by the (bottom-anchored) keyboard; relative moves work anywhere.
        val trackpad = TrackpadView(ctx)
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

        val kb = host!!.rootView
        (kb.parent as? ViewGroup)?.removeView(kb)
        root.addView(kb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        return root
    }

    private fun addFloatingButton() {
        val wm = windowManager ?: return
        val ctx = displayContext ?: return
        val size = dp(48f)
        val btn = TextView(ctx).apply {
            text = "⌨"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xCC222222.toInt())
            }
            setOnClickListener { expand() }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(8f); lp.y = dp(8f)
        try {
            wm.addView(btn, lp)
            floatingButton = btn
        } catch (e: Exception) {
            Log.e(TAG, "addView(floating) failed", e)
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
        private const val KEYBOARD_HEIGHT_PERCENT = 68f
        const val ACTION_TOGGLE = "vn.vhn.pckeyboard.thor.TOGGLE"

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
