package vn.vhn.pckeyboard.thor

import android.content.Context
import android.util.Log

/**
 * One-shot device setup performed via root so the Thor keyboard works standalone
 * (no adb): grant the overlay permission and make this app the selected IME.
 * Idempotent -- safe to call on every service start / boot.
 */
object ThorSetup {
    private const val TAG = "ThorSetup"

    fun ensure(context: Context) {
        val pkg = context.packageName
        val ime = "$pkg/.LatinIME"
        val cmd = buildString {
            append("appops set $pkg SYSTEM_ALERT_WINDOW allow; ")
            append("ime enable $ime; ")
            append("ime set $ime; ")
            // Android 12+ kills "phantom" child processes -- our uinput daemon (launched
            // via su) is one. Disable that monitor so it isn't killed in normal use.
            append("settings put global settings_enable_monitor_phantom_procs false; ")
            append("device_config put activity_manager max_phantom_processes 2147483647")
        }
        runAsRoot(cmd)
        installBootScript(pkg)
    }

    /**
     * Install a Magisk service.d script that starts the deck on boot. More
     * reliable than a manifest BOOT_COMPLETED receiver, which this ROM leaves
     * ungranted (RECEIVE_BOOT_COMPLETED granted=false) and which is blocked while
     * the app is in the stopped state.
     */
    private fun installBootScript(pkg: String) {
        val path = "/data/adb/service.d/thor_keyboard.sh"
        val script = """
            #!/system/bin/sh
            # Start the Thor second-screen keyboard deck on boot.
            while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
            sleep 8
            am start-foreground-service -n $pkg/.thor.OverlayKeyboardService
        """.trimIndent()
        val cmd = "mkdir -p /data/adb/service.d && cat > $path <<'THOREOF'\n$script\nTHOREOF\nchmod 755 $path"
        runAsRoot(cmd)
    }

    private fun runAsRoot(command: String) {
        try {
            // Single-arg command string so su runs it through a shell (`;` works).
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            p.outputStream.close()
            p.waitFor()
            Log.d(TAG, "setup exit=${p.exitValue()} cmd=$command")
        } catch (e: Exception) {
            Log.e(TAG, "root setup failed", e)
        }
    }
}
