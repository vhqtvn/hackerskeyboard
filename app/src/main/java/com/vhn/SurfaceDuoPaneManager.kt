package com.vhn

import android.content.Context
import android.graphics.Rect
import android.util.Log
import dalvik.system.PathClassLoader
import java.util.*


//import com.microsoft.device.layoutmanager.PaneManager.PaneState

class SurfaceDuoPaneManager(private val mContext: Context) {
    companion object {
        lateinit var PaneManagerClass: Class<*>
        lateinit var PaneManager_PaneState: Class<*>
        lateinit var PaneManager_ServiceConnectionListener: Class<*>

        init {
            try {
                val pathClassLoader = PathClassLoader(
                    "/system/framework/com.microsoft.device-private.jar",
                    ClassLoader.getSystemClassLoader())
                PaneManagerClass = Class.forName("com.microsoft.device.layoutmanager.PaneManager",
                    true,
                    pathClassLoader)
                for (clz in PaneManagerClass.declaredClasses) {
                    when (clz.simpleName) {
                        "PaneState" -> PaneManager_PaneState = clz
                        "ServiceConnectionListener" -> PaneManager_ServiceConnectionListener = clz
                    }
                }
            } catch (e: java.lang.Exception) {
                //maybe another device
            }
        }
    }

    var mConnected: Boolean = false
    var paneManager: Object? = null

    private val CONNECT = PaneManagerClass.getMethod(
        "connect",
        Context::class.java,
        PaneManager_ServiceConnectionListener
    )

    private val LISTENER_PROXY =
        PaneManager_ServiceConnectionListener.cast(java.lang.reflect.Proxy.newProxyInstance(
            PaneManager_ServiceConnectionListener.classLoader,
            arrayOf(PaneManager_ServiceConnectionListener)) { _, _, args ->
            // only one method
            mConnected = args[0] as Boolean
            null
        })

    @Suppress("ObjectLiteralToLambda")
    fun connect() {
        val pm = paneManager ?: return

        CONNECT.invoke(pm, mContext, LISTENER_PROXY)
    }

    private val DISCONNECT = PaneManagerClass.getMethod(
        "disconnect"
    )

    fun disconnect() {
        val pm = paneManager ?: return
        mConnected = false
        DISCONNECT.invoke(pm)
    }

    private val OVERRIDE_KEYBOARD_PANE = PaneManagerClass.getMethod(
        "overrideKeyboardPane",
        Int::class.java
    )

    fun overrideKeyboardPane(i: Int) {
        if (!mConnected) return
        val pm = paneManager ?: return
        OVERRIDE_KEYBOARD_PANE.invoke(pm, i)
    }

    private val GET_PANE_STATES = PaneManagerClass.getMethod("getPaneStates")

    private val PANESTATE_getPaneId = PaneManager_PaneState.getMethod("getPaneId")
    private val PANESTATE_getTaskPane = PaneManager_PaneState.getMethod("getTaskPane")
    private val PANESTATE_isInFocus = PaneManager_PaneState.getMethod("isInFocus")
    private val PANESTATE_isOccupied = PaneManager_PaneState.getMethod("isOccupied")

    fun paneState(): Array<PaneState>? {
        if (!mConnected) return null
        val pm = paneManager ?: return null
        return (GET_PANE_STATES.invoke(pm) as Array<Object>).map {
            PaneState(
                PANESTATE_isInFocus.invoke(it) as Boolean,
                PANESTATE_isOccupied.invoke(it) as Boolean,
                PANESTATE_getPaneId.invoke(it) as Int,
                PANESTATE_getTaskPane.invoke(it) as Rect,
            )
        }.toTypedArray()
    }

    private val GET_PANE_STATES_KB = PaneManagerClass.getMethod("getPaneStatesForKeyboard")
    fun paneStateForKeyboard(): Array<PaneState?>? {
        if (!mConnected) return null
        val pm = paneManager ?: return null
        return (GET_PANE_STATES_KB.invoke(pm) as Array<Object>).map {
            PaneState(
                PANESTATE_isInFocus.invoke(it) as Boolean,
                PANESTATE_isOccupied.invoke(it) as Boolean,
                PANESTATE_getPaneId.invoke(it) as Int,
                PANESTATE_getTaskPane.invoke(it) as Rect,
            )
        }.toTypedArray()
    }

    fun ensureInitialized() {
        if (paneManager == null) {
            paneManager = PaneManagerClass.newInstance() as Object
        };
    }
}

class PaneState(
    val inFocus: Boolean,
    val occupied: Boolean,
    val paneId: Int,
    val taskPane: Rect,
) {
}