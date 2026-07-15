package vn.vhn.pckeyboard.thor

import android.util.Log

/**
 * Event-driven recents: registers a TaskStackListener with the system so we get
 * pushed [onChanged] callbacks whenever the task stack changes -- no polling, no exec.
 *
 * Uses reflection because the APIs are @hide (needs hidden-API unsealed via
 * FreeReflection) and requires MANAGE_ACTIVITY_TASKS (granted by the sig-grant module).
 * The AIDL-generated android.app.ITaskStackListener is shadowed at runtime by the
 * framework's real interface (parent-first loading), so our Stub IS-A framework
 * ITaskStackListener and only implements the first method (onTaskStackChanged); the
 * framework's other (oneway) callbacks land on unimplemented methods and are dropped.
 */
object TaskWatcher {
    private const val TAG = "ThorTaskWatcher"

    /** Invoked (on a binder thread) whenever the task stack changes. */
    @Volatile var onChanged: (() -> Unit)? = null

    @Volatile var active = false
        private set

    private var listener: android.app.ITaskStackListener.Stub? = null

    @Synchronized
    fun register(): Boolean {
        if (listener != null) return true
        return try {
            val l = object : android.app.ITaskStackListener.Stub() {
                override fun onTaskStackChanged() {}   // handled in onTransact; satisfies the compiler
                override fun onTransact(
                    code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int
                ): Boolean {
                    val first = android.os.IBinder.FIRST_CALL_TRANSACTION
                    return when {
                        // onTaskStackChanged is the interface's first method (code = base+0).
                        code == first -> {
                            try { onChanged?.invoke() } catch (_: Throwable) {}
                            true
                        }
                        // Every other (oneway) callback: consume without dispatching, so we
                        // never hit an unimplemented method (which would AbstractMethodError -> crash).
                        code in (first + 1)..(first + 200) -> true
                        else -> super.onTransact(code, data, reply, flags)   // dump/interface codes
                    }
                }
            }
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
            val iface = Class.forName("android.app.ITaskStackListener")
            atm.javaClass.getMethod("registerTaskStackListener", iface).invoke(atm, l)
            listener = l
            active = true
            Log.d(TAG, "TaskStackListener registered")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "registerTaskStackListener failed", e)
            active = false
            false
        }
    }

    @Synchronized
    fun unregister() {
        val l = listener ?: return
        try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
            val iface = Class.forName("android.app.ITaskStackListener")
            atm.javaClass.getMethod("unregisterTaskStackListener", iface).invoke(atm, l)
        } catch (e: Throwable) {
            Log.w(TAG, "unregisterTaskStackListener failed", e)
        }
        listener = null
        active = false
    }
}
