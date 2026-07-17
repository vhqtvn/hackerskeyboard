package vn.vhn.pckeyboard.thor

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Horizontal strip of up to [MAX_APPS] recent apps, read from `dumpsys activity
 * recents` via su (normal apps can't query the recents list). Tap an icon to bring
 * that app to the main screen; swipe an icon down to force-stop it. A green dot marks
 * apps that still have a live process (running in the background). Auto-refreshes
 * while visible.
 */
class RecentAppsView(context: Context) : LinearLayout(context) {
    private val appCtx = context.applicationContext
    private val iconSize = dp(44)

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private val poll = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, POLL_MS) }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Event-driven: refresh when the task stack changes (pushed by TaskWatcher).
        TaskWatcher.onChanged = { post { refresh() } }
        refresh()
        // Only fall back to timer polling if the listener couldn't register.
        if (!TaskWatcher.active) handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        TaskWatcher.onChanged = null
        handler.removeCallbacks(poll)
    }

    /** Re-query recents + running state off the UI thread and repopulate. */
    fun refresh() {
        if (busy) return
        busy = true
        Thread {
            val (pkgs, running) = try { fetchState() } catch (e: Exception) { emptyList<String>() to emptySet() }
            post { render(pkgs, running); busy = false }
        }.start()
    }

    private fun fetchState(): Pair<List<String>, Set<String>> {
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = appCtx.packageManager
        val homePkgs = homePackages(pm)
        val pkgs = LinkedHashSet<String>()   // recents order, deduped
        try {
            @Suppress("DEPRECATION")
            val tasks = am.getRecentTasks(MAX_APPS * 4, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            for (t in tasks) {
                val pkg = t.baseActivity?.packageName
                    ?: t.topActivity?.packageName
                    ?: t.baseIntent?.component?.packageName
                    ?: continue
                if (pkg == appCtx.packageName) continue
                if (pkg in homePkgs) continue          // don't show the launcher(s) as an app
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                pkgs.add(pkg)
                if (pkgs.size >= MAX_APPS) break
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getRecentTasks failed (REAL_GET_TASKS granted?)", e)
        }
        // Which of them still have a live process (running in background)?
        val running = HashSet<String>()
        try {
            am.runningAppProcesses?.forEach { p -> p.pkgList?.forEach { running.add(it) } }
        } catch (_: Throwable) {}
        return pkgs.toList() to running
    }

    /**
     * Packages that act as a home launcher, so we can hide them from the strip (the
     * user's launcher is a task like any other and would otherwise show as an app).
     * Only real launchers register their HOME activity at priority >= 0; boot stubs
     * like Settings' FallbackHome sit at a negative priority, so filtering on priority
     * drops the launcher(s) without also dropping the real Settings app.
     */
    private fun homePackages(pm: PackageManager): Set<String> {
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.priority >= 0 }
                .mapNotNull { it.activityInfo?.packageName }
                .toHashSet()
        } catch (e: Throwable) {
            Log.e(TAG, "homePackages query failed", e)
            emptySet()
        }
    }

    private fun render(pkgs: List<String>, running: Set<String>) {
        removeAllViews()
        val pm = appCtx.packageManager
        for (pkg in pkgs) {
            val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { continue }
            // Fixed square cell (the strip wraps to this height); icon fills it,
            // centred vertically. The running dot is a badge on the icon's bottom.
            val cell = FrameLayout(context)
            val lp = LayoutParams(iconSize, iconSize)
            lp.marginStart = dp(6); lp.marginEnd = dp(6)
            lp.gravity = Gravity.CENTER_VERTICAL
            cell.layoutParams = lp

            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageDrawable(icon)
            cell.addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            if (running.contains(pkg)) {
                val dot = View(context)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF3DDC84.toInt())            // green = running
                    setStroke(dp(1), 0xFF000000.toInt())    // outline for contrast
                }
                cell.addView(dot, FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END   // bottom-right badge, on the circle's edge
                    bottomMargin = dp(3); marginEnd = dp(3)
                })
            }

            attachTouch(cell, pkg)
            addView(cell)
        }
    }

    /** Tap = activate; swipe down = kill. */
    private fun attachTouch(v: View, pkg: String) {
        var downX = 0f
        var downY = 0f
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; view.alpha = 0.5f }
                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    if (dy > dp(28) && dy > Math.abs(dx)) { removeView(view); killApp(pkg) }
                    else if (Math.abs(dx) < dp(16) && Math.abs(dy) < dp(16)) launchApp(pkg)
                }
                MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            true
        }
    }

    private fun launchApp(pkg: String) {
        val intent = appCtx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                appCtx.startActivity(intent, opts.toBundle())
            } else {
                appCtx.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "launch $pkg failed", e)
        }
    }

    private fun killApp(pkg: String) {
        // force-stop kills the app's processes; the icon is already removed for
        // instant feedback. (Android 13 has no CLI to drop the recents task.)
        Thread {
            runSu("am force-stop $pkg")
            post { refresh() }   // reflect new running state
        }.start()
    }

    private fun runSu(cmd: String): String {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        val text = BufferedReader(InputStreamReader(p.inputStream)).readText()
        try { p.waitFor() } catch (_: InterruptedException) {}
        return text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ThorRecents"
        private const val MAX_APPS = 8
        private const val POLL_MS = 3000L
    }
}
