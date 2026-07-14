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
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper

/**
 * Foreground service that shows the Hacker's Keyboard on the AYN Thor's second
 * screen (display 4) as a system overlay, and injects keystrokes (via
 * [InputInjector]) into whatever is focused on the main screen.
 *
 * A small floating toggle button lets the keyboard be shown/hidden so the second
 * screen stays free for other uses (DSKeyboard-style).
 */
class OverlayKeyboardService : Service() {

    private var displayContext: Context? = null
    private var windowManager: WindowManager? = null
    private var host: ThorKeyboardHost? = null
    private var toggleButton: View? = null
    private var keyboardShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        InputInjector.start(this)
        if (!setupDisplay()) {
            Log.e(TAG, "no secondary display found; stopping")
            stopSelf()
            return
        }
        addToggleButton()
        showKeyboard()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) toggleKeyboard()
        return START_STICKY
    }

    override fun onDestroy() {
        hideKeyboard()
        toggleButton?.let { safeRemove(it) }
        toggleButton = null
        host?.destroy()
        host = null
        InputInjector.stop()
        super.onDestroy()
    }

    // --- display / windows -----------------------------------------------------
    private fun setupDisplay(): Boolean {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = pickSecondaryDisplay(dm) ?: return false
        val ctx = createDisplayContext(display)
        displayContext = ContextThemeWrapper(ctx, androidThemeRes())
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "using display ${display.displayId} '${display.name}'")
        return true
    }

    private fun pickSecondaryDisplay(dm: DisplayManager): Display? {
        // Prefer a presentation display; otherwise any non-default display.
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()?.let { return it }
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun showKeyboard() {
        val wm = windowManager ?: return
        val ctx = displayContext ?: return
        if (keyboardShown) return
        if (host == null) {
            host = ThorKeyboardHost(ctx).apply { onHideRequested = { hideKeyboard() } }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        try {
            wm.addView(host!!.rootView, lp)
            keyboardShown = true
        } catch (e: Exception) {
            Log.e(TAG, "addView(keyboard) failed", e)
        }
    }

    private fun hideKeyboard() {
        if (!keyboardShown) return
        host?.rootView?.let { safeRemove(it) }
        keyboardShown = false
    }

    private fun toggleKeyboard() {
        if (keyboardShown) hideKeyboard() else showKeyboard()
    }

    private fun addToggleButton() {
        val wm = windowManager ?: return
        val ctx = displayContext ?: return
        val size = dp(48f)
        val btn = TextView(ctx).apply {
            text = "⌨"  // keyboard glyph
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC222222.toInt())
            }
            setOnClickListener { toggleKeyboard() }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(8f)
        lp.y = dp(8f)
        try {
            wm.addView(btn, lp)
            toggleButton = btn
        } catch (e: Exception) {
            Log.e(TAG, "addView(toggle) failed", e)
        }
    }

    private fun safeRemove(v: View) {
        try {
            if (v.parent != null) windowManager?.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun androidThemeRes(): Int = android.R.style.Theme_DeviceDefault

    // --- notification ----------------------------------------------------------
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Thor Keyboard", NotificationManager.IMPORTANCE_LOW)
            )
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
