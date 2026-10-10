package vn.vhn.pckeyboard

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * From API 35 Android draws activities edge to edge whether they asked for it or not,
 * so anything that used to sit below the status bar now sits *under* it. These screens
 * are preference lists with no insets handling of their own, so their first row would
 * hide behind the status bar and their last behind the navigation bar.
 *
 * Rather than opt out of edge-to-edge -- a flag Android is phasing out -- pad the
 * content by whatever the system bars actually occupy.
 */
fun Activity.padForSystemBars() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val content = findViewById<View>(android.R.id.content) ?: return
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
