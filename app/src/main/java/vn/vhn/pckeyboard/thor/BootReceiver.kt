package vn.vhn.pckeyboard.thor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the Thor overlay keyboard deck automatically on boot so it survives a
 * reboot without any manual/adb step. The service self-configures permissions and
 * the default IME via root on start (see ThorSetup).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            OverlayKeyboardService.start(context)
        }
    }
}
