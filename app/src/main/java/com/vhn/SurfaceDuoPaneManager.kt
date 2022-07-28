package com.vhn

import android.content.Context
import com.microsoft.device.layoutmanager.PaneManager
import com.microsoft.device.layoutmanager.PaneManager.PaneState

class SurfaceDuoPaneManager(private val mContext: Context) {
    var mConnected: Boolean = false
    var paneManager: PaneManager? = null
    @Suppress("ObjectLiteralToLambda")
    fun connect() {
        val pm = paneManager ?: return
        pm.connect(mContext, object: PaneManager.ServiceConnectionListener {
            override fun onServiceConnectionChanged(p0: Boolean) {
                mConnected = p0
            }
        })
    }

    fun disconnect() {
        val pm = paneManager ?: return
        mConnected = false
        pm.disconnect()
    }

    fun overrideKeyboardPane(i: Int) {
        if (!mConnected) return
        val pm = paneManager ?: return
        pm.overrideKeyboardPane(i)
    }

    fun paneState(): Array<PaneState>? {
        if (!mConnected) return null
        val pm = paneManager ?: return null
        return pm.paneStates
    }

    fun paneStateForKeyboard(): Array<PaneState?>? {
        if (!mConnected) return null
        val pm = paneManager ?: return null
        return pm.paneStatesForKeyboard
    }

    fun ensureInitialized() {
        if (paneManager == null) paneManager = PaneManager()
    }
}