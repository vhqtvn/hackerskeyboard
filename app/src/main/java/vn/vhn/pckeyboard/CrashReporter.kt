package vn.vhn.pckeyboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Shows an uncaught exception on screen instead of letting it vanish into logcat.
 *
 * An IME crash is easy to miss -- the keyboard simply stops appearing -- so non-Play
 * builds install this to surface the stack trace with a way to copy or share it.
 *
 * Replaces the old `me.jfenn:crasher` dependency, which only ever resolved from a
 * stale local Gradle cache (JCenter is shut down) and pulled in the pre-AndroidX
 * design support library.
 */
object CrashReporter {
    const val EXTRA_THREAD = "vn.vhn.pckeyboard.CRASH_THREAD"
    const val EXTRA_TRACE = "vn.vhn.pckeyboard.CRASH_TRACE"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Best-effort: whatever happens here, still hand the crash on and die.
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                app.startActivity(Intent(app, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_THREAD, thread.name)
                    putExtra(EXTRA_TRACE, trace)
                })
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}

/**
 * Displays the trace handed over by [CrashReporter]. Runs in its own process (see the
 * manifest) so it survives the process that crashed.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thread = intent.getStringExtra(CrashReporter.EXTRA_THREAD) ?: "?"
        val trace = intent.getStringExtra(CrashReporter.EXTRA_TRACE) ?: "(no stack trace)"
        val report = "VHKeyboard crashed\n" +
                "thread: $thread\n" +
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})\n\n" + trace

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "VHKeyboard crashed"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(pad, pad, pad, dp(8))
        })

        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                setTextColor(0xFFCCCCCC.toInt())
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(pad, 0, pad, pad)
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(pad, 0, pad, pad)
            addView(button("Copy") {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("VHKeyboard crash", report))
            })
            addView(button("Share") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "VHKeyboard crash")
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "Share crash report"))
            })
            addView(button("Close") { finish() })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}
