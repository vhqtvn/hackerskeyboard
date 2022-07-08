package com.vhn;

import android.content.Context;

import com.microsoft.device.layoutmanager.PaneManager;

public class SurfaceDuoPaneManager {
    public PaneManager paneManager;
    private Context mContext;

    public SurfaceDuoPaneManager(Context ctx) {
        mContext = ctx;
    }

    public void connect() {
        PaneManager pm = paneManager;
        if (pm == null) return;
        pm.connect(mContext, connected -> {
            //TODO
        });
    }

    public void disconnect() {
        PaneManager pm = paneManager;
        if (pm == null) return;
        pm.disconnect();
    }

    public void overrideKeyboardPane(int i) {
        PaneManager pm = paneManager;
        if (pm == null) return;
        pm.overrideKeyboardPane(i);
    }

    public PaneManager.PaneState[] paneState() {
        PaneManager pm = paneManager;
        if(pm == null) return null;
        return pm.getPaneStates();
    }

    public PaneManager.PaneState[] paneStateForKeyboard() {
        PaneManager pm = paneManager;
        if(pm == null) return null;
        return pm.getPaneStatesForKeyboard();
    }

    public void ensureInitialized() {
        if (paneManager == null) paneManager = new PaneManager();
    }

    public void setOnPaneStateChangeListener() {
        PaneManager pm = paneManager;
        if(pm == null) return;
        pm.setOnPaneStateChangeListener(() -> {
            //TODO
        });
    }
}
