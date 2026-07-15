package vn.vhn.pckeyboard.thor

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Horizontal strip of up to [MAX_APPS] recent apps, read from `dumpsys activity
 * recents` via su (normal apps can't query the recents list). Tap an icon to bring
 * that app to the main screen; swipe an icon down to force-stop it.
 */
class RecentAppsView(context: Context) : LinearLayout(context) {
    private val appCtx = context.applicationContext
    private val iconSize = dp(44)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    /** Re-query recents off the UI thread and repopulate. */
    fun refresh() {
        Thread {
            val pkgs = try { fetchRecents() } catch (e: Exception) { emptyList() }
            post { render(pkgs) }
        }.start()
    }

    private fun fetchRecents(): List<String> {
        val out = runSu("dumpsys activity recents | grep realActivity")
        val pm = appCtx.packageManager
        val pkgs = LinkedHashSet<String>()   // preserve recents order, dedupe
        val re = Regex("realActivity=\\{?([^/\\s{}]+)/")   // realActivity={pkg/act}
        for (line in out.lineSequence()) {
            val pkg = re.find(line)?.groupValues?.get(1) ?: continue
            if (pkg == appCtx.packageName) continue
            if (pm.getLaunchIntentForPackage(pkg) == null) continue   // must be launchable
            pkgs.add(pkg)
            if (pkgs.size >= MAX_APPS) break
        }
        return pkgs.toList()
    }

    private fun render(pkgs: List<String>) {
        removeAllViews()
        val pm = appCtx.packageManager
        for (pkg in pkgs) {
            val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { continue }
            val iv = ImageView(context)
            iv.setImageDrawable(icon)
            val lp = LayoutParams(iconSize, iconSize)
            lp.marginStart = dp(7); lp.marginEnd = dp(7)
            iv.layoutParams = lp
            attachTouch(iv, pkg)
            addView(iv)
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
        // force-stop kills the app's processes. The icon is already removed from the
        // strip for instant feedback. (Android 13 has no CLI to drop the recents
        // task, so a killed app may reappear here on a later refresh -- but stopped.)
        Thread { runSu("am force-stop $pkg") }.start()
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
    }
}
