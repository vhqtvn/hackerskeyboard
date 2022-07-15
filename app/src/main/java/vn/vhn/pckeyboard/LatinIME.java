/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package vn.vhn.pckeyboard;

import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.WindowCompat;

import com.lge.ime.util.p118f.DualKeyboardManager;
import com.lge.ime.util.p118f.LGMultiDisplayUtils;
import com.microsoft.device.layoutmanager.PaneManager;
import com.novia.lg_dualscreen_ime.ToggleFullScreenIME;
import com.vhn.SurfaceDuoPaneManager;
import com.vhn.SurfaceDuoUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements
        ComposeSequencing,
        LatinKeyboardBaseView.OnKeyboardActionListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "PCKeyboardIME";
    private static final String NOTIFICATION_CHANNEL_ID = "PCKeyboard";
    private static final int NOTIFICATION_ONGOING_ID = 1001;

    private static final String PREF_VIBRATE_ON = "vibrate_on";
    static final String PREF_VIBRATE_LEN = "vibrate_len";
    private static final String PREF_SOUND_ON = "sound_on";
    private static final String PREF_POPUP_ON = "popup_on";
    private static final String PREF_AUTO_CAP = "auto_cap";
    private static final String PREF_VOICE_MODE = "voice_mode";

    public static final String PREF_SELECTED_LANGUAGES = "selected_languages";
    public static final String PREF_INPUT_LANGUAGE = "input_language";
    static final String PREF_FULLSCREEN_OVERRIDE = "fullscreen_override";
    static final String PREF_FORCE_KEYBOARD_ON = "force_keyboard_on";
    static final String PREF_KEYBOARD_NOTIFICATION = "keyboard_notification";
    static final String PREF_HEIGHT_PORTRAIT = "settings_height_portrait";
    static final String PREF_HEIGHT_LANDSCAPE = "settings_height_landscape";
    static final String PREF_HINT_MODE = "pref_hint_mode";
    static final String PREF_LONGPRESS_TIMEOUT = "pref_long_press_duration";
    static final String PREF_RENDER_MODE = "pref_render_mode";
    static final String PREF_SWIPE_UP = "pref_swipe_up";
    static final String PREF_SWIPE_DOWN = "pref_swipe_down";
    static final String PREF_SWIPE_LEFT = "pref_swipe_left";
    static final String PREF_SWIPE_RIGHT = "pref_swipe_right";
    static final String PREF_VOL_UP = "pref_vol_up";
    static final String PREF_VOL_DOWN = "pref_vol_down";

    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200;

    static final int ASCII_ENTER = '\n';
    static final int ASCII_SPACE = ' ';

    // Contextual menu positions
    private static final int POS_METHOD = 0;
    private static final int POS_SETTINGS = 1;

    // private LatinKeyboardView mInputView;
    private ScreenOrientationLocker orientationLocker;

    private AlertDialog mOptionsDialog;

    /* package */ KeyboardSwitcher mKeyboardSwitcher;

    private Resources mResources;

    private String mSystemLocale;
    private LanguageSwitcher mLanguageSwitcher;

    final private StringBuilder mComposing = new StringBuilder();
    // TODO move this state variable outside LatinIME
    private boolean mModShiftLeft;
    private boolean mModCtrlLeft;
    private boolean mModAltLeft;
    private boolean mModMetaLeft;
    private boolean mModShiftRight;
    private boolean mModCtrlRight;
    private boolean mModAltRight;
    private boolean mModMetaRight;
    private boolean mModFn;
    private boolean mModFn1;
    private boolean mModFn2;
    private boolean mVibrateOn;
    private int mVibrateLen;
    private boolean mSoundOn;
    private boolean mPopupOn;
    private boolean mAutoCapPref;
    private boolean mDeadKeysActive;
    private boolean mFullscreenOverride;
    private boolean mForceKeyboardOn;
    private boolean mStick = false;
    private boolean mKeyboardNotification;
    private String mSwipeUpAction;
    private String mSwipeDownAction;
    private String mSwipeLeftAction;
    private String mSwipeRightAction;
    private String mVolUpAction;
    private String mVolDownAction;

    public static final GlobalKeyboardSettings sKeyboardSettings = new GlobalKeyboardSettings();
    static LatinIME sInstance;

    private int mHeightPortrait;
    private int mHeightLandscape;
    private int mNumKeyboardModes = 3;
    private int mKeyboardModeOverridePortrait;
    private int mKeyboardModeOverrideLandscape;
    private int mOrientation = -1337;

    private int mDeleteCount;
    private long mLastKeyTime;

    private static boolean mDualEnabled = false;

    public static boolean isDualEnabled() {
        return mDualEnabled;
    }

    // Modifier keys state
    class BinaryModifierKeystate {
        ModifierKeyState left = new ModifierKeyState();
        ModifierKeyState right = new ModifierKeyState();
    }

    private BinaryModifierKeystate mShiftKeyState = new BinaryModifierKeystate();
    private BinaryModifierKeystate mCtrlKeyState = new BinaryModifierKeystate();
    private BinaryModifierKeystate mAltKeyState = new BinaryModifierKeystate();
    private BinaryModifierKeystate mMetaKeyState = new BinaryModifierKeystate();

    private ModifierKeyState mSymbolKeyState = new ModifierKeyState();
    private ModifierKeyState mFnKeyState = new ModifierKeyState();
    private ModifierKeyState mFn1KeyState = new ModifierKeyState();
    private ModifierKeyState mFn2KeyState = new ModifierKeyState();

    // Compose sequence handling
    private boolean mComposeMode = false;
    private ComposeSequence mComposeBuffer = new ComposeSequence(this);
    private ComposeSequence mDeadAccentBuffer = new DeadAccentSequence(this);

    private AudioManager mAudioManager;
    // Align sound effect volume on music volume
    private final float FX_VOLUME = -1.0f;
    private final float FX_VOLUME_RANGE_DB = 72.0f;
    private boolean mSilentMode;

    /* package */ String mWordSeparators;
    private String mSentenceSeparators;
    private boolean mConfigurationChanging;

    // Keeps track of most recently inserted text (multi-character key) for
    // reverting
    private CharSequence mEnteredText;
    private boolean mRefreshKeyboardRequired;

    private NotificationReceiver mNotificationReceiver;

    public Handler mDefaultHandler = new Handler();

    private SurfaceDuoPaneManager surfaceDuoPaneManager;

    public SurfaceDuoPaneManager getSurfaceDuoPaneManager() {
        return surfaceDuoPaneManager;
    }

    @Override
    public void onCreate() {
        Log.i("PCKeyboard", "onCreate(), os.version=" + System.getProperty("os.version"));
        KeyboardSwitcher.init(this);
        super.onCreate();
        WindowCompat.setDecorFitsSystemWindows(getWindow().getWindow(), false);
        if (SurfaceDuoUtils.isDeviceSurfaceDuo(getPackageManager())) {
            surfaceDuoPaneManager = new SurfaceDuoPaneManager(getApplicationContext());
            surfaceDuoPaneManager.ensureInitialized();
            surfaceDuoPaneManager.connect();
        }
        if (LGMultiDisplayUtils.supportDualScreen()) {
            mDualEnabled = LGMultiDisplayUtils.checkForceLandscape(this);
        } else {
            mDualEnabled = false;
        }
        sInstance = this;
        // setStatusIcon(R.drawable.ime_qwerty);
        mResources = getResources();
        final Configuration conf = mResources.getConfiguration();
        boolean orientationUpdated = updateOrientation(conf);
        if (LGMultiDisplayUtils.supportDualScreen()) {
            orientationLocker = new ScreenOrientationLocker(getApplicationContext());
        }
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        mLanguageSwitcher = new LanguageSwitcher(this);
        mLanguageSwitcher.loadLocales(prefs);
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher);
        mSystemLocale = conf.locale.toString();
        mLanguageSwitcher.setSystemLocale(conf.locale);
        String inputLanguage = mLanguageSwitcher.getInputLanguage();
        if (inputLanguage == null) {
            inputLanguage = conf.locale.toString();
        }
        Resources res = getResources();
        mFullscreenOverride = prefs.getBoolean(PREF_FULLSCREEN_OVERRIDE,
                res.getBoolean(R.bool.default_fullscreen_override));
        mForceKeyboardOn = prefs.getBoolean(PREF_FORCE_KEYBOARD_ON,
                res.getBoolean(R.bool.default_force_keyboard_on));
        mKeyboardNotification = prefs.getBoolean(PREF_KEYBOARD_NOTIFICATION,
                res.getBoolean(R.bool.default_keyboard_notification));
        mHeightPortrait = getHeight(prefs, PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait));
        mHeightLandscape = getHeight(prefs, PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape));
        LatinIME.sKeyboardSettings.hintMode = Integer.parseInt(prefs.getString(PREF_HINT_MODE, res.getString(R.string.default_hint_mode)));
        LatinIME.sKeyboardSettings.longpressTimeout = getPrefInt(prefs, PREF_LONGPRESS_TIMEOUT, res.getString(R.string.default_long_press_duration));
        LatinIME.sKeyboardSettings.renderMode = getPrefInt(prefs, PREF_RENDER_MODE, res.getString(R.string.default_render_mode));
        mSwipeUpAction = prefs.getString(PREF_SWIPE_UP, res.getString(R.string.default_swipe_up));
        mSwipeDownAction = prefs.getString(PREF_SWIPE_DOWN, res.getString(R.string.default_swipe_down));
        mSwipeLeftAction = prefs.getString(PREF_SWIPE_LEFT, res.getString(R.string.default_swipe_left));
        mSwipeRightAction = prefs.getString(PREF_SWIPE_RIGHT, res.getString(R.string.default_swipe_right));
        mVolUpAction = prefs.getString(PREF_VOL_UP, res.getString(R.string.default_vol_up));
        mVolDownAction = prefs.getString(PREF_VOL_DOWN, res.getString(R.string.default_vol_down));
        sKeyboardSettings.initPrefs(prefs, res);

        updateKeyboardOptions();
        if (LGMultiDisplayUtils.supportDualScreen()) {
            if (updateOrientation(conf)) orientationUpdated = true;
        }

        if (orientationUpdated) reloadKeyboards();

        LatinIMEUtil.GCUtils.getInstance().reset();
        // register to receive ringer mode changes for silent mode
        IntentFilter filter = new IntentFilter(
                AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);
        prefs.registerOnSharedPreferenceChangeListener(this);
        setNotification(mKeyboardNotification);

        if (LGMultiDisplayUtils.supportDualScreen()) {
            DualKeyboardManager.setContext(this).setInputMethodService(this);

            (new Handler()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    reloadKeyboards();
                }
            }, 50);
        } else {
            mOrientation = conf.orientation;
        }
    }

    static int mLastDisplayId = -1337;

    private boolean updateOrientation(Configuration conf) {
        if (!LGMultiDisplayUtils.supportDualScreen()) {
            if (conf.orientation != mOrientation) {
                mOrientation = conf.orientation;
                return true;
            }
            return false;
        }
        int newOrientation = mOrientation;
        if (mOrientation == -1337) {
            newOrientation = mDualEnabled ? Configuration.ORIENTATION_LANDSCAPE : conf.orientation;
        }
        final Window current_window = getWindow().getWindow();
        //Only update when the binder is alive
        if (current_window != null
                && current_window.getAttributes() != null
                && current_window.getAttributes().token != null
                && current_window.getAttributes().token.isBinderAlive()) {
            final Display current_display = current_window.getWindowManager().getDefaultDisplay();
            if (current_display.getDisplayId() != mLastDisplayId) {
//                mDualEnabled = false;
                mLastDisplayId = current_display.getDisplayId();
            }
//            orientationLocker.saveCurrentWindowManager(current_window.getWindowManager());
            boolean isLandscape = current_display.getRotation() == Surface.ROTATION_90 || current_display.getRotation() == Surface.ROTATION_270;
            if (mDualEnabled) isLandscape = true;
            Log.i(TAG, "Current window: " + current_display.getName() + "; dualEnabled: " + mDualEnabled + "; isLandScape: " + isLandscape);
            newOrientation = isLandscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        }
        boolean result = newOrientation != mOrientation;
        Log.i(TAG, "New orientation: " + newOrientation);
        mOrientation = newOrientation;
        return result;
    }

    private int getKeyboardModeNum(int origMode, int override) {
        if (mNumKeyboardModes == 2 && origMode == 2) origMode = 1; // skip "compact". FIXME!
        int num = (origMode + override) % mNumKeyboardModes;
        if (mNumKeyboardModes == 2 && num == 1) num = 2; // skip "compact". FIXME!
        return num;
    }

    boolean updateKeyboardOptionsRecursive = false;

    private void updateKeyboardOptions() {
        if (!LGMultiDisplayUtils.supportDualScreen()) {
            boolean isPortrait = isPortrait();
            int kbMode;
            mNumKeyboardModes = 2;
            if (isPortrait) {
                kbMode = getKeyboardModeNum(sKeyboardSettings.keyboardModePortrait, mKeyboardModeOverridePortrait);
            } else {
                kbMode = getKeyboardModeNum(sKeyboardSettings.keyboardModeLandscape, mKeyboardModeOverrideLandscape);
            }
            // Convert overall keyboard height to per-row percentage
            int screenHeightPercent = isPortrait ? mHeightPortrait : mHeightLandscape;
            LatinIME.sKeyboardSettings.keyboardMode = kbMode;
            LatinIME.sKeyboardSettings.keyboardHeightPercent = (float) screenHeightPercent;
            return;
        }
        if (updateKeyboardOptionsRecursive) return;
        updateKeyboardOptionsRecursive = true;
        try {
            boolean isPortrait = isPortrait();
            int kbMode;
            mNumKeyboardModes = 2;
            if (isPortrait) {
                kbMode = getKeyboardModeNum(sKeyboardSettings.keyboardModePortrait, mKeyboardModeOverridePortrait);
            } else {
                kbMode = getKeyboardModeNum(sKeyboardSettings.keyboardModeLandscape, mKeyboardModeOverrideLandscape);
            }
            // Convert overall keyboard height to per-row percentage
            Log.i(TAG, "updateKeyboardOptions: mDualEnabled=" + mDualEnabled);
            if (!mDualEnabled && LGMultiDisplayUtils.checkForceLandscape(this))
                setDualDisplay(false);
            float screenHeightPercent = mDualEnabled ? 96.25f : (isPortrait ? mHeightPortrait : mHeightLandscape);
            LatinIME.sKeyboardSettings.keyboardMode = kbMode;
            if (
                    LatinIME.sKeyboardSettings.keyboardMode != kbMode
                            || LatinIME.sKeyboardSettings.keyboardHeightPercent != screenHeightPercent
            ) {
                LatinIME.sKeyboardSettings.keyboardMode = kbMode;
                LatinIME.sKeyboardSettings.keyboardHeightPercent = screenHeightPercent;
                reloadKeyboards();
            }
        } finally {
            updateKeyboardOptionsRecursive = false;
        }
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.notification_channel_name);
            String description = getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void setNotification(boolean visible) {
        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(ns);

        if (visible && mNotificationReceiver == null) {
            createNotificationChannel();
            int icon = R.drawable.icon;
            CharSequence text = "Keyboard notification enabled.";
            long when = System.currentTimeMillis();

            // TODO: clean this up?
            mNotificationReceiver = new NotificationReceiver(this);
            final IntentFilter pFilter = new IntentFilter(NotificationReceiver.ACTION_SHOW);
            pFilter.addAction(NotificationReceiver.ACTION_SETTINGS);
            registerReceiver(mNotificationReceiver, pFilter);

            Intent notificationIntent = new Intent(NotificationReceiver.ACTION_SHOW);
            PendingIntent contentIntent = PendingIntent.getBroadcast(getApplicationContext(), 1, notificationIntent, 0);
            //PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

            Intent configIntent = new Intent(NotificationReceiver.ACTION_SETTINGS);
            PendingIntent configPendingIntent =
                    PendingIntent.getBroadcast(getApplicationContext(), 2, configIntent, 0);

            String title = "Show Hacker's Keyboard";
            String body = "Select this to open the keyboard. Disable in settings.";

            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.icon_hk_notification)
                    .setColor(0xff220044)
                    .setAutoCancel(false) //Make this notification automatically dismissed when the user touches it -> false.
                    .setTicker(text)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .addAction(R.drawable.icon_hk_notification, getString(R.string.notification_action_settings),
                            configPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            /*
            Notification notification = new Notification.Builder(getApplicationContext())
                    .setAutoCancel(false) //Make this notification automatically dismissed when the user touches it -> false.
                    .setTicker(text)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setWhen(when)
                    .setSmallIcon(icon)
                    .setContentIntent(contentIntent)
                    .getNotification();
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
            mNotificationManager.notify(ID, notification);
            */

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);

            // notificationId is a unique int for each notification that you must define
            notificationManager.notify(NOTIFICATION_ONGOING_ID, mBuilder.build());

        } else if (mNotificationReceiver != null) {
            mNotificationManager.cancel(NOTIFICATION_ONGOING_ID);
            unregisterReceiver(mNotificationReceiver);
            mNotificationReceiver = null;
        }
    }

    private boolean isPortrait() {
        return (mOrientation == Configuration.ORIENTATION_PORTRAIT);
    }

    @Override
    public void onDestroy() {
        if (surfaceDuoPaneManager != null) {
            surfaceDuoPaneManager.disconnect();
            surfaceDuoPaneManager = null;
        }
        unregisterReceiver(mReceiver);
        if (mNotificationReceiver != null) {
            unregisterReceiver(mNotificationReceiver);
            mNotificationReceiver = null;
        }

        if (LGMultiDisplayUtils.supportDualScreen()) {
            orientationLocker.unlock();
        }
        super.onDestroy();
        if (LGMultiDisplayUtils.supportDualScreen()) {
            synchronized (glocker) {
                inputMethodAttachCnt -= selfInputMethodAttachCnt;
                selfInputMethodAttachCnt = 0;
            }
        }
    }

    static Configuration mLastConfiguration = null;
    static int mConfigurationPostId = 1;

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        if (!LGMultiDisplayUtils.supportDualScreen()) {
            handleConfigurationChanged(conf);
            return;
        }
        if (mLastConfiguration == null) mLastConfiguration = conf;
        Log.i("PCKeyboard", "onConfigurationChanged() diff=" + mLastConfiguration.diff(conf));
        if (mLastConfiguration.diff(conf) == 0) {
            final int currentId = ++mConfigurationPostId;
            mDefaultHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (currentId != mConfigurationPostId) return;
                    handleConfigurationChanged(conf);
                }
            }, 50);
        } else {
            handleConfigurationChanged(conf);
        }
        mConfigurationChanging = true;
        super.onConfigurationChanged(conf);
        mConfigurationChanging = false;
    }

    void handleConfigurationChanged(Configuration conf) {
        Log.i("PCKeyboard", "onConfigurationChanged()");
        // If the system locale changes and is different from the saved
        // locale (mSystemLocale), then reload the input locale list from the
        // latin ime settings (shared prefs) and reset the input locale
        // to the first one.
        final String systemLocale = conf.locale.toString();
        if (!TextUtils.equals(systemLocale, mSystemLocale)) {
            mSystemLocale = systemLocale;
            if (mLanguageSwitcher != null) {
                mLanguageSwitcher.loadLocales(PreferenceManager
                        .getDefaultSharedPreferences(this));
                mLanguageSwitcher.setSystemLocale(conf.locale);
                toggleLanguage(true, true);
            } else {
                reloadKeyboards();
            }
        }
        // If orientation changed while predicting, commit the change
        if (updateOrientation(conf)) {
            InputConnection ic = getCurrentInputConnection();
            commitTyped(ic, true);
            if (ic != null)
                ic.finishComposingText(); // For voice input
            if (!LGMultiDisplayUtils.supportDualScreen())
                mOrientation = conf.orientation;
            reloadKeyboards();
            Log.i(TAG, "New orientation: reload");
        }
        mConfigurationChanging = true;
        super.onConfigurationChanged(conf);
        mConfigurationChanging = false;
    }


    @Override
    public View onCreateInputView() {
        setCandidatesViewShown(false);  // Workaround for "already has a parent" when reconfiguring
        mKeyboardSwitcher.recreateInputView();
        mKeyboardSwitcher.makeKeyboards(true);
        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, 0, false);
        return mKeyboardSwitcher.getRootView();
    }

    @Override
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new MyInputMethodImpl();
    }

    IBinder mToken;
    int selfInputMethodAttachCnt = 0;
    static int inputMethodAttachCnt = 0;
    static boolean lastActionIsShow = false;
    static Object glocker = new Object();

    public class MyInputMethodImpl extends InputMethodImpl {
        private boolean isShown = false;

        @Override
        public void attachToken(IBinder token) {
            super.attachToken(token);
            Log.i(TAG, "attachToken " + token);
            if (mToken == null) {
                mToken = token;
            }
        }

        long showTimeMS = 0;
        int rotationOnShow = -999;


        @Override
        public void bindInput(InputBinding binding) {
            super.bindInput(binding);
            if (LGMultiDisplayUtils.supportDualScreen()) {
                int currRotation = getWindow().getWindow().getWindowManager().getDefaultDisplay().getRotation();
                rotationOnShow = currRotation;
                synchronized (glocker) {
                    ++inputMethodAttachCnt;
                    ++selfInputMethodAttachCnt;
                }
                Log.i(TAG, this.hashCode() + " (" + inputMethodAttachCnt + ") " + "bindInput");
                showTimeMS = System.currentTimeMillis();
                if (inputMethodAttachCnt == 1) {
                    if (LatinIME.mDualEnabled && !isShown) LatinIME.this.setDualDisplay(true);
                    isShown = true;
                }
            }
        }

        @Override
        public void unbindInput() {
            if (!LGMultiDisplayUtils.supportDualScreen()) {
                super.unbindInput();
                return;
            }
            int currRotation = getWindow().getWindow().getWindowManager().getDefaultDisplay().getRotation();
            int thisAttachCnt = 0;
            synchronized (glocker) {
                thisAttachCnt = --inputMethodAttachCnt;
                --selfInputMethodAttachCnt;
            }
            super.unbindInput();
            if (!mDualEnabled) return;
            int finalThisAttachCnt = thisAttachCnt;
            (new Handler()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, this.hashCode() + " (" + finalThisAttachCnt + ") " + "unbindInput: " + LatinIME.mDualEnabled + ":" + currRotation + " vs " + rotationOnShow);
                    if (!mDualEnabled) return;
                    boolean stillNeedKeyboard = finalThisAttachCnt > 0;
                    if (finalThisAttachCnt == 0) {
                        if (!(
                                mDualEnabled
                                        && (System.currentTimeMillis() <= showTimeMS + 500 || System.currentTimeMillis() <= lastRequestDualMilli + 1000)
                                        && rotationOnShow != currRotation
                                        && rotationOnShow == 0)) {
                            if (isShown) LatinIME.this.setDualDisplay(false);
                            isShown = false;
                        } else {
                            stillNeedKeyboard = true;
                        }
                    } else {

                    }
                    if (stillNeedKeyboard) {
                        (new Handler()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "show keyboard again");
                                orientationLocker.lock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                                orientationLocker.showKeyboard();
                                if (!LGMultiDisplayUtils.checkForceLandscape(LatinIME.this))
                                    setDualDisplay(true);
                            }
                        }, 100);
                    }
                }
            }, 200);
        }

        @Override
        public void restartInput(InputConnection ic, EditorInfo attribute) {
            super.restartInput(ic, attribute);
//            Log.i(TAG, this.hashCode() + " (" + inputMethodAttachCnt + ") " + "restartInput");
        }

        @Override
        public void startInput(InputConnection ic, EditorInfo attribute) {
            super.startInput(ic, attribute);
            if (!LGMultiDisplayUtils.supportDualScreen()) return;
            Log.i(TAG, this.hashCode() + " (" + inputMethodAttachCnt + ") " + "startInput");
            if (mDualEnabled && inputMethodAttachCnt > 0 && !lastActionIsShow) {
                (new Handler()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "show keyboard again");
                        orientationLocker.lock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                        orientationLocker.showKeyboard();
                    }
                }, 100);
            }
        }

        @Override
        public void showSoftInput(int flags, ResultReceiver resultReceiver) {
            super.showSoftInput(flags, resultReceiver);
            if (!LGMultiDisplayUtils.supportDualScreen()) return;
            Log.i(TAG, this.hashCode() + " (" + inputMethodAttachCnt + ") " + "showSoftInput");
            if (lastActionIsShow) {
                LatinIME.this.reloadKeyboards();
                (new Handler()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        LatinIME.this.reloadKeyboards();
                    }
                }, 250);
            }
            lastActionIsShow = true;
        }

        @Override
        public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
            if (mStick) return;
            super.hideSoftInput(flags, resultReceiver);
            if (!LGMultiDisplayUtils.supportDualScreen()) return;
            Log.i(TAG, this.hashCode() + " (" + inputMethodAttachCnt + ") " + "hideSoftInput");
            lastActionIsShow = false;
        }
    }

    @Override
    public void onBindInput() {
        super.onBindInput();
    }

    void updateSurfaceDuoKeyboardPanePosition() {
        PaneManager.PaneState[] states = surfaceDuoPaneManager.paneStateForKeyboard();
        if (states.length >= 2) {
            boolean targetState = isPortrait() ? false : true;
            for (PaneManager.PaneState state : surfaceDuoPaneManager.paneStateForKeyboard()) {
                if (state.isInFocus() == targetState) {
                    try {
                        if (isPortrait())
                            surfaceDuoPaneManager.overrideKeyboardPane(state.getPaneId() | 3);
                    } catch (Exception e) {
                    }
                    try {
                        surfaceDuoPaneManager.overrideKeyboardPane(state.getPaneId());
                    } catch (Exception e) {
                    }
                }
            }
        } else {
            try {
                surfaceDuoPaneManager.overrideKeyboardPane(states[0].getPaneId());
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        if (SurfaceDuoUtils.isDeviceSurfaceDuo(getPackageManager())) {
            updateSurfaceDuoKeyboardPanePosition();
            mDefaultHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateSurfaceDuoKeyboardPanePosition();
                }
            }, 50);
        }
        sKeyboardSettings.editorPackageName = attribute.packageName;
        sKeyboardSettings.editorFieldName = attribute.fieldName;
        sKeyboardSettings.editorFieldId = attribute.fieldId;
        sKeyboardSettings.editorInputType = attribute.inputType;

        //Log.i("PCKeyboard", "onStartInputView " + attribute + ", inputType= " + Integer.toHexString(attribute.inputType) + ", restarting=" + restarting);
        LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
        // In landscape mode, this method gets called without the input view
        // being created.
        if (inputView == null) {
            return;
        }

        if (mRefreshKeyboardRequired) {
            mRefreshKeyboardRequired = false;
            toggleLanguage(true, true);
        }

        mKeyboardSwitcher.makeKeyboards(false);

        // Most such things we decide below in the switch statement, but we need to know
        // now whether this is a password text field, because we need to know now (before
        // the switch statement) whether we want to enable the voice button.
        int variation = attribute.inputType & EditorInfo.TYPE_MASK_VARIATION;
        if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == 0xe0 /* EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD */
        ) {
            if ((attribute.inputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT) {
//                mPasswordText = true;
            }
        }

        mModShiftLeft = false;
        mModCtrlLeft = false;
        mModAltLeft = false;
        mModMetaLeft = false;

        mModShiftRight = false;
        mModCtrlRight = false;
        mModAltRight = false;
        mModMetaRight = false;

        mModFn = false;
        mModFn1 = false;
        mModFn2 = false;
        mEnteredText = null;

        mKeyboardModeOverridePortrait = 0;
        mKeyboardModeOverrideLandscape = 0;
        sKeyboardSettings.useExtension = false;

        switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
            case EditorInfo.TYPE_CLASS_NUMBER:
            case EditorInfo.TYPE_CLASS_DATETIME:
                // fall through
                // NOTE: For now, we use the phone keyboard for NUMBER and DATETIME
                // until we get
                // a dedicated number entry keypad.
                // TODO: Use a dedicated number entry keypad here when we get one.
            case EditorInfo.TYPE_CLASS_PHONE:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_PHONE,
                        attribute.imeOptions, false);
                break;
            case EditorInfo.TYPE_CLASS_TEXT:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                        attribute.imeOptions, false);
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL,
                            attribute.imeOptions, false);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL,
                            attribute.imeOptions, false);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM,
                            attribute.imeOptions, false);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_WEB,
                            attribute.imeOptions, false);
                }
                break;
            default:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                        attribute.imeOptions, false);
        }
        inputView.closing();
        loadSettings();
        updateShiftKeyState(attribute);

        inputView.setPreviewEnabled(mPopupOn);
        inputView.setProximityCorrectionEnabled(true);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();

        if (mKeyboardSwitcher.getInputView() != null) {
            mKeyboardSwitcher.getInputView().closing();
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        // Remove penging messages related to update suggestions
    }

    @Override
    public void onUpdateExtractedText(int token, ExtractedText text) {
        super.onUpdateExtractedText(token, text);
        InputConnection ic = getCurrentInputConnection();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd, int candidatesStart,
                                  int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);

    }

    /**
     * This is called when the user has clicked on the extracted text view, when
     * running in fullscreen mode. The default implementation hides the
     * candidates view when this happens, but only if the extracted text editor
     * has a vertical scroll bar because its text doesn't fit. Here we override
     * the behavior due to the possibility that a re-correction could cause the
     * candidate strip to disappear and re-appear.
     */
    @Override
    public void onExtractedTextClicked() {
        super.onExtractedTextClicked();
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode. The default
     * implementation hides the candidates view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit. Here we override the behavior due to the
     * possibility that a re-correction could cause the candidate strip to
     * disappear and re-appear.
     */
    @Override
    public void onExtractedCursorMovement(int dx, int dy) {
        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void onInitializeInterface() {
        super.onInitializeInterface();
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] completions) {
    }

    @Override
    public void onFinishCandidatesView(boolean finishingInput) {
        //Log.i(TAG, "onFinishCandidatesView(), mCandidateViewContainer=" + mCandidateViewContainer);
        super.onFinishCandidatesView(finishingInput);
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        boolean parent = super.onEvaluateInputViewShown();
        return mForceKeyboardOn || mStick || parent;
    }

    @Override
    public void setCandidatesViewShown(boolean shown) {
        super.setCandidatesViewShown(shown);
    }

    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float displayHeight = dm.heightPixels;
        // If the display is more than X inches high, don't go to fullscreen
        // mode
        float dimen = getResources().getDimension(
                R.dimen.max_height_for_fullscreen);
        if (displayHeight > dimen || mFullscreenOverride) {
            return false;
        } else {
            return super.onEvaluateFullscreenMode();
        }
    }

    public boolean isKeyboardVisible() {
        return (mKeyboardSwitcher != null
                && mKeyboardSwitcher.getInputView() != null
                && mKeyboardSwitcher.getInputView().isShown());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_1) {
//            if (event.getRepeatCount() > 0) return true;
//            keyCode = KeyEvent.KEYCODE_META_LEFT;
//            event = changeEventKeyCode(event, keyCode);
//        }
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_2) keyCode = KeyEvent.KEYCODE_META_RIGHT;
//        if(keyCode == KeyEvent.KEYCODE_DPAD_CENTER) return false;
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_5) {
//            if (event.getRepeatCount() > 0) return true;
//            keyCode = KeyEvent.KEYCODE_CTRL_LEFT;
//            event = changeEventKeyCode(event, keyCode);
//            super.onKeyDown(keyCode, event);
//            return true;
//        }
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_6) keyCode = KeyEvent.KEYCODE_CTRL_RIGHT;
//        Log.d("VHDebug", "Down Mod" + keyCode + "; " + event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0
                        && mKeyboardSwitcher.getInputView() != null) {
                    if (mKeyboardSwitcher.getInputView().handleBack()) {
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (!mVolUpAction.equals("none") && isKeyboardVisible()) {
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (!mVolDownAction.equals("none") && isKeyboardVisible()) {
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_1 || keyCode == KeyEvent.KEYCODE_CLEAR) {
//            if (event.getRepeatCount() > 0) return true;
//            keyCode = KeyEvent.KEYCODE_META_LEFT;
//        }
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_2) keyCode = KeyEvent.KEYCODE_META_RIGHT;
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_5) {
//            if (event.getRepeatCount() > 0) return true;
//            keyCode = KeyEvent.KEYCODE_CTRL_LEFT;
//            event = changeEventKeyCode(event, keyCode);
//            super.onKeyUp(keyCode, event);
//            return true;
//        }
//        if (keyCode == KeyEvent.KEYCODE_NUMPAD_6) keyCode = KeyEvent.KEYCODE_CTRL_RIGHT;
//        Log.d("VHDebug", "Up Mod" + keyCode + "; " + event);
        switch (keyCode) {
//            case KeyEvent.KEYCODE_DPAD_DOWN:
//            case KeyEvent.KEYCODE_DPAD_UP:
//            case KeyEvent.KEYCODE_DPAD_LEFT:
//            case KeyEvent.KEYCODE_DPAD_RIGHT:
//                LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
//                // Enable shift key and DPAD to do selections
//                if (inputView != null && inputView.isShown()
//                        && inputView.isShift()) {
//                    event = new KeyEvent(event.getDownTime(), event.getEventTime(),
//                            event.getAction(), event.getKeyCode(), event
//                            .getRepeatCount(), event.getDeviceId(), event
//                            .getScanCode(), KeyEvent.META_SHIFT_LEFT_ON
//                            | KeyEvent.META_SHIFT_ON);
//                    InputConnection ic = getCurrentInputConnection();
//                    if (ic != null)
//                        ic.sendKeyEvent(event);
//                    return true;
//                }
//                break;
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (!mVolUpAction.equals("none") && isKeyboardVisible()) {
                    return doSwipeAction(mVolUpAction);
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (!mVolDownAction.equals("none") && isKeyboardVisible()) {
                    return doSwipeAction(mVolDownAction);
                }
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void reloadKeyboards() {
        mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher);
        if (mKeyboardSwitcher.getInputView() != null
                && mKeyboardSwitcher.getKeyboardMode() != KeyboardSwitcher.MODE_NONE) {
            mKeyboardSwitcher.setVoiceMode(false, false);
        }
        updateKeyboardOptions();
        mKeyboardSwitcher.makeKeyboards(true);
    }

    private void commitTyped(InputConnection inputConnection, boolean manual) {
    }

    public void updateShiftKeyState(EditorInfo attr) {
//        InputConnection ic = getCurrentInputConnection();
//        if (ic != null) {
//            ic.clearMetaKeyStates(KeyEvent.META_FUNCTION_ON
////                    | KeyEvent.META_SHIFT_MASK
//                    | KeyEvent.META_ALT_MASK
//                    | KeyEvent.META_CTRL_MASK
//                    | KeyEvent.META_META_MASK
//                    | KeyEvent.META_SYM_ON);
//        }
    }

    private void showInputMethodPicker() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showInputMethodPicker();
    }

    private void onOptionKeyPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Input method selector is available as a button in the soft key area, so just launch
            // HK settings directly. This also works around the alert dialog being clipped
            // in Android O.
            Intent intent = new Intent(this, LatinIMESettings.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            // Show an options menu with choices to change input method or open HK settings.
            if (!isShowingOptionDialog()) {
                showOptionsMenu();
            }
        }
    }

    private void onOptionKeyLongPressed() {
        if (!isShowingOptionDialog()) {
            showInputMethodPicker();
        }
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    private int getMetaState() {
        int meta = 0;
        if (mModShiftLeft) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mModShiftRight) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
        if (mModCtrlLeft) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (mModAltLeft) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if (mModMetaLeft) meta |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        if (mModCtrlRight) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
        if (mModAltRight) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
        if (mModMetaRight) meta |= KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
        return meta;
    }

    private void sendKeyDown(InputConnection ic, int key, int meta) {
        long now = System.currentTimeMillis();
        if (ic != null) ic.sendKeyEvent(new KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, key, 0, meta));
    }

    private void sendKeyUp(InputConnection ic, int key, int meta) {
        long now = System.currentTimeMillis();
        if (ic != null) ic.sendKeyEvent(new KeyEvent(
                now, now, KeyEvent.ACTION_UP, key, 0, meta));
    }

    private void sendModifiedKeyDownUp(int key) {
        InputConnection ic = getCurrentInputConnection();
        int meta = getMetaState();
        sendModifierKeysDown();
        sendKeyDown(ic, key, meta);
        sendKeyUp(ic, key, meta);
        sendModifierKeysUp();
    }

    private void sendShiftKey(InputConnection ic, boolean left, boolean isDown) {
        int key = left ? KeyEvent.KEYCODE_SHIFT_LEFT : KeyEvent.KEYCODE_SHIFT_RIGHT;
        int meta = KeyEvent.META_SHIFT_ON | (
                left ? KeyEvent.META_SHIFT_LEFT_ON
                        : KeyEvent.META_SHIFT_RIGHT_ON
        );
        if (isDown) {
            sendKeyDown(ic, key, meta);
        } else {
            sendKeyUp(ic, key, meta);
        }
    }

    private void sendCtrlKey(InputConnection ic, boolean left, boolean isDown, boolean chording) {
        int key = left ? KeyEvent.KEYCODE_CTRL_LEFT : KeyEvent.KEYCODE_CTRL_RIGHT;
        int meta = KeyEvent.META_CTRL_ON | (left ? KeyEvent.META_CTRL_LEFT_ON : KeyEvent.META_CTRL_RIGHT_ON);
        if (isDown) {
            sendKeyDown(ic, key, meta);
        } else {
            sendKeyUp(ic, key, meta);
        }
    }

    private void sendAltKey(InputConnection ic, boolean left, boolean isDown, boolean chording) {
        int key = left ? KeyEvent.KEYCODE_ALT_LEFT : KeyEvent.KEYCODE_ALT_RIGHT;
        int meta = KeyEvent.META_ALT_ON | (left ? KeyEvent.META_ALT_LEFT_ON : KeyEvent.META_ALT_RIGHT_ON);
        if (isDown) {
            sendKeyDown(ic, key, meta);
        } else {
            sendKeyUp(ic, key, meta);
        }
    }

    private void sendMetaKey(InputConnection ic, boolean left, boolean isDown, boolean chording) {
        int key = left ? KeyEvent.KEYCODE_META_LEFT : KeyEvent.KEYCODE_META_RIGHT;
        int meta = KeyEvent.META_META_ON | (left ? KeyEvent.META_META_LEFT_ON : KeyEvent.META_META_RIGHT_ON);
        if (isDown) {
            sendKeyDown(ic, key, meta);
        } else {
            sendKeyUp(ic, key, meta);
        }
    }

    private void sendModifierKeysDown() {
        InputConnection ic = getCurrentInputConnection();
        if (mModShiftLeft && (!mShiftKeyState.left.isChording())) {
            sendShiftKey(ic, true, true);
        }
        if (mModCtrlLeft && (!mCtrlKeyState.left.isChording())) {
            sendCtrlKey(ic, true, true, false);
        }
        if (mModAltLeft && (!mAltKeyState.left.isChording())) {
            sendAltKey(ic, true, true, false);
        }
        if (mModMetaLeft && (!mMetaKeyState.left.isChording())) {
            sendMetaKey(ic, true, true, false);
        }
        if (mModShiftRight && (!mShiftKeyState.right.isChording())) {
            sendShiftKey(ic, false, true);
        }
        if (mModCtrlRight && (!mCtrlKeyState.right.isChording())) {
            sendCtrlKey(ic, false, true, false);
        }
        if (mModAltRight && (!mAltKeyState.right.isChording())) {
            sendAltKey(ic, false, true, false);
        }
        if (mModMetaRight && (!mMetaKeyState.right.isChording())) {
            sendMetaKey(ic, false, true, false);
        }
    }

    private void handleFNModifierKeysUp(boolean sendKey) {
        if (mModFn1 && (!mFn1KeyState.isChording())) {
            setModFn1(false);
        }
        if (mModFn2 && (!mFn2KeyState.isChording())) {
            setModFn2(false);
        }
    }

    private void handleModifierKeysUp(boolean sendKey) {
        InputConnection ic = getCurrentInputConnection();
        if (mModMetaLeft && (!mMetaKeyState.left.isChording())) {
            if (sendKey) sendMetaKey(ic, true, false, false);
            if (!mMetaKeyState.left.isChording()) setModMeta(false, mModMetaRight);
        }
        if (mModAltLeft && (!mAltKeyState.left.isChording())) {
            if (sendKey) sendAltKey(ic, true, false, false);
            if (!mAltKeyState.left.isChording()) setModAlt(false, mModAltRight);
        }
        if (mModCtrlLeft && (!mCtrlKeyState.left.isChording())) {
            if (sendKey) sendCtrlKey(ic, true, false, false);
            if (!mCtrlKeyState.left.isChording()) setModCtrl(false, mModCtrlRight);
        }
        if (mModShiftLeft && (!mShiftKeyState.left.isChording())) {
            if (sendKey) sendShiftKey(ic, true, false);
            if (!mShiftKeyState.left.isChording()) setModShift(false, mModShiftRight);
        }
        if (mModMetaRight && (!mMetaKeyState.right.isChording())) {
            if (sendKey) sendMetaKey(ic, false, false, false);
            if (!mMetaKeyState.right.isChording()) setModMeta(mModMetaLeft, false);
        }
        if (mModAltRight && (!mAltKeyState.right.isChording())) {
            if (sendKey) sendAltKey(ic, false, false, false);
            if (!mAltKeyState.right.isChording()) setModAlt(mModAltLeft, false);
        }
        if (mModCtrlRight && (!mCtrlKeyState.right.isChording())) {
            if (sendKey) sendCtrlKey(ic, false, false, false);
            if (!mCtrlKeyState.right.isChording()) setModCtrl(mModCtrlLeft, false);
        }
        if (mModShiftRight && (!mShiftKeyState.right.isChording())) {
            if (sendKey) sendShiftKey(ic, false, false);
            if (!mShiftKeyState.right.isChording()) setModShift(mModShiftLeft, false);
        }
    }

    private void sendModifierKeysUp() {
        handleModifierKeysUp(true);
    }

    private void sendSpecialKey(int code) {
        sendModifiedKeyDownUp(code);
//        sendDownUpKeyEvents(code);
        handleModifierKeysUp(false);
    }

    private final static int asciiToKeyCode[] = new int[127];
    private final static int KF_MASK = 0xffff;
    private final static int KF_SHIFTABLE = 0x10000;
    private final static int KF_UPPER = 0x20000;
    private final static int KF_LETTER = 0x40000;

    {
        // Include RETURN in this set even though it's not printable.
        // Most other non-printable keys get handled elsewhere.
        asciiToKeyCode['\n'] = KeyEvent.KEYCODE_ENTER | KF_SHIFTABLE;

        // Non-alphanumeric ASCII codes which have their own keys
        // (on some keyboards)
        asciiToKeyCode[' '] = KeyEvent.KEYCODE_SPACE | KF_SHIFTABLE;
        //asciiToKeyCode['!'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['"'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['#'] = KeyEvent.KEYCODE_POUND;
        //asciiToKeyCode['$'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['%'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['&'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['\''] = KeyEvent.KEYCODE_APOSTROPHE;
        //asciiToKeyCode['('] = KeyEvent.KEYCODE_;
        //asciiToKeyCode[')'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['*'] = KeyEvent.KEYCODE_STAR;
        asciiToKeyCode['+'] = KeyEvent.KEYCODE_PLUS;
        asciiToKeyCode[','] = KeyEvent.KEYCODE_COMMA;
        asciiToKeyCode['-'] = KeyEvent.KEYCODE_MINUS;
        asciiToKeyCode['.'] = KeyEvent.KEYCODE_PERIOD;
        asciiToKeyCode['/'] = KeyEvent.KEYCODE_SLASH;
        //asciiToKeyCode[':'] = KeyEvent.KEYCODE_;
        asciiToKeyCode[';'] = KeyEvent.KEYCODE_SEMICOLON;
        //asciiToKeyCode['<'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['='] = KeyEvent.KEYCODE_EQUALS;
        //asciiToKeyCode['>'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['?'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['@'] = KeyEvent.KEYCODE_AT;
        asciiToKeyCode['['] = KeyEvent.KEYCODE_LEFT_BRACKET;
        asciiToKeyCode['\\'] = KeyEvent.KEYCODE_BACKSLASH;
        asciiToKeyCode[']'] = KeyEvent.KEYCODE_RIGHT_BRACKET;
        //asciiToKeyCode['^'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['_'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['`'] = KeyEvent.KEYCODE_GRAVE;
        //asciiToKeyCode['{'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['|'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['}'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['~'] = KeyEvent.KEYCODE_;


        for (int i = 0; i <= 25; ++i) {
            asciiToKeyCode['a' + i] = KeyEvent.KEYCODE_A + i | KF_LETTER;
            asciiToKeyCode['A' + i] = KeyEvent.KEYCODE_A + i | KF_UPPER | KF_LETTER;
        }

        for (int i = 0; i <= 9; ++i) {
            asciiToKeyCode['0' + i] = KeyEvent.KEYCODE_0 + i;
        }
    }

    public void sendModifiableKeyChar(char ch) {
        // Support modified key events
        if ((mModShiftLeft || mModShiftRight || mModCtrlLeft || mModAltLeft || mModMetaLeft || mModCtrlRight || mModAltRight || mModMetaRight) && ch > 0 && ch < 127) {
            int combinedCode = asciiToKeyCode[ch];
            if (combinedCode > 0) {
                int code = combinedCode & KF_MASK;
                sendModifiedKeyDownUp(code);
                return;
            }
        }

        sendKeyChar(ch);
    }

    private void sendTab() {
        sendModifiedKeyDownUp(KeyEvent.KEYCODE_TAB);
    }

    private void sendEscape() {
        sendModifiedKeyDownUp(111 /*KeyEvent.KEYCODE_ESCAPE */);
    }

    private boolean processMultiKey(int primaryCode) {
        if (mDeadAccentBuffer.composeBuffer.length() > 0) {
            //Log.i(TAG, "processMultiKey: pending DeadAccent, length=" + mDeadAccentBuffer.composeBuffer.length());
            mDeadAccentBuffer.execute(primaryCode);
            mDeadAccentBuffer.clear();
            return true;
        }
        if (mComposeMode) {
            mComposeMode = mComposeBuffer.execute(primaryCode);
            return true;
        }
        return false;
    }

    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes, int x, int y) {
        long when = SystemClock.uptimeMillis();
        if (primaryCode != Keyboard.KEYCODE_DELETE
                || when > mLastKeyTime + QUICK_PRESS) {
            mDeleteCount = 0;
        }
        mLastKeyTime = when;
        final boolean distinctMultiTouch = mKeyboardSwitcher
                .hasDistinctMultitouch();
        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                handleFNModifierKeysUp(false);
                if (processMultiKey(primaryCode)) {
                    break;
                }
                handleBackspace();
                mDeleteCount++;
                break;
            case LatinKeyboardView.KEYCODE_SHIFT_LEFT:
                if (!distinctMultiTouch)
                    setModShift(!mModShiftLeft, mModShiftRight);
                break;
            case LatinKeyboardView.KEYCODE_SHIFT_RIGHT:
                if (!distinctMultiTouch)
                    setModShift(mModShiftLeft, !mModShiftRight);
                break;
            case Keyboard.KEYCODE_MODE_CHANGE:
                // Symbol key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch)
                    changeKeyboardMode();
                break;
            case LatinKeyboardView.KEYCODE_CTRL_LEFT:
                if (!distinctMultiTouch)
                    setModCtrl(!mModCtrlLeft, mModCtrlRight);
                break;
            case LatinKeyboardView.KEYCODE_CTRL_RIGHT:
                if (!distinctMultiTouch)
                    setModCtrl(mModCtrlLeft, !mModCtrlRight);
                break;
            case LatinKeyboardView.KEYCODE_ALT_LEFT:
                if (!distinctMultiTouch)
                    setModAlt(!mModAltLeft, mModAltRight);
                break;
            case LatinKeyboardView.KEYCODE_ALT_RIGHT:
                if (!distinctMultiTouch)
                    setModAlt(mModAltLeft, !mModAltRight);
                break;
            case LatinKeyboardView.KEYCODE_META_LEFT:
                if (!distinctMultiTouch)
                    setModMeta(!mModMetaLeft, mModMetaRight);
                break;
            case LatinKeyboardView.KEYCODE_META_RIGHT:
                if (!distinctMultiTouch)
                    setModMeta(mModMetaLeft, !mModMetaRight);
                break;
            case LatinKeyboardView.KEYCODE_FN_1:
                if (!distinctMultiTouch)
                    setModFn1(!mModFn1);
                break;
            case LatinKeyboardView.KEYCODE_FN_2:
                if (!distinctMultiTouch)
                    setModFn2(!mModFn2);
                break;
//            case LatinKeyboardView.KEYCODE_FN:
//                if (!distinctMultiTouch)
//                    setModFn(!mModFn);
//                break;
            case Keyboard.KEYCODE_STICK:
                handleFNModifierKeysUp(false);
                setSticky(!mStick);
                break;
            case Keyboard.KEYCODE_CANCEL:
                if (!isShowingOptionDialog()) {
                    handleClose();
                }
                break;
            case LatinKeyboardView.KEYCODE_FULLSCREEN_DUAL:
                setDualDisplay(null);
                break;
            case LatinKeyboardView.KEYCODE_OPTIONS:
                onOptionKeyPressed();
                break;
            case LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS:
                onOptionKeyLongPressed();
                break;
            case LatinKeyboardView.KEYCODE_COMPOSE:
                mComposeMode = !mComposeMode;
                mComposeBuffer.clear();
                break;
            case LatinKeyboardView.KEYCODE_NEXT_LANGUAGE:
                toggleLanguage(false, true);
                break;
            case LatinKeyboardView.KEYCODE_PREV_LANGUAGE:
                toggleLanguage(false, false);
                break;
//            case LatinKeyboardView.KEYCODE_VOICE:
//                if (mVoiceRecognitionTrigger.isInstalled()) {
//                    mVoiceRecognitionTrigger.startVoiceRecognition();
//                }
//                //startListening(false /* was a button press, was not a swipe */);
//                break;
            case 9 /* Tab */:
                if (processMultiKey(primaryCode)) {
                    break;
                }
                sendTab();
                break;
            case LatinKeyboardView.KEYCODE_ESCAPE:
                handleFNModifierKeysUp(false);
                if (processMultiKey(primaryCode)) {
                    break;
                }
                sendEscape();
                break;
            case LatinKeyboardView.KEYCODE_DPAD_UP:
            case LatinKeyboardView.KEYCODE_DPAD_DOWN:
            case LatinKeyboardView.KEYCODE_DPAD_LEFT:
            case LatinKeyboardView.KEYCODE_DPAD_RIGHT:
            case LatinKeyboardView.KEYCODE_DPAD_CENTER:
            case LatinKeyboardView.KEYCODE_HOME:
            case LatinKeyboardView.KEYCODE_END:
            case LatinKeyboardView.KEYCODE_PAGE_UP:
            case LatinKeyboardView.KEYCODE_PAGE_DOWN:
            case LatinKeyboardView.KEYCODE_FKEY_F1:
            case LatinKeyboardView.KEYCODE_FKEY_F2:
            case LatinKeyboardView.KEYCODE_FKEY_F3:
            case LatinKeyboardView.KEYCODE_FKEY_F4:
            case LatinKeyboardView.KEYCODE_FKEY_F5:
            case LatinKeyboardView.KEYCODE_FKEY_F6:
            case LatinKeyboardView.KEYCODE_FKEY_F7:
            case LatinKeyboardView.KEYCODE_FKEY_F8:
            case LatinKeyboardView.KEYCODE_FKEY_F9:
            case LatinKeyboardView.KEYCODE_FKEY_F10:
            case LatinKeyboardView.KEYCODE_FKEY_F11:
            case LatinKeyboardView.KEYCODE_FKEY_F12:
            case LatinKeyboardView.KEYCODE_FORWARD_DEL:
            case LatinKeyboardView.KEYCODE_INSERT:
            case LatinKeyboardView.KEYCODE_SYSRQ:
            case LatinKeyboardView.KEYCODE_BREAK:
            case LatinKeyboardView.KEYCODE_NUM_LOCK:
            case LatinKeyboardView.KEYCODE_SCROLL_LOCK:
                if (processMultiKey(primaryCode)) {
                    break;
                }
                // send as plain keys, or as escape sequence if needed
                sendSpecialKey(-primaryCode);
                handleFNModifierKeysUp(false);
                break;
            default:
                if (!mComposeMode && mDeadKeysActive && Character.getType(primaryCode) == Character.NON_SPACING_MARK) {
                    //Log.i(TAG, "possible dead character: " + primaryCode);
                    if (!mDeadAccentBuffer.execute(primaryCode)) {
                        //Log.i(TAG, "double dead key");
                        break; // pressing a dead key twice produces spacing equivalent
                    }
                    updateShiftKeyState(getCurrentInputEditorInfo());
                    break;
                }
                if (processMultiKey(primaryCode)) {
                    handleFNModifierKeysUp(false);
                    break;
                }
                LatinIMEUtil.RingCharBuffer.getInstance().push((char) primaryCode, x, y);
                handleCharacter(primaryCode, keyCodes);
                handleFNModifierKeysUp(false);
                // Cancel the just reverted state
        }
        mKeyboardSwitcher.onKey(primaryCode);
        // Reset after any single keystroke
        mEnteredText = null;
        //mDeadAccentBuffer.clear();  // FIXME
    }

    private void setSticky(boolean enable) {
        mStick = enable;
        mKeyboardSwitcher.setSticky(mStick);
        Toast.makeText(this, mStick ? R.string.sticky_enabled : R.string.sticky_disabled, Toast.LENGTH_SHORT).show();
    }

    static long lastRequestDualMilli = 0;

    private void setDualDisplay(Boolean newState) {
        if (!LGMultiDisplayUtils.supportDualScreen()) return;
        Log.d(TAG, "setDualDisplay: " + newState);
        orientationLocker.cancelUnlock();
        boolean enabled = false;
        if (!LGMultiDisplayUtils.checkForceLandscape(this) && (newState == null || newState)) {
            Log.d(TAG, "set lastRequestDualMilli");
            lastRequestDualMilli = System.currentTimeMillis();
            orientationLocker.saveCurrentWindowManager(getWindow().getWindow(), getWindow().getWindow().getWindowManager());
        }
        if (newState == null) {
            mDualEnabled = ToggleFullScreenIME.Toggle(this);
            enabled = mDualEnabled;
        } else {
            try {
                enabled = newState;
                // comment this helps the keyboard from recreating
//                ToggleFullScreenIME.ToggleSimply(this, newState);
            } catch (Exception ex) {
            }
        }
        if (enabled) {
            orientationLocker.lock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            orientationLocker.postUnlock(1000);
        }
    }

    public void onText(CharSequence text) {
        //mDeadAccentBuffer.clear();  // FIXME
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        ic.beginBatchEdit();
        ic.commitText(text, 1);
        ic.endBatchEdit();
        updateShiftKeyState(getCurrentInputEditorInfo());
        mKeyboardSwitcher.onKey(0); // dummy key code.
        mEnteredText = text;
    }

    public void onCancel() {
        // User released a finger outside any key
        mKeyboardSwitcher.onCancelInput();
    }

    private void handleBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;

        ic.beginBatchEdit();


        if (mEnteredText != null
                && sameAsTextBeforeCursor(ic, mEnteredText)) {
            ic.deleteSurroundingText(mEnteredText.length(), 0);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            if (mDeleteCount > DELETE_ACCELERATE_AT) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            }
        }
        ic.endBatchEdit();
    }

    private void setModShift(boolean left, boolean right) {
        mKeyboardSwitcher.setShiftIndicator(left, right);
        mModShiftLeft = left;
        mModShiftRight = right;
    }

    private void setModCtrl(boolean left, boolean right) {
        mKeyboardSwitcher.setCtrlIndicator(left, right);
        mModCtrlLeft = left;
        mModCtrlRight = right;
    }

    private void setModAlt(boolean left, boolean right) {
        mKeyboardSwitcher.setAltIndicator(left, right);
        mModAltLeft = left;
        mModAltRight = right;
    }

    private void setModMeta(boolean left, boolean right) {
        mKeyboardSwitcher.setMetaIndicator(left, right);
        mModMetaLeft = left;
        mModMetaRight = right;
    }

    private void setModFn1(boolean active) {
        mKeyboardSwitcher.setFn1Indicator(active);
        mModFn1 = active;
    }

    private void setModFn2(boolean active) {
        mKeyboardSwitcher.setFn2Indicator(active);
        mModFn2 = active;
    }

    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (mModShiftLeft || mModShiftRight || mModCtrlLeft || mModAltLeft || mModMetaLeft || mModCtrlRight || mModAltRight || mModMetaRight) {
            commitTyped(getCurrentInputConnection(), true); // sets mPredicting=false
        }
        sendModifiableKeyChar((char) primaryCode);
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private void handleClose() {
        commitTyped(getCurrentInputConnection(), true);
        requestHideSelf(0);
        if (mKeyboardSwitcher != null) {
            LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
            if (inputView != null) {
                inputView.closing();
            }
        }
    }

    private boolean sameAsTextBeforeCursor(InputConnection ic, CharSequence text) {
        CharSequence beforeText = ic.getTextBeforeCursor(text.length(), 0);
        return TextUtils.equals(text, beforeText);
    }

    void toggleLanguage(boolean reset, boolean next) {
        if (reset) {
            mLanguageSwitcher.reset();
        } else {
            if (next) {
                mLanguageSwitcher.next();
            } else {
                mLanguageSwitcher.prev();
            }
        }
        int currentKeyboardMode = mKeyboardSwitcher.getKeyboardMode();
        reloadKeyboards();
        mKeyboardSwitcher.makeKeyboards(true);
        mKeyboardSwitcher.setKeyboardMode(currentKeyboardMode, 0,
                false);
        mLanguageSwitcher.persist();
        mDeadKeysActive = mLanguageSwitcher.allowDeadKeys();
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        Log.i("PCKeyboard", "onSharedPreferenceChanged()");
        boolean needReload = false;
        Resources res = getResources();

        // Apply globally handled shared prefs
        sKeyboardSettings.sharedPreferenceChanged(sharedPreferences, key);
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_NEED_RELOAD)) {
            needReload = true;
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RECREATE_INPUT_VIEW)) {
            mKeyboardSwitcher.recreateInputView();
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_MODE_OVERRIDE)) {
            mKeyboardModeOverrideLandscape = 0;
            mKeyboardModeOverridePortrait = 0;
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_KEYBOARDS)) {
            toggleLanguage(true, true);
        }
        int unhandledFlags = sKeyboardSettings.unhandledFlags();
        if (unhandledFlags != GlobalKeyboardSettings.FLAG_PREF_NONE) {
            Log.w(TAG, "Not all flag settings handled, remaining=" + unhandledFlags);
        }

        if (PREF_SELECTED_LANGUAGES.equals(key)) {
            mLanguageSwitcher.loadLocales(sharedPreferences);
            mRefreshKeyboardRequired = true;
        } else if (PREF_FULLSCREEN_OVERRIDE.equals(key)) {
            mFullscreenOverride = sharedPreferences.getBoolean(
                    PREF_FULLSCREEN_OVERRIDE, res
                            .getBoolean(R.bool.default_fullscreen_override));
            needReload = true;
        } else if (PREF_FORCE_KEYBOARD_ON.equals(key)) {
            mForceKeyboardOn = sharedPreferences.getBoolean(
                    PREF_FORCE_KEYBOARD_ON, res
                            .getBoolean(R.bool.default_force_keyboard_on));
            needReload = true;
        } else if (PREF_KEYBOARD_NOTIFICATION.equals(key)) {
            mKeyboardNotification = sharedPreferences.getBoolean(
                    PREF_KEYBOARD_NOTIFICATION, res
                            .getBoolean(R.bool.default_keyboard_notification));
            setNotification(mKeyboardNotification);
        } else if (PREF_HEIGHT_PORTRAIT.equals(key)) {
            mHeightPortrait = getHeight(sharedPreferences,
                    PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait));
            needReload = true;
        } else if (PREF_HEIGHT_LANDSCAPE.equals(key)) {
            mHeightLandscape = getHeight(sharedPreferences,
                    PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape));
            needReload = true;
        } else if (PREF_HINT_MODE.equals(key)) {
            LatinIME.sKeyboardSettings.hintMode = Integer.parseInt(sharedPreferences.getString(PREF_HINT_MODE,
                    res.getString(R.string.default_hint_mode)));
            needReload = true;
        } else if (PREF_LONGPRESS_TIMEOUT.equals(key)) {
            LatinIME.sKeyboardSettings.longpressTimeout = getPrefInt(sharedPreferences, PREF_LONGPRESS_TIMEOUT,
                    res.getString(R.string.default_long_press_duration));
        } else if (PREF_RENDER_MODE.equals(key)) {
            LatinIME.sKeyboardSettings.renderMode = getPrefInt(sharedPreferences, PREF_RENDER_MODE,
                    res.getString(R.string.default_render_mode));
            needReload = true;
        } else if (PREF_SWIPE_UP.equals(key)) {
            mSwipeUpAction = sharedPreferences.getString(PREF_SWIPE_UP, res.getString(R.string.default_swipe_up));
        } else if (PREF_SWIPE_DOWN.equals(key)) {
            mSwipeDownAction = sharedPreferences.getString(PREF_SWIPE_DOWN, res.getString(R.string.default_swipe_down));
        } else if (PREF_SWIPE_LEFT.equals(key)) {
            mSwipeLeftAction = sharedPreferences.getString(PREF_SWIPE_LEFT, res.getString(R.string.default_swipe_left));
        } else if (PREF_SWIPE_RIGHT.equals(key)) {
            mSwipeRightAction = sharedPreferences.getString(PREF_SWIPE_RIGHT, res.getString(R.string.default_swipe_right));
        } else if (PREF_VOL_UP.equals(key)) {
            mVolUpAction = sharedPreferences.getString(PREF_VOL_UP, res.getString(R.string.default_vol_up));
        } else if (PREF_VOL_DOWN.equals(key)) {
            mVolDownAction = sharedPreferences.getString(PREF_VOL_DOWN, res.getString(R.string.default_vol_down));
        } else if (PREF_VIBRATE_LEN.equals(key)) {
            mVibrateLen = getPrefInt(sharedPreferences, PREF_VIBRATE_LEN, getResources().getString(R.string.vibrate_duration_ms));
        }

        updateKeyboardOptions();
        if (needReload) {
            mKeyboardSwitcher.makeKeyboards(true);
        }
    }

    private boolean doSwipeAction(String action) {
        //Log.i(TAG, "doSwipeAction + " + action);
        if (action == null || action.equals("") || action.equals("none")) {
            return false;
        } else if (action.equals("close")) {
            handleClose();
        } else if (action.equals("settings")) {
            launchSettings();
        } else if (action.equals("lang_prev")) {
            toggleLanguage(false, false);
        } else if (action.equals("lang_next")) {
            toggleLanguage(false, true);
        } else if (action.equals("full_mode")) {
            if (isPortrait()) {
                mKeyboardModeOverridePortrait = (mKeyboardModeOverridePortrait + 1) % mNumKeyboardModes;
            } else {
                mKeyboardModeOverrideLandscape = (mKeyboardModeOverrideLandscape + 1) % mNumKeyboardModes;
            }
            toggleLanguage(true, true);
        } else if (action.equals("extension")) {
            sKeyboardSettings.useExtension = !sKeyboardSettings.useExtension;
            reloadKeyboards();
        } else if (action.equals("height_up")) {
            if (isPortrait()) {
                mHeightPortrait += 5;
                if (mHeightPortrait > 70) mHeightPortrait = 70;
            } else {
                mHeightLandscape += 5;
                if (mHeightLandscape > 70) mHeightLandscape = 70;
            }
            toggleLanguage(true, true);
        } else if (action.equals("height_down")) {
            if (isPortrait()) {
                mHeightPortrait -= 5;
                if (mHeightPortrait < 15) mHeightPortrait = 15;
            } else {
                mHeightLandscape -= 5;
                if (mHeightLandscape < 15) mHeightLandscape = 15;
            }
            toggleLanguage(true, true);
        } else {
            Log.i(TAG, "Unsupported swipe action config: " + action);
        }
        return true;
    }

    public boolean swipeRight() {
        return doSwipeAction(mSwipeRightAction);
    }

    public boolean swipeLeft() {
        return doSwipeAction(mSwipeLeftAction);
    }

    public boolean swipeDown() {
        return doSwipeAction(mSwipeDownAction);
    }

    public boolean swipeUp() {
        return doSwipeAction(mSwipeUpAction);
    }

    public void onPress(int primaryCode) {
        InputConnection ic = getCurrentInputConnection();
        if (mKeyboardSwitcher.isVibrateAndSoundFeedbackRequired()) {
            vibrate();
            playKeyClick(primaryCode);
        }
        final boolean distinctMultiTouch = mKeyboardSwitcher
                .hasDistinctMultitouch();
        if (distinctMultiTouch
                && primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
            changeKeyboardMode();
            mSymbolKeyState.onPress();
            mKeyboardSwitcher.setAutoModeSwitchStateMomentary();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_LEFT) {
            if (mModShiftLeft) {
                setModShift(false, mModShiftRight);
                mShiftKeyState.left.onRelease();
            } else {
                setModShift(true, mModShiftRight);
                mShiftKeyState.left.onPress();
                sendShiftKey(ic, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
            if (mModCtrlLeft) {
                setModCtrl(false, mModCtrlRight);
                mCtrlKeyState.left.onRelease();
            } else {
                setModCtrl(true, mModCtrlRight);
                mCtrlKeyState.left.onPress();
                sendCtrlKey(ic, true, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT) {
            if (mModAltLeft) {
                setModAlt(true, mModAltRight);
                mAltKeyState.left.onRelease();
            } else {
                setModAlt(true, mModAltRight);
                mAltKeyState.left.onPress();
                sendAltKey(ic, true, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_META_LEFT) {
            if (mModMetaLeft) {
                setModMeta(false, mModMetaRight);
                mMetaKeyState.left.onRelease();
            } else {
                setModMeta(true, mModMetaRight);
                mMetaKeyState.left.onPress();
                sendMetaKey(ic, true, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_RIGHT) {
            if (mModShiftRight) {
                setModShift(mModShiftLeft, false);
                mShiftKeyState.right.onRelease();
            } else {
                setModShift(mModShiftLeft, true);
                mShiftKeyState.right.onPress();
                sendShiftKey(ic, false, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_CTRL_RIGHT) {
            if (mModCtrlRight) {
                setModCtrl(mModCtrlLeft, false);
                mCtrlKeyState.right.onRelease();
            } else {
                setModCtrl(mModCtrlLeft, true);
                mCtrlKeyState.right.onPress();
                sendCtrlKey(ic, false, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_ALT_RIGHT) {
            if (mModAltRight) {
                setModAlt(mModAltLeft, false);
                mAltKeyState.right.onRelease();
            } else {
                setModAlt(mModAltLeft, true);
                mAltKeyState.right.onPress();
                sendAltKey(ic, false, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_META_RIGHT) {
            if (mModMetaRight) {
                setModMeta(mModMetaLeft, false);
                mMetaKeyState.right.onRelease();
            } else {
                setModMeta(mModMetaLeft, true);
                mMetaKeyState.right.onPress();
                sendMetaKey(ic, false, true, true);
            }
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_FN_1) {
            setModFn1(!mModFn1);
            mFn1KeyState.onPress();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_FN_2) {
            setModFn2(!mModFn2);
            mFn2KeyState.onPress();
        } else {
            mShiftKeyState.left.onOtherKeyPressed();
            mShiftKeyState.right.onOtherKeyPressed();
            mCtrlKeyState.left.onOtherKeyPressed();
            mCtrlKeyState.right.onOtherKeyPressed();
            mAltKeyState.left.onOtherKeyPressed();
            mAltKeyState.right.onOtherKeyPressed();
            mMetaKeyState.left.onOtherKeyPressed();
            mMetaKeyState.right.onOtherKeyPressed();
            mSymbolKeyState.onOtherKeyPressed();
            mFnKeyState.onOtherKeyPressed();
            mFn1KeyState.onOtherKeyPressed();
            mFn2KeyState.onOtherKeyPressed();
        }
    }

    public void onRelease(int primaryCode) {
        // Reset any drag flags in the keyboard
        if (mKeyboardSwitcher.getInputView().getKeyboard() != null)
            ((LatinKeyboard) mKeyboardSwitcher.getInputView().getKeyboard())
                    .keyReleased();
        // vibrate();
        final boolean distinctMultiTouch = mKeyboardSwitcher
                .hasDistinctMultitouch();
        InputConnection ic = getCurrentInputConnection();
        if (distinctMultiTouch
                && primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
            // Snap back to the previous keyboard mode if the user chords the
            // mode change key and
            // other key, then released the mode change key.
            if (mKeyboardSwitcher.isInChordingAutoModeSwitchState())
                changeKeyboardMode();
            mSymbolKeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
            if (mCtrlKeyState.left.isChording()) {
                setModCtrl(false, mModCtrlRight);
            }
            sendCtrlKey(ic, true, false, true);
            mCtrlKeyState.left.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_LEFT) {
            if (mShiftKeyState.left.isChording()) {
                setModShift(false, mModShiftRight);
            }
            sendShiftKey(ic, true, false);
            mShiftKeyState.left.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT) {
            if (mAltKeyState.left.isChording()) {
                setModAlt(false, mModAltRight);
            }
            sendAltKey(ic, true, false, true);
            mAltKeyState.left.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_META_LEFT) {
            if (mMetaKeyState.left.isChording()) {
                setModMeta(false, mModMetaRight);
            }
            sendMetaKey(ic, true, false, true);
            mMetaKeyState.left.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_RIGHT) {
            if (mShiftKeyState.right.isChording()) {
                setModShift(mModShiftLeft, false);
            }
            sendShiftKey(ic, false, false);
            mShiftKeyState.right.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_CTRL_RIGHT) {
            if (mCtrlKeyState.right.isChording()) {
                setModCtrl(mModCtrlLeft, false);
            }
            sendCtrlKey(ic, false, false, true);
            mCtrlKeyState.right.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_ALT_RIGHT) {
            if (mAltKeyState.right.isChording()) {
                setModAlt(mModAltLeft, false);
            }
            sendAltKey(ic, false, false, true);
            mAltKeyState.right.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_META_RIGHT) {
            if (mMetaKeyState.right.isChording()) {
                setModMeta(mModMetaLeft, false);
            }
            sendMetaKey(ic, false, false, true);
            mMetaKeyState.right.onRelease();
//        } else if (distinctMultiTouch
//                && primaryCode == LatinKeyboardView.KEYCODE_FN) {
//            if (mFnKeyState.isChording()) {
//                setModFn(false);
//            }
//            mFnKeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_FN_1) {
            if (mFn1KeyState.isChording()) {
                setModFn1(false);
            }
            mFn1KeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_FN_2) {
            if (mFn2KeyState.isChording()) {
                setModFn2(false);
            }
            mFn2KeyState.onRelease();
        }
        // WARNING: Adding a chording modifier key? Make sure you also
        // edit PointerTracker.isModifierInternal(), otherwise it will
        // force a release event instead of chording.
    }

    // receive ringer mode changes to detect silent mode
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerMode();
        }
    };

    // update flags for silent mode
    private void updateRingerMode() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (mAudioManager != null) {
            mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
        }
    }

    private float getKeyClickVolume() {
        if (mAudioManager == null) return 0.0f; // shouldn't happen

        // The volume calculations are poorly documented, this is the closest I could
        // find for explaining volume conversions:
        // http://developer.android.com/reference/android/media/MediaPlayer.html#setAuxEffectSendLevel(float)
        //
        //   Note that the passed level value is a raw scalar. UI controls should be scaled logarithmically:
        //   the gain applied by audio framework ranges from -72dB to 0dB, so an appropriate conversion 
        //   from linear UI input x to level is: x == 0 -> level = 0 0 < x <= R -> level = 10^(72*(x-R)/20/R)

        int method = sKeyboardSettings.keyClickMethod; // See click_method_values in strings.xml
        if (method == 0) return FX_VOLUME;

        float targetVol = sKeyboardSettings.keyClickVolume;

        if (method > 1) {
            // TODO(klausw): on some devices the media volume controls the click volume?
            // If that's the case, try to set a relative target volume.
            int mediaMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int mediaVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            //Log.i(TAG, "getKeyClickVolume relative, media vol=" + mediaVol + "/" + mediaMax);
            float channelVol = (float) mediaVol / mediaMax;
            if (method == 2) {
                targetVol *= channelVol;
            } else if (method == 3) {
                if (channelVol == 0) return 0.0f; // Channel is silent, won't get audio
                targetVol = Math.min(targetVol / channelVol, 1.0f); // Cap at 1.0
            }
        }
        // Set absolute volume, treating the percentage as a logarithmic control
        float vol = (float) Math.pow(10.0, FX_VOLUME_RANGE_DB * (targetVol - 1) / 20);
        //Log.i(TAG, "getKeyClickVolume absolute, target=" + targetVol + " amp=" + vol);
        return vol;
    }

    private void playKeyClick(int primaryCode) {
        // if mAudioManager is null, we don't have the ringer state yet
        // mAudioManager will be set by updateRingerMode
        if (mAudioManager == null) {
            if (mKeyboardSwitcher.getInputView() != null) {
                updateRingerMode();
            }
        }
        if (mSoundOn && !mSilentMode) {
            // FIXME: Volume and enable should come from UI settings
            // FIXME: These should be triggered after auto-repeat logic
            int sound = AudioManager.FX_KEYPRESS_STANDARD;
            switch (primaryCode) {
                case Keyboard.KEYCODE_DELETE:
                    sound = AudioManager.FX_KEYPRESS_DELETE;
                    break;
                case ASCII_ENTER:
                    sound = AudioManager.FX_KEYPRESS_RETURN;
                    break;
                case ASCII_SPACE:
                    sound = AudioManager.FX_KEYPRESS_SPACEBAR;
                    break;
            }
            mAudioManager.playSoundEffect(sound, getKeyClickVolume());
        }
    }

    private void vibrate() {
        if (!mVibrateOn) {
            return;
        }
        vibrate(mVibrateLen);
    }

    void vibrate(int len) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(len);
            return;
        }

        if (mKeyboardSwitcher.getInputView() != null) {
            mKeyboardSwitcher.getInputView().performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    /* package */boolean getPopupOn() {
        return mPopupOn;
    }

    protected void launchSettings() {
        launchSettings(LatinIMESettings.class);
    }

    protected void launchSettings(
            Class<? extends PreferenceActivity> settingsClass) {
        handleClose();
        Intent intent = new Intent();
        intent.setClass(LatinIME.this, settingsClass);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void loadSettings() {
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager
                .getDefaultSharedPreferences(this);
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false);
        mVibrateLen = getPrefInt(sp, PREF_VIBRATE_LEN, getResources().getString(R.string.vibrate_duration_ms));
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false);
        mPopupOn = sp.getBoolean(PREF_POPUP_ON, mResources
                .getBoolean(R.bool.default_popup_preview));
        mAutoCapPref = sp.getBoolean(PREF_AUTO_CAP, getResources().getBoolean(
                R.bool.default_auto_cap));
        final String voiceMode = sp.getString(PREF_VOICE_MODE,
                getString(R.string.voice_mode_main));
        mLanguageSwitcher.loadLocales(sp);
        mDeadKeysActive = mLanguageSwitcher.allowDeadKeys();
    }

    private void showOptionsMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_dialog_keyboard);
        builder.setNegativeButton(android.R.string.cancel, null);
        CharSequence itemSettings = getString(R.string.english_ime_settings);
        CharSequence itemInputMethod = getString(R.string.selectInputMethod);
        builder.setItems(new CharSequence[]{itemInputMethod, itemSettings},
                new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface di, int position) {
                        di.dismiss();
                        switch (position) {
                            case POS_SETTINGS:
                                launchSettings();
                                break;
                            case POS_METHOD:
                                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                                        .showInputMethodPicker();
                                break;
                        }
                    }
                });
        builder.setTitle(mResources
                .getString(R.string.english_ime_input_options));
        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mKeyboardSwitcher.getInputView().getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    public void changeKeyboardMode() {
        KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.toggleSymbols();
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  Keyboard mode = " + mKeyboardSwitcher.getKeyboardMode());
        p.println("  mComposing=" + mComposing);
        p.println("  mSoundOn=" + mSoundOn);
        p.println("  mVibrateOn=" + mVibrateOn);
        p.println("  mPopupOn=" + mPopupOn);
    }

    // Characters per second measurement

    private static Pattern NUMBER_RE = Pattern.compile("(\\d+).*");

    static int getIntFromString(String val, int defVal) {
        Matcher num = NUMBER_RE.matcher(val);
        if (!num.matches()) return defVal;
        return Integer.parseInt(num.group(1));
    }

    static int getPrefInt(SharedPreferences prefs, String prefName, int defVal) {
        String prefVal = prefs.getString(prefName, Integer.toString(defVal));
        //Log.i("PCKeyboard", "getPrefInt " + prefName + " = " + prefVal + ", default " + defVal);
        return getIntFromString(prefVal, defVal);
    }

    static int getPrefInt(SharedPreferences prefs, String prefName, String defStr) {
        int defVal = getIntFromString(defStr, 0);
        return getPrefInt(prefs, prefName, defVal);
    }

    static int getHeight(SharedPreferences prefs, String prefName, String defVal) {
        int val = getPrefInt(prefs, prefName, defVal);
        if (val < 15)
            val = 15;
        if (val > 75)
            val = 75;
        return val;
    }
}
