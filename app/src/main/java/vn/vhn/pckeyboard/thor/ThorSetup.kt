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
        // The app is unavailable until the user's first unlock (file-based encryption) --
        // `am start` returns "Not found" before that -- so retry until the start succeeds
        // (i.e. just after the first unlock), then exit. A single fire-once start would
        // always miss, since service.d runs before unlock.
        val script = """
            #!/system/bin/sh
            # Start the Thor deck on boot, retrying until the app is available (it isn't
            # until the user's first unlock -- FBE). On the first success, immediately make
            # VHKeyboard the IME so the system doesn't linger on the fallback keyboard.
            while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
            i=0
            while [ "${'$'}i" -lt 300 ]; do
              out=${'$'}(am start-foreground-service -n $pkg/.thor.OverlayKeyboardService 2>&1)
              case "${'$'}out" in
                *Error*|*Exception*) : ;;
                *) ime enable $pkg/.LatinIME 2>/dev/null; ime set $pkg/.LatinIME 2>/dev/null; exit 0 ;;
              esac
              i=${'$'}((i + 1))
              sleep 2
            done
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
