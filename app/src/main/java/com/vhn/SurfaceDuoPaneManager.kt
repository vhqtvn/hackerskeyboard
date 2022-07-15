package com.vhn

import android.content.Context
import com.microsoft.device.layoutmanager.PaneManager
import com.microsoft.device.layoutmanager.PaneManager.PaneState

class SurfaceDuoPaneManager(private val mContext: Context) {
    var paneManager: PaneManager? = null
    fun connect() {
        val pm = paneManager ?: return
        pm.connect(mContext) { connected: Boolean -> }
    }

    fun disconnect() {
        val pm = paneManager ?: return
        pm.disconnect()
    }

    fun overrideKeyboardPane(i: Int) {
        val pm = paneManager ?: return
        pm.overrideKeyboardPane(i)
    }

    fun paneState(): Array<PaneState>? {
        val pm = paneManager ?: return null
        return pm.paneStates
    }

    fun paneStateForKeyboard(): Array<PaneState?>? {
        val pm = paneManager ?: return null
        return pm.paneStatesForKeyboard
    }

    fun ensureInitialized() {
        if (paneManager == null) paneManager = PaneManager()
    }

    fun setOnPaneStateChangeListener() {
        val pm = paneManager ?: return
        pm.setOnPaneStateChangeListener {}
    }
}