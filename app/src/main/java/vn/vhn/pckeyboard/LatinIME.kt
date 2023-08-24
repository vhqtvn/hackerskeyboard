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
package vn.vhn.pckeyboard

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Configuration
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.*
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.Log
import android.util.PrintWriterPrinter
import android.util.Printer
import android.view.*
import android.view.inputmethod.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lge.ime.util.p118f.DualKeyboardManager.Companion.setContext
import com.lge.ime.util.p118f.LGMultiDisplayUtils.checkForceLandscape
import com.lge.ime.util.p118f.LGMultiDisplayUtils.supportDualScreen
import com.novia.lg_dualscreen_ime.ToggleFullScreenIME
import com.vhn.SurfaceDuoPaneManager
import com.vhn.SurfaceDuoUtils
import james.crasher.Crasher
import vn.vhn.pckeyboard.LatinIMEUtil.GCUtils
import vn.vhn.pckeyboard.LatinIMEUtil.RingCharBuffer
import vn.vhn.pckeyboard.orientation.IScreenOrientationLocker
import vn.vhn.pckeyboard.orientation.createScreenOrientationLocker
import vn.vhn.pckeyboard.root.RootCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.regex.Pattern


/**
 * Input method implementation for Qwerty'ish keyboard.
 */
class LatinIME : InputMethodService(), ComposeSequencing,
    LatinKeyboardBaseView.OnKeyboardActionListener, OnSharedPreferenceChangeListener {
    // private LatinKeyboardView mInputView;

    private var mOrientationLockerCreationTries = 0
    private var mOrientationLockerImpl: IScreenOrientationLocker? = null
    private val orientationLocker: IScreenOrientationLocker?
        get() {
            //there should be no race here
            if (mOrientationLockerImpl == null && mOrientationLockerCreationTries <= 5) {
                mOrientationLockerCreationTries++
                mOrientationLockerImpl = createScreenOrientationLocker(this)
            }
            return mOrientationLockerImpl
        }
    private var mOptionsDialog: AlertDialog? = null

    /* package */
    lateinit var mKeyboardSwitcher: KeyboardSwitcher
    private lateinit var mResources: Resources
    private var mSystemLocale: String? = null
    private var mLanguageSwitcher: LanguageSwitcher? = null
    private val mComposing = StringBuilder()

    // TODO move this state variable outside LatinIME
    private var mModShiftLeft = false
    private var mModCtrlLeft = false
    private var mModAltLeft = false
    private var mModMetaLeft = false
    private var mModShiftRight = false
    private var mModCtrlRight = false
    private var mModAltRight = false
    private var mModMetaRight = false
    private var mModFn = false
    private var mModFn1 = false
    private var mModFn2 = false
    private var mVibrateOn = false
    private var mVibrateLen = 0
    private var mSoundOn = false
    /* package */  var popupOn = false
        private set
    private var mAutoCapPref = false
    private var mDeadKeysActive = false
    private var mFullscreenOverride = false
    private var mForceKeyboardOn = false
    private var mStick = false
    private var mNoIME = false
    private var mOrientationLocked = false
    private var mKeyboardNotification = false
    private var mSwipeUpAction: String? = null
    private var mSwipeDownAction: String? = null
    private var mSwipeLeftAction: String? = null
    private var mSwipeRightAction: String? = null
    private var mVolUpAction: String? = null
    private var mVolDownAction: String? = null
    private var mHeightPortrait = 0
    private var mHeightLandscape = 0
    private var mNumKeyboardModes = 3
    private var mKeyboardModeOverridePortrait = 0
    private var mKeyboardModeOverrideLandscape = 0
    private var mOrientation = -1337
    private var mDeleteCount = 0
    private var mLastKeyTime: Long = 0

    // Modifier keys state
    internal inner class BinaryModifierKeystate {
        var left = ModifierKeyState()
        var right = ModifierKeyState()
    }

    private val mShiftKeyState = BinaryModifierKeystate()
    private val mCtrlKeyState = BinaryModifierKeystate()
    private val mAltKeyState = BinaryModifierKeystate()
    private val mMetaKeyState = BinaryModifierKeystate()
    private val mSymbolKeyState = ModifierKeyState()
    private val mFnKeyState = ModifierKeyState()
    private val mFn1KeyState = ModifierKeyState()
    private val mFn2KeyState = ModifierKeyState()

    // Compose sequence handling
    private var mComposeMode = false
    private val mComposeBuffer = ComposeSequence(this)
    private val mDeadAccentBuffer: ComposeSequence = DeadAccentSequence(this)
    private var mAudioManager: AudioManager? = null

    // Align sound effect volume on music volume
    private val FX_VOLUME = -1.0f
    private val FX_VOLUME_RANGE_DB = 72.0f
    private var mSilentMode = false

    /* package */
    var mWordSeparators: String? = null
    private val mSentenceSeparators: String? = null
    private var mConfigurationChanging = false

    // Keeps track of most recently inserted text (multi-character key) for
    // reverting
    private var mEnteredText: CharSequence? = null
    private var mRefreshKeyboardRequired = false
    private var mNotificationReceiver: NotificationReceiver? = null
    var mDefaultHandler = Handler()
    var surfaceDuoPaneManager: SurfaceDuoPaneManager? = null
        private set

    override fun onCreate() {
        Log.i("PCKeyboard", "onCreate(), os.version=" + System.getProperty("os.version"))
        KeyboardSwitcher.init(this)
        super.onCreate()
        if (!BuildConfig.GOOGLEPLAY_BUILD)
            Crasher(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.window!!.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            val decorFitsFlags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

            val decorView: View = window.window!!.decorView
            val sysUiVis = decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = sysUiVis or decorFitsFlags
        }
        if (SurfaceDuoUtils.isDeviceSurfaceDuo(packageManager)) {
            surfaceDuoPaneManager = SurfaceDuoPaneManager(applicationContext)
            surfaceDuoPaneManager!!.ensureInitialized()
            surfaceDuoPaneManager!!.connect()
        }
        if (supportDualScreen()) {
            isDualEnabled = checkForceLandscape(this)
        } else {
            isDualEnabled = false
        }
        sInstance = this
        // setStatusIcon(R.drawable.ime_qwerty);
        mResources = resources
        val conf = mResources.getConfiguration()
        var orientationUpdated = updateOrientation(conf)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        mLanguageSwitcher = LanguageSwitcher(this)
        mLanguageSwitcher!!.loadLocales(prefs)
        mKeyboardSwitcher = KeyboardSwitcher.instance
        mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher)
        mSystemLocale = conf.locale.toString()
        mLanguageSwitcher!!.setSystemLocale(conf.locale)
        var inputLanguage = mLanguageSwitcher!!.getInputLanguage()
        if (inputLanguage == null) {
            inputLanguage = conf.locale.toString()
        }
        val res = resources
        mFullscreenOverride = prefs.getBoolean(PREF_FULLSCREEN_OVERRIDE,
            res.getBoolean(R.bool.default_fullscreen_override))
        mForceKeyboardOn = prefs.getBoolean(PREF_FORCE_KEYBOARD_ON,
            res.getBoolean(R.bool.default_force_keyboard_on))
        mKeyboardNotification = prefs.getBoolean(PREF_KEYBOARD_NOTIFICATION,
            res.getBoolean(R.bool.default_keyboard_notification))
        mNoIME = prefs.getBoolean(PREF_NOIME, false)
        mHeightPortrait =
            getHeight(prefs, PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait))
        mHeightLandscape = getHeight(prefs,
            PREF_HEIGHT_LANDSCAPE,
            res.getString(R.string.default_height_landscape))
        sKeyboardSettings.hintMode =
            prefs.getString(PREF_HINT_MODE, res.getString(R.string.default_hint_mode))!!
                .toInt()
        sKeyboardSettings.longpressTimeout = getPrefInt(prefs,
            PREF_LONGPRESS_TIMEOUT,
            res.getString(R.string.default_long_press_duration))
        sKeyboardSettings.renderMode =
            getPrefInt(prefs, PREF_RENDER_MODE, res.getString(R.string.default_render_mode))
        mSwipeUpAction = prefs.getString(PREF_SWIPE_UP, res.getString(R.string.default_swipe_up))
        mSwipeDownAction =
            prefs.getString(PREF_SWIPE_DOWN, res.getString(R.string.default_swipe_down))
        mSwipeLeftAction =
            prefs.getString(PREF_SWIPE_LEFT, res.getString(R.string.default_swipe_left))
        mSwipeRightAction =
            prefs.getString(PREF_SWIPE_RIGHT, res.getString(R.string.default_swipe_right))
        mVolUpAction = prefs.getString(PREF_VOL_UP, res.getString(R.string.default_vol_up))
        mVolDownAction = prefs.getString(PREF_VOL_DOWN, res.getString(R.string.default_vol_down))
        sKeyboardSettings.initPrefs(prefs, res)
        updateKeyboardOptions()
        if (supportDualScreen()) {
            if (updateOrientation(conf)) orientationUpdated = true
        }
        if (orientationUpdated) reloadKeyboards()
        GCUtils.instance.reset()
        // register to receive ringer mode changes for silent mode
        val filter = IntentFilter(
            AudioManager.RINGER_MODE_CHANGED_ACTION)
        registerReceiver(mReceiver, filter)
        prefs.registerOnSharedPreferenceChangeListener(this)
        setNotification(mKeyboardNotification)
        if (supportDualScreen()) {
            setContext(this)!!.setInputMethodService(this)
            Handler().postDelayed({ reloadKeyboards() }, 50)
        } else {
            mOrientation = conf.orientation
        }
    }

    private fun updateOrientation(conf: Configuration): Boolean {
        if (!supportDualScreen()) {
            if (conf.orientation != mOrientation) {
                mOrientation = conf.orientation
                return true
            }
            return false
        }
        var newOrientation = mOrientation
        if (mOrientation == -1337) {
            newOrientation =
                if (isDualEnabled) Configuration.ORIENTATION_LANDSCAPE else conf.orientation
        }
        val current_window = window.window
        //Only update when the binder is alive
        if (current_window != null && current_window.attributes != null && current_window.attributes.token != null && current_window.attributes.token.isBinderAlive
        ) {
            val current_display = current_window.windowManager.defaultDisplay
            if (current_display.displayId != mLastDisplayId) {
//                mDualEnabled = false;
                mLastDisplayId = current_display.displayId
            }
            //            orientationLocker.saveCurrentWindowManager(current_window.getWindowManager());
            var isLandscape =
                current_display.rotation == Surface.ROTATION_90 || current_display.rotation == Surface.ROTATION_270
            if (isDualEnabled) isLandscape = true
            Log.i(TAG,
                "Current window: " + current_display.name + "; dualEnabled: " + isDualEnabled + "; isLandScape: " + isLandscape)
            newOrientation =
                if (isLandscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        val result = newOrientation != mOrientation
        Log.i(TAG, "New orientation: $newOrientation")
        mOrientation = newOrientation
        return result
    }

    private fun getKeyboardModeNum(origMode: Int, override: Int): Int {
        var origMode = origMode
        if (mNumKeyboardModes == 2 && origMode == 2) origMode = 1 // skip "compact". FIXME!
        var num = (origMode + override) % mNumKeyboardModes
        if (mNumKeyboardModes == 2 && num == 1) num = 2 // skip "compact". FIXME!
        return num
    }

    var updateKeyboardOptionsRecursive = false
    private fun updateKeyboardOptions() {
        if (!supportDualScreen()) {
            val isPortrait = isPortrait
            val kbMode: Int
            mNumKeyboardModes = 2
            kbMode = if (isPortrait) {
                getKeyboardModeNum(sKeyboardSettings.keyboardModePortrait,
                    mKeyboardModeOverridePortrait)
            } else {
                getKeyboardModeNum(sKeyboardSettings.keyboardModeLandscape,
                    mKeyboardModeOverrideLandscape)
            }
            // Convert overall keyboard height to per-row percentage
            val screenHeightPercent = if (isPortrait) mHeightPortrait else mHeightLandscape
            sKeyboardSettings.keyboardMode = kbMode
            sKeyboardSettings.keyboardHeightPercent = screenHeightPercent.toFloat()
            return
        }
        if (updateKeyboardOptionsRecursive) return
        updateKeyboardOptionsRecursive = true
        try {
            val isPortrait = isPortrait
            val kbMode: Int
            mNumKeyboardModes = 2
            kbMode = if (isPortrait) {
                getKeyboardModeNum(sKeyboardSettings.keyboardModePortrait,
                    mKeyboardModeOverridePortrait)
            } else {
                getKeyboardModeNum(sKeyboardSettings.keyboardModeLandscape,
                    mKeyboardModeOverrideLandscape)
            }
            // Convert overall keyboard height to per-row percentage
            Log.i(TAG, "updateKeyboardOptions: mDualEnabled=" + isDualEnabled)
            if (!isDualEnabled && checkForceLandscape(this)) setDualDisplay(false)
            val screenHeightPercent =
                if (isDualEnabled) 96.25f else (if (isPortrait) mHeightPortrait else mHeightLandscape).toFloat()
            sKeyboardSettings.keyboardMode = kbMode
            if (sKeyboardSettings.keyboardMode != kbMode
                || sKeyboardSettings.keyboardHeightPercent != screenHeightPercent
            ) {
                sKeyboardSettings.keyboardMode = kbMode
                sKeyboardSettings.keyboardHeightPercent = screenHeightPercent
                reloadKeyboards()
            }
        } finally {
            updateKeyboardOptionsRecursive = false
        }
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = getString(R.string.notification_channel_name)
            val description = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            channel.description = description
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(
                NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setNotification(visible: Boolean) {
        val ns = NOTIFICATION_SERVICE
        val mNotificationManager = getSystemService(ns) as NotificationManager
        if (visible && mNotificationReceiver == null) {
            createNotificationChannel()
            val text: CharSequence = "Keyboard notification enabled."
            val `when` = System.currentTimeMillis()

            // TODO: clean this up?
            mNotificationReceiver = NotificationReceiver(this)
            val pFilter = IntentFilter(NotificationReceiver.ACTION_SHOW)
            pFilter.addAction(NotificationReceiver.ACTION_SETTINGS)
            registerReceiver(mNotificationReceiver, pFilter)
            val notificationIntent = Intent(NotificationReceiver.ACTION_SHOW)
            val contentIntent =
                PendingIntent.getBroadcast(applicationContext, 1, notificationIntent, 0)
            //PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            val configIntent = Intent(NotificationReceiver.ACTION_SETTINGS)
            val configPendingIntent = PendingIntent.getBroadcast(
                applicationContext, 2, configIntent, 0)
            val title = "Show VHKeyboard"
            val body = "Select this to open the keyboard. Disable in settings."
            val mBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_hk_notification)
                .setColor(-0xddffbc)
                .setAutoCancel(false) //Make this notification automatically dismissed when the user touches it -> false.
                .setTicker(text)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(R.drawable.icon_hk_notification,
                    getString(R.string.notification_action_settings),
                    configPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

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
            val notificationManager = NotificationManagerCompat.from(this)

            // notificationId is a unique int for each notification that you must define
            notificationManager.notify(NOTIFICATION_ONGOING_ID, mBuilder.build())
        } else if (mNotificationReceiver != null) {
            mNotificationManager.cancel(NOTIFICATION_ONGOING_ID)
            unregisterReceiver(mNotificationReceiver)
            mNotificationReceiver = null
        }
    }

    private val isPortrait: Boolean
        private get() = mOrientation == Configuration.ORIENTATION_PORTRAIT

    override fun onDestroy() {
        if (surfaceDuoPaneManager != null) {
            surfaceDuoPaneManager!!.disconnect()
            surfaceDuoPaneManager = null
        }
        unregisterReceiver(mReceiver)
        if (mNotificationReceiver != null) {
            unregisterReceiver(mNotificationReceiver)
            mNotificationReceiver = null
        }
        unlockOrientationLocker()
        super.onDestroy()
        if (supportDualScreen()) {
            synchronized(glocker) {
                inputMethodAttachCnt -= selfInputMethodAttachCnt
                selfInputMethodAttachCnt = 0
            }
        }
    }

    override fun onConfigurationChanged(conf: Configuration) {
        if (!supportDualScreen()) {
            handleConfigurationChanged(conf)
            return
        }
        if (mLastConfiguration == null) mLastConfiguration = conf
        Log.i("PCKeyboard", "onConfigurationChanged() diff=" + mLastConfiguration!!.diff(conf))
        if (mLastConfiguration!!.diff(conf) == 0) {
            val currentId = ++mConfigurationPostId
            mDefaultHandler.postDelayed(Runnable {
                if (currentId != mConfigurationPostId) return@Runnable
                handleConfigurationChanged(conf)
            }, 50)
        } else {
            handleConfigurationChanged(conf)
        }
        mConfigurationChanging = true
        super.onConfigurationChanged(conf)
        mConfigurationChanging = false
    }

    fun handleConfigurationChanged(conf: Configuration) {
        Log.i("PCKeyboard", "onConfigurationChanged()")
        // If the system locale changes and is different from the saved
        // locale (mSystemLocale), then reload the input locale list from the
        // latin ime settings (shared prefs) and reset the input locale
        // to the first one.
        val systemLocale = conf.locale.toString()
        if (!TextUtils.equals(systemLocale, mSystemLocale)) {
            mSystemLocale = systemLocale
            if (mLanguageSwitcher != null) {
                mLanguageSwitcher!!.loadLocales(PreferenceManager
                    .getDefaultSharedPreferences(this))
                mLanguageSwitcher!!.setSystemLocale(conf.locale)
                toggleLanguage(true, true)
            } else {
                reloadKeyboards()
            }
        }
        // If orientation changed while predicting, commit the change
        if (updateOrientation(conf)) {
            val ic = currentInputConnection
            commitTyped(ic, true)
            ic?.finishComposingText() // For voice input
            if (!supportDualScreen()) mOrientation = conf.orientation
            reloadKeyboards()
            Log.i(TAG, "New orientation: reload")
        }
        mConfigurationChanging = true
        super.onConfigurationChanged(conf)
        mConfigurationChanging = false
    }

    override fun onCreateInputView(): View {
        setCandidatesViewShown(false) // Workaround for "already has a parent" when reconfiguring
        mKeyboardSwitcher.recreateInputView()
        mKeyboardSwitcher.makeKeyboards(true)
        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, 0, false)
        return mKeyboardSwitcher.rootView as View
    }

    override fun onCreateInputMethodInterface(): AbstractInputMethodImpl {
        return MyInputMethodImpl()
    }

    var mToken: IBinder? = null
    var selfInputMethodAttachCnt = 0

    inner class MyInputMethodImpl : InputMethodImpl() {
        private var isShown = false
        override fun attachToken(token: IBinder) {
            super.attachToken(token)
            Log.i(TAG, "attachToken $token")
            if (mToken == null) {
                mToken = token
            }
        }

        var showTimeMS: Long = 0
        var rotationOnShow = -999
        override fun bindInput(binding: InputBinding) {
            super.bindInput(binding)
            if (supportDualScreen()) {
                val currRotation = window.window!!.windowManager.defaultDisplay.rotation
                rotationOnShow = currRotation
                synchronized(glocker) {
                    ++inputMethodAttachCnt
                    ++selfInputMethodAttachCnt
                }
                Log.i(TAG,
                    this.hashCode().toString() + " (" + inputMethodAttachCnt + ") " + "bindInput")
                showTimeMS = System.currentTimeMillis()
                if (inputMethodAttachCnt == 1) {
                    if (isDualEnabled && !isShown) setDualDisplay(true)
                    isShown = true
                }
            }
        }

        override fun unbindInput() {
            if (!supportDualScreen()) {
                super.unbindInput()
                return
            }
            val currRotation = window.window!!.windowManager.defaultDisplay.rotation
            var thisAttachCnt = 0
            synchronized(glocker) {
                thisAttachCnt = --inputMethodAttachCnt
                --selfInputMethodAttachCnt
            }
            super.unbindInput()
            if (!isDualEnabled) return
            val finalThisAttachCnt = thisAttachCnt
            Handler().postDelayed(object : Runnable {
                override fun run() {
                    Log.i(TAG,
                        this.hashCode()
                            .toString() + " (" + finalThisAttachCnt + ") " + "unbindInput: " + isDualEnabled + ":" + currRotation + " vs " + rotationOnShow)
                    if (!isDualEnabled) return
                    var stillNeedKeyboard = finalThisAttachCnt > 0
                    if (finalThisAttachCnt == 0) {
                        if (!(isDualEnabled
                                    && (System.currentTimeMillis() <= showTimeMS + 500 || System.currentTimeMillis() <= lastRequestDualMilli + 1000)
                                    && rotationOnShow != currRotation && rotationOnShow == 0)
                        ) {
                            if (isShown) setDualDisplay(false)
                            isShown = false
                        } else {
                            stillNeedKeyboard = true
                        }
                    } else {
                    }
                    if (stillNeedKeyboard) {
                        Handler().postDelayed({
                            Log.d(TAG, "show keyboard again")
                            orientationLocker?.lock(Surface.ROTATION_90)
                            forceShowKeyboard()
                            if (!checkForceLandscape(this@LatinIME)) setDualDisplay(true)
                        }, 100)
                    }
                }
            }, 200)
        }

        private fun forceShowKeyboard() {
            if (RootCompat.isCompatibleRooted()) {
                RootCompat.rootRun("input keyevent 108", false)
            } else {
                //FIXME: this wont work
                val inputManager =
                    getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED,
                    InputMethodManager.HIDE_IMPLICIT_ONLY);
            }

        }

        override fun restartInput(ic: InputConnection?, attribute: EditorInfo?) {
            super.restartInput(ic, attribute)
            //            Log.i(TAG, this.hashCode() + " (" + inputMethodAttachCnt + ") " + "restartInput");
        }

        override fun startInput(ic: InputConnection?, attribute: EditorInfo?) {
            super.startInput(ic, attribute)
            if (!supportDualScreen()) return
            Log.i(TAG,
                this.hashCode().toString() + " (" + inputMethodAttachCnt + ") " + "startInput")
            if (isDualEnabled && inputMethodAttachCnt > 0 && !lastActionIsShow) {
                Handler().postDelayed({
                    Log.d(TAG, "show keyboard again")
                    orientationLocker!!.lock(Surface.ROTATION_90)
                    forceShowKeyboard()
                }, 100)
            }
        }

        override fun showSoftInput(flags: Int, resultReceiver: ResultReceiver?) {
            super.showSoftInput(flags, resultReceiver)
            savedOrientation?.also {
                mOrientationLocked = true
                Log.d(TAG, "reenable lock")
                orientationLocker?.lock(it)
                savedOrientation = null
            }
            if (!supportDualScreen()) return
            Log.i(TAG,
                this.hashCode().toString() + " (" + inputMethodAttachCnt + ") " + "showSoftInput")
            if (lastActionIsShow) {
                reloadKeyboards()
                Handler().postDelayed({ reloadKeyboards() }, 250)
            }
            lastActionIsShow = true
        }

        override fun hideSoftInput(flags: Int, resultReceiver: ResultReceiver?) {
            if (mStick) return
            if (mOrientationLocked) savedOrientation = unlockOrientationLocker()
            else savedOrientation = null
            super.hideSoftInput(flags, resultReceiver)
            if (!supportDualScreen()) return
            Log.i(TAG,
                this.hashCode().toString() + " (" + inputMethodAttachCnt + ") " + "hideSoftInput")
            lastActionIsShow = false
        }
    }

    fun updateSurfaceDuoKeyboardPanePosition() {
        val states = surfaceDuoPaneManager!!.paneStateForKeyboard()
        if (states!!.size >= 2) {
            val targetState = if (isPortrait) false else true
            for (state in surfaceDuoPaneManager!!.paneStateForKeyboard()!!) {
                if (state!!.inFocus == targetState) {
                    try {
                        if (isPortrait) surfaceDuoPaneManager!!.overrideKeyboardPane(state.paneId or 3)
                    } catch (e: Exception) {
                    }
                    try {
                        surfaceDuoPaneManager!!.overrideKeyboardPane(state.paneId)
                    } catch (e: Exception) {
                    }
                }
            }
        } else {
            try {
                surfaceDuoPaneManager!!.overrideKeyboardPane(states[0]!!.paneId)
            } catch (e: Exception) {
            }
        }
    }

    override fun onStartInputView(attribute: EditorInfo, restarting: Boolean) {
        if (SurfaceDuoUtils.isDeviceSurfaceDuo(packageManager)) {
            updateSurfaceDuoKeyboardPanePosition()
            mDefaultHandler.postDelayed({ updateSurfaceDuoKeyboardPanePosition() }, 50)
        }
        sKeyboardSettings.editorPackageName = attribute.packageName
        sKeyboardSettings.editorFieldName = attribute.fieldName
        sKeyboardSettings.editorFieldId = attribute.fieldId
        sKeyboardSettings.editorInputType = attribute.inputType

        //Log.i("PCKeyboard", "onStartInputView " + attribute + ", inputType= " + Integer.toHexString(attribute.inputType) + ", restarting=" + restarting);
        val inputView = mKeyboardSwitcher.inputView ?: return
        // In landscape mode, this method gets called without the input view
        // being created.
        if (mRefreshKeyboardRequired) {
            mRefreshKeyboardRequired = false
            toggleLanguage(true, true)
        }
        mKeyboardSwitcher.makeKeyboards(false)

        // Most such things we decide below in the switch statement, but we need to know
        // now whether this is a password text field, because we need to know now (before
        // the switch statement) whether we want to enable the voice button.
        val variation = attribute.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == 0xe0 /* EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD */) {
            if (attribute.inputType and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_CLASS_TEXT) {
//                mPasswordText = true;
            }
        }
        mModShiftLeft = false
        mModCtrlLeft = false
        mModAltLeft = false
        mModMetaLeft = false
        mModShiftRight = false
        mModCtrlRight = false
        mModAltRight = false
        mModMetaRight = false
        mModFn = false
        mModFn1 = false
        mModFn2 = false
        mEnteredText = null
        mKeyboardModeOverridePortrait = 0
        mKeyboardModeOverrideLandscape = 0
        sKeyboardSettings.useExtension = false
        when (attribute.inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_NUMBER, EditorInfo.TYPE_CLASS_DATETIME, EditorInfo.TYPE_CLASS_PHONE -> mKeyboardSwitcher.setKeyboardMode(
                KeyboardSwitcher.MODE_PHONE,
                attribute.imeOptions,
                false)
            EditorInfo.TYPE_CLASS_TEXT -> {
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                    attribute.imeOptions, false)
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL,
                        attribute.imeOptions, false)
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL,
                        attribute.imeOptions, false)
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM,
                        attribute.imeOptions, false)
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_WEB,
                        attribute.imeOptions, false)
                }
            }
            else -> mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                attribute.imeOptions, false)
        }
        inputView.closing()
        loadSettings()
        updateShiftKeyState(attribute)
        inputView.setPreviewEnabled(popupOn)
        inputView.setProximityCorrectionEnabled(true)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        mKeyboardSwitcher.inputView?.closing()
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText) {
        super.onUpdateExtractedText(token, text)
        val ic = currentInputConnection
    }

    override fun hideWindow() {
        if (mOptionsDialog != null && mOptionsDialog!!.isShowing) {
            mOptionsDialog!!.dismiss()
            mOptionsDialog = null
        }
        super.hideWindow()
    }

    override fun onDisplayCompletions(completions: Array<CompletionInfo?>?) {}

    override fun onEvaluateInputViewShown(): Boolean {
        val parent = super.onEvaluateInputViewShown()
        return mForceKeyboardOn || mStick || parent
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val dm = resources.displayMetrics
        val displayHeight = dm.heightPixels.toFloat()
        // If the display is more than X inches high, don't go to fullscreen
        // mode
        val dimen = resources.getDimension(
            R.dimen.max_height_for_fullscreen)
        return if (displayHeight > dimen || mFullscreenOverride) {
            false
        } else {
            super.onEvaluateFullscreenMode()
        }
    }

    val isKeyboardVisible: Boolean
        get() = mKeyboardSwitcher?.inputView?.isShown ?: false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) {
                if (mKeyboardSwitcher.inputView?.handleBack() == true) {
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (mVolUpAction != "none" && isKeyboardVisible) {
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (mVolDownAction != "none" && isKeyboardVisible) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
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
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (mVolUpAction != "none" && isKeyboardVisible) {
                return doSwipeAction(mVolUpAction)
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (mVolDownAction != "none" && isKeyboardVisible) {
                return doSwipeAction(mVolDownAction)
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun reloadKeyboards() {
        mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher)
        if (mKeyboardSwitcher.inputView != null
            && mKeyboardSwitcher.keyboardMode != KeyboardSwitcher.MODE_NONE
        ) {
            mKeyboardSwitcher.setVoiceMode(false, false)
        }
        updateKeyboardOptions()
        mKeyboardSwitcher.makeKeyboards(true)
    }

    private fun commitTyped(inputConnection: InputConnection?, manual: Boolean) {}
    override fun updateShiftKeyState(attr: EditorInfo?) {
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

    private fun showInputMethodPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
    }

    private fun onOptionKeyPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Input method selector is available as a button in the soft key area, so just launch
            // HK settings directly. This also works around the alert dialog being clipped
            // in Android O.
            val intent = Intent(this, LatinIMESettings::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            // Show an options menu with choices to change input method or open HK settings.
            if (!isShowingOptionDialog) {
                showOptionsMenu()
            }
        }
    }

    private fun onOptionKeyLongPressed() {
        if (!isShowingOptionDialog) {
            showInputMethodPicker()
        }
    }

    private val isShowingOptionDialog: Boolean
        private get() = mOptionsDialog != null && mOptionsDialog!!.isShowing
    private val metaState: Int
        private get() {
            var meta = 0
            if (mModShiftLeft) meta =
                meta or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
            if (mModShiftRight) meta =
                meta or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON)
            if (mModCtrlLeft) meta = meta or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            if (mModAltLeft) meta = meta or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
            if (mModMetaLeft) meta = meta or (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            if (mModCtrlRight) meta = meta or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON)
            if (mModAltRight) meta = meta or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON)
            if (mModMetaRight) meta = meta or (KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON)
            return meta
        }

    private fun sendKeyDown(ic: InputConnection?, key: Int, meta: Int) {
        val now = System.currentTimeMillis()
        ic?.sendKeyEvent(KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
        if (mNoIME)
            Thread.sleep(75)
    }

    private fun sendKeyUp(ic: InputConnection?, key: Int, meta: Int) {
        val now = System.currentTimeMillis()
        ic?.sendKeyEvent(KeyEvent(
            now, now, KeyEvent.ACTION_UP, key, 0, meta))
    }

    private fun sendModifiedKeyDownUp(key: Int) {
        val ic = currentInputConnection
        val meta = metaState
        sendModifierKeysDown()
        sendKeyDown(ic, key, meta)
        sendKeyUp(ic, key, meta)
        sendModifierKeysUp()
    }

    private fun sendShiftKey(ic: InputConnection, left: Boolean, isDown: Boolean) {
        val key = if (left) KeyEvent.KEYCODE_SHIFT_LEFT else KeyEvent.KEYCODE_SHIFT_RIGHT
        val meta =
            KeyEvent.META_SHIFT_ON or if (left) KeyEvent.META_SHIFT_LEFT_ON else KeyEvent.META_SHIFT_RIGHT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendCtrlKey(
        ic: InputConnection,
        left: Boolean,
        isDown: Boolean,
        chording: Boolean,
    ) {
        val key = if (left) KeyEvent.KEYCODE_CTRL_LEFT else KeyEvent.KEYCODE_CTRL_RIGHT
        val meta =
            KeyEvent.META_CTRL_ON or if (left) KeyEvent.META_CTRL_LEFT_ON else KeyEvent.META_CTRL_RIGHT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendAltKey(ic: InputConnection, left: Boolean, isDown: Boolean, chording: Boolean) {
        val key = if (left) KeyEvent.KEYCODE_ALT_LEFT else KeyEvent.KEYCODE_ALT_RIGHT
        val meta =
            KeyEvent.META_ALT_ON or if (left) KeyEvent.META_ALT_LEFT_ON else KeyEvent.META_ALT_RIGHT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendMetaKey(
        ic: InputConnection,
        left: Boolean,
        isDown: Boolean,
        chording: Boolean,
    ) {
        val key = if (left) KeyEvent.KEYCODE_META_LEFT else KeyEvent.KEYCODE_META_RIGHT
        val meta =
            KeyEvent.META_META_ON or if (left) KeyEvent.META_META_LEFT_ON else KeyEvent.META_META_RIGHT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendModifierKeysDown() {
        val ic = currentInputConnection
        if (mModShiftLeft && !mShiftKeyState.left.isChording) {
            sendShiftKey(ic, true, true)
        }
        if (mModCtrlLeft && !mCtrlKeyState.left.isChording) {
            sendCtrlKey(ic, true, true, false)
        }
        if (mModAltLeft && !mAltKeyState.left.isChording) {
            sendAltKey(ic, true, true, false)
        }
        if (mModMetaLeft && !mMetaKeyState.left.isChording) {
            sendMetaKey(ic, true, true, false)
        }
        if (mModShiftRight && !mShiftKeyState.right.isChording) {
            sendShiftKey(ic, false, true)
        }
        if (mModCtrlRight && !mCtrlKeyState.right.isChording) {
            sendCtrlKey(ic, false, true, false)
        }
        if (mModAltRight && !mAltKeyState.right.isChording) {
            sendAltKey(ic, false, true, false)
        }
        if (mModMetaRight && !mMetaKeyState.right.isChording) {
            sendMetaKey(ic, false, true, false)
        }
    }

    private fun handleFNModifierKeysUp(sendKey: Boolean) {
        if (mModFn1 && !mFn1KeyState.isChording) {
            setModFn1(false)
        }
        if (mModFn2 && !mFn2KeyState.isChording) {
            setModFn2(false)
        }
    }

    private fun handleModifierKeysUp(sendKey: Boolean) {
        val ic = currentInputConnection
        if (mModMetaLeft && !mMetaKeyState.left.isChording) {
            if (sendKey) sendMetaKey(ic, true, false, false)
            if (!mMetaKeyState.left.isChording) setModMeta(false, mModMetaRight)
        }
        if (mModAltLeft && !mAltKeyState.left.isChording) {
            if (sendKey) sendAltKey(ic, true, false, false)
            if (!mAltKeyState.left.isChording) setModAlt(false, mModAltRight)
        }
        if (mModCtrlLeft && !mCtrlKeyState.left.isChording) {
            if (sendKey) sendCtrlKey(ic, true, false, false)
            if (!mCtrlKeyState.left.isChording) setModCtrl(false, mModCtrlRight)
        }
        if (mModShiftLeft && !mShiftKeyState.left.isChording) {
            if (sendKey) sendShiftKey(ic, true, false)
            if (!mShiftKeyState.left.isChording) setModShift(false, mModShiftRight)
        }
        if (mModMetaRight && !mMetaKeyState.right.isChording) {
            if (sendKey) sendMetaKey(ic, false, false, false)
            if (!mMetaKeyState.right.isChording) setModMeta(mModMetaLeft, false)
        }
        if (mModAltRight && !mAltKeyState.right.isChording) {
            if (sendKey) sendAltKey(ic, false, false, false)
            if (!mAltKeyState.right.isChording) setModAlt(mModAltLeft, false)
        }
        if (mModCtrlRight && !mCtrlKeyState.right.isChording) {
            if (sendKey) sendCtrlKey(ic, false, false, false)
            if (!mCtrlKeyState.right.isChording) setModCtrl(mModCtrlLeft, false)
        }
        if (mModShiftRight && !mShiftKeyState.right.isChording) {
            if (sendKey) sendShiftKey(ic, false, false)
            if (!mShiftKeyState.right.isChording) setModShift(mModShiftLeft, false)
        }
    }

    private fun sendModifierKeysUp() {
        handleModifierKeysUp(true)
    }

    private fun sendSpecialKey(code: Int) {
        sendModifiedKeyDownUp(code)
        //        sendDownUpKeyEvents(code);
        handleModifierKeysUp(false)
    }

    fun sendModifiableKeyChar(ch: Char) {
        // Support modified key events
        if ((mNoIME || mModShiftLeft || mModShiftRight || mModCtrlLeft || mModAltLeft || mModMetaLeft || mModCtrlRight || mModAltRight || mModMetaRight) && ch.code > 0 && ch.code < 127) {
            val combinedCode = asciiToKeyCode[ch.code]
            if (combinedCode > 0) {
                val code = combinedCode and KF_MASK
                sendModifiedKeyDownUp(code)
                return
            }
        }
        sendKeyChar(ch)
    }

    private fun sendTab() {
        sendModifiedKeyDownUp(KeyEvent.KEYCODE_TAB)
    }

    private fun sendEscape() {
        sendModifiedKeyDownUp(111 /*KeyEvent.KEYCODE_ESCAPE */)
    }

    private fun processMultiKey(primaryCode: Int): Boolean {
        if (mDeadAccentBuffer.composeBuffer.length > 0) {
            //Log.i(TAG, "processMultiKey: pending DeadAccent, length=" + mDeadAccentBuffer.composeBuffer.length());
            mDeadAccentBuffer.execute(primaryCode)
            mDeadAccentBuffer.clear()
            return true
        }
        if (mComposeMode) {
            mComposeMode = mComposeBuffer.execute(primaryCode)
            return true
        }
        return false
    }

    // Implementation of KeyboardViewListener
    override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) {
        val `when` = SystemClock.uptimeMillis()
        if (primaryCode != Keyboard.KEYCODE_DELETE
            || `when` > mLastKeyTime + QUICK_PRESS
        ) {
            mDeleteCount = 0
        }
        mLastKeyTime = `when`
        val distinctMultiTouch = mKeyboardSwitcher
            .hasDistinctMultitouch()
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                handleFNModifierKeysUp(false)
                if (!processMultiKey(primaryCode)) {
                    handleBackspace()
                    mDeleteCount++
                }
            }
            LatinKeyboardView.KEYCODE_SHIFT_LEFT ->
                if (!distinctMultiTouch) setModShift(!mModShiftLeft, mModShiftRight)
            LatinKeyboardView.KEYCODE_SHIFT_RIGHT ->
                if (!distinctMultiTouch) setModShift(mModShiftLeft, !mModShiftRight)
            Keyboard.KEYCODE_MODE_CHANGE ->
                // Symbol key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch) changeKeyboardMode()
            LatinKeyboardView.KEYCODE_CTRL_LEFT ->
                if (!distinctMultiTouch) setModCtrl(!mModCtrlLeft, mModCtrlRight)
            LatinKeyboardView.KEYCODE_CTRL_RIGHT ->
                if (!distinctMultiTouch) setModCtrl(mModCtrlLeft, !mModCtrlRight)
            LatinKeyboardView.KEYCODE_ALT_LEFT ->
                if (!distinctMultiTouch) setModAlt(!mModAltLeft, mModAltRight)
            LatinKeyboardView.KEYCODE_ALT_RIGHT ->
                if (!distinctMultiTouch) setModAlt(mModAltLeft, !mModAltRight)
            LatinKeyboardView.KEYCODE_META_LEFT ->
                if (!distinctMultiTouch) setModMeta(!mModMetaLeft, mModMetaRight)
            LatinKeyboardView.KEYCODE_META_RIGHT ->
                if (!distinctMultiTouch) setModMeta(mModMetaLeft, !mModMetaRight)
            LatinKeyboardView.KEYCODE_FN_1 ->
                if (!distinctMultiTouch) setModFn1(!mModFn1)
            LatinKeyboardView.KEYCODE_FN_2 ->
                if (!distinctMultiTouch) setModFn2(!mModFn2)
            Keyboard.KEYCODE_STICK -> {
                handleFNModifierKeysUp(false)
                setSticky(!mStick)
            }
            Keyboard.KEYCODE_NOIME -> {
                handleFNModifierKeysUp(false)
                setNoIME(!mNoIME)
            }
            LatinKeyboardView.KEYCODE_ORIENTATION_LOCK -> {
                handleFNModifierKeysUp(false)
                setOrientationLock(!mOrientationLocked)
            }
            Keyboard.KEYCODE_CANCEL -> if (!isShowingOptionDialog) {
                handleClose()
            }
            LatinKeyboardView.KEYCODE_FULLSCREEN_DUAL -> setDualDisplay(null)
            LatinKeyboardView.KEYCODE_OPTIONS -> onOptionKeyPressed()
            LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS -> onOptionKeyLongPressed()
            LatinKeyboardView.KEYCODE_COMPOSE -> {
                mComposeMode = !mComposeMode
                mComposeBuffer.clear()
            }
            LatinKeyboardView.KEYCODE_NEXT_LANGUAGE -> toggleLanguage(false, true)
            LatinKeyboardView.KEYCODE_PREV_LANGUAGE -> toggleLanguage(false, false)
            9 -> {
                if (!processMultiKey(primaryCode)) {
                    sendTab()
                }
            }
            LatinKeyboardView.KEYCODE_ESCAPE -> {
                handleFNModifierKeysUp(false)
                if (!processMultiKey(primaryCode)) {
                    sendEscape()
                }
            }
            LatinKeyboardView.KEYCODE_DPAD_UP, LatinKeyboardView.KEYCODE_DPAD_DOWN, LatinKeyboardView.KEYCODE_DPAD_LEFT, LatinKeyboardView.KEYCODE_DPAD_RIGHT, LatinKeyboardView.KEYCODE_DPAD_CENTER, LatinKeyboardView.KEYCODE_HOME, LatinKeyboardView.KEYCODE_END, LatinKeyboardView.KEYCODE_PAGE_UP, LatinKeyboardView.KEYCODE_PAGE_DOWN, LatinKeyboardView.KEYCODE_FKEY_F1, LatinKeyboardView.KEYCODE_FKEY_F2, LatinKeyboardView.KEYCODE_FKEY_F3, LatinKeyboardView.KEYCODE_FKEY_F4, LatinKeyboardView.KEYCODE_FKEY_F5, LatinKeyboardView.KEYCODE_FKEY_F6, LatinKeyboardView.KEYCODE_FKEY_F7, LatinKeyboardView.KEYCODE_FKEY_F8, LatinKeyboardView.KEYCODE_FKEY_F9, LatinKeyboardView.KEYCODE_FKEY_F10, LatinKeyboardView.KEYCODE_FKEY_F11, LatinKeyboardView.KEYCODE_FKEY_F12, LatinKeyboardView.KEYCODE_FORWARD_DEL, LatinKeyboardView.KEYCODE_INSERT, LatinKeyboardView.KEYCODE_SYSRQ, LatinKeyboardView.KEYCODE_BREAK, LatinKeyboardView.KEYCODE_NUM_LOCK, LatinKeyboardView.KEYCODE_SCROLL_LOCK -> {
                if (!processMultiKey(primaryCode)) {
                    // send as plain keys, or as escape sequence if needed
                    sendSpecialKey(-primaryCode)
                    handleFNModifierKeysUp(false)
                }
            }
            else -> {
                if (!mComposeMode && mDeadKeysActive && Character.getType(primaryCode) == Character.NON_SPACING_MARK.toInt()) {
                    //Log.i(TAG, "possible dead character: " + primaryCode);
                    if (mDeadAccentBuffer.execute(primaryCode)) {
                        updateShiftKeyState(currentInputEditorInfo)
                    }
                } else if (processMultiKey(primaryCode)) {
                    handleFNModifierKeysUp(false)
                } else {
                    RingCharBuffer.instance.push(primaryCode.toChar(), x, y)
                    handleCharacter(primaryCode, keyCodes)
                    handleFNModifierKeysUp(false)
                }
            }
        }
        mKeyboardSwitcher.onKey(primaryCode)
        // Reset after any single keystroke
        mEnteredText = null
        //mDeadAccentBuffer.clear();  // FIXME
    }

    private fun setOrientationLock(enable: Boolean) {
        Log.d(TAG, "setOrientationLock $enable")
        if (enable) {
            val locker = orientationLocker
            if (locker == null) {
//                Toast.makeText(applicationContext,
//                    R.string.orientation_locker_not_supported,
//                    Toast.LENGTH_SHORT).show()
                return
            }
            if (!locker.lock()) {
                Toast.makeText(applicationContext,
                    R.string.orientation_lock_failed,
                    Toast.LENGTH_SHORT).show()
            } else {
                mOrientationLocked = true
                Toast.makeText(applicationContext,
                    R.string.orientation_locked,
                    Toast.LENGTH_SHORT).show()
            }
        } else {
            mOrientationLocked = false
            unlockOrientationLocker()
            Toast.makeText(applicationContext,
                R.string.orientation_unlocked,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun setSticky(enable: Boolean) {
        if (enable == mStick) return
        mStick = enable
        mKeyboardSwitcher.setSticky(mStick)
        Toast.makeText(baseContext,
            if (mStick) R.string.sticky_enabled else R.string.sticky_disabled,
            Toast.LENGTH_SHORT).show()
    }

    private fun setNoIME(enable: Boolean) {
        if (enable == mNoIME) return
        mNoIME = enable
        mKeyboardSwitcher.setNoIME(mNoIME)
        Toast.makeText(baseContext,
            if (mNoIME) R.string.noime_enabled else R.string.noime_disabled,
            Toast.LENGTH_SHORT).show()
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_NOIME, mNoIME).apply()
    }

    private fun setDualDisplay(newState: Boolean?) {
        if (!supportDualScreen()) return
        cancelOrientationUnlock()
        var enabled = false
        if (!checkForceLandscape(this) && (newState == null || newState)) {
            Log.d(TAG, "set lastRequestDualMilli")
            lastRequestDualMilli = System.currentTimeMillis()
            orientationLocker!!.saveCurrentWindowManager(window.window, window.window!!
                .windowManager)
        }
        if (newState == null) {
            isDualEnabled = ToggleFullScreenIME.Toggle(this)
            enabled = isDualEnabled
        } else {
            try {
                enabled = newState
                // comment this helps the keyboard from recreating
//                ToggleFullScreenIME.ToggleSimply(this, newState);
            } catch (ex: Exception) {
            }
        }
        if (enabled) {
            Log.d(TAG, "(dual) orientation lock")
            orientationLocker!!.lock(Surface.ROTATION_90)
        } else {
            postOrientationUnlock(1000)
        }
    }

    private var unlockToken = 0

    fun cancelOrientationUnlock() {
        ++unlockToken
    }

    fun postOrientationUnlock(delay: Int) {
        val currToken = ++unlockToken
        mDefaultHandler.postDelayed({
            if (currToken == unlockToken) {
                unlockOrientationLocker()
            }
        }, delay.toLong())
    }

    fun unlockOrientationLocker(): Int? {
        return mOrientationLockerImpl?.unlock()
    }


    override fun onText(text: CharSequence?) {
        //mDeadAccentBuffer.clear();  // FIXME
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        ic.endBatchEdit()
        updateShiftKeyState(currentInputEditorInfo)
        mKeyboardSwitcher.onKey(0) // dummy key code.
        mEnteredText = text
    }

    override fun onCancel() {
        // User released a finger outside any key
        mKeyboardSwitcher.onCancelInput()
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (mEnteredText != null
            && sameAsTextBeforeCursor(ic, mEnteredText!!)
        ) {
            ic.deleteSurroundingText(mEnteredText!!.length, 0)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            if (mDeleteCount > DELETE_ACCELERATE_AT) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
        ic.endBatchEdit()
    }

    private fun setModShift(left: Boolean, right: Boolean) {
        mKeyboardSwitcher.setShiftIndicator(left, right)
        mModShiftLeft = left
        mModShiftRight = right
    }

    private fun setModCtrl(left: Boolean, right: Boolean) {
        mKeyboardSwitcher.setCtrlIndicator(left, right)
        mModCtrlLeft = left
        mModCtrlRight = right
    }

    private fun setModAlt(left: Boolean, right: Boolean) {
        mKeyboardSwitcher.setAltIndicator(left, right)
        mModAltLeft = left
        mModAltRight = right
    }

    private fun setModMeta(left: Boolean, right: Boolean) {
        mKeyboardSwitcher.setMetaIndicator(left, right)
        mModMetaLeft = left
        mModMetaRight = right
    }

    private fun setModFn1(active: Boolean) {
        mKeyboardSwitcher.setFn1Indicator(active)
        mModFn1 = active
    }

    private fun setModFn2(active: Boolean) {
        mKeyboardSwitcher.setFn2Indicator(active)
        mModFn2 = active
    }

    private fun handleCharacter(primaryCode: Int, keyCodes: IntArray?) {
        if (mModShiftLeft || mModShiftRight || mModCtrlLeft || mModAltLeft || mModMetaLeft || mModCtrlRight || mModAltRight || mModMetaRight) {
            commitTyped(currentInputConnection, true) // sets mPredicting=false
        }
        sendModifiableKeyChar(primaryCode.toChar())
        updateShiftKeyState(currentInputEditorInfo)
    }

    private fun handleClose() {
        commitTyped(currentInputConnection, true)
        requestHideSelf(0)
        if (mKeyboardSwitcher != null) {
            val inputView = mKeyboardSwitcher.inputView
            inputView?.closing()
        }
    }

    private fun sameAsTextBeforeCursor(ic: InputConnection, text: CharSequence): Boolean {
        val beforeText = ic.getTextBeforeCursor(text.length, 0)
        return TextUtils.equals(text, beforeText)
    }

    fun toggleLanguage(reset: Boolean, next: Boolean) {
        if (reset) {
            mLanguageSwitcher!!.reset()
        } else {
            if (next) {
                mLanguageSwitcher!!.next()
            } else {
                mLanguageSwitcher!!.prev()
            }
        }
        val currentKeyboardMode = mKeyboardSwitcher.keyboardMode
        reloadKeyboards()
        mKeyboardSwitcher.makeKeyboards(true)
        mKeyboardSwitcher.setKeyboardMode(currentKeyboardMode, 0,
            false)
        mLanguageSwitcher!!.persist()
        mDeadKeysActive = mLanguageSwitcher!!.allowDeadKeys()
        updateShiftKeyState(currentInputEditorInfo)
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String,
    ) {
        Log.i("PCKeyboard", "onSharedPreferenceChanged()")
        var needReload = false
        val res = resources

        // Apply globally handled shared prefs
        sKeyboardSettings.sharedPreferenceChanged(sharedPreferences, key)
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_NEED_RELOAD)) {
            needReload = true
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RECREATE_INPUT_VIEW)) {
            mKeyboardSwitcher.recreateInputView()
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_MODE_OVERRIDE)) {
            mKeyboardModeOverrideLandscape = 0
            mKeyboardModeOverridePortrait = 0
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_KEYBOARDS)) {
            toggleLanguage(true, true)
        }

        val unhandledFlags = sKeyboardSettings.unhandledFlags()
        if (unhandledFlags != GlobalKeyboardSettings.FLAG_PREF_NONE) {
            Log.w(TAG, "Not all flag settings handled, remaining=$unhandledFlags")
        }
        if (PREF_SELECTED_LANGUAGES == key) {
            mLanguageSwitcher!!.loadLocales(sharedPreferences)
            mRefreshKeyboardRequired = true
        } else if (PREF_FULLSCREEN_OVERRIDE == key) {
            mFullscreenOverride = sharedPreferences.getBoolean(
                PREF_FULLSCREEN_OVERRIDE, res
                    .getBoolean(R.bool.default_fullscreen_override))
            needReload = true
        } else if (PREF_FORCE_KEYBOARD_ON == key) {
            mForceKeyboardOn = sharedPreferences.getBoolean(
                PREF_FORCE_KEYBOARD_ON, res
                    .getBoolean(R.bool.default_force_keyboard_on))
            needReload = true
        } else if (PREF_KEYBOARD_NOTIFICATION == key) {
            mKeyboardNotification = sharedPreferences.getBoolean(
                PREF_KEYBOARD_NOTIFICATION, res
                    .getBoolean(R.bool.default_keyboard_notification))
            setNotification(mKeyboardNotification)
        } else if (PREF_HEIGHT_PORTRAIT == key) {
            mHeightPortrait = getHeight(sharedPreferences,
                PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait))
            needReload = true
        } else if (PREF_HEIGHT_LANDSCAPE == key) {
            mHeightLandscape = getHeight(sharedPreferences,
                PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape))
            needReload = true
        } else if (PREF_HINT_MODE == key) {
            sKeyboardSettings.hintMode = sharedPreferences.getString(PREF_HINT_MODE,
                res.getString(R.string.default_hint_mode))!!.toInt()
            needReload = true
        } else if (PREF_LONGPRESS_TIMEOUT == key) {
            sKeyboardSettings.longpressTimeout =
                getPrefInt(sharedPreferences, PREF_LONGPRESS_TIMEOUT,
                    res.getString(R.string.default_long_press_duration))
        } else if (PREF_RENDER_MODE == key) {
            sKeyboardSettings.renderMode = getPrefInt(sharedPreferences, PREF_RENDER_MODE,
                res.getString(R.string.default_render_mode))
            needReload = true
        } else if (PREF_SWIPE_UP == key) {
            mSwipeUpAction =
                sharedPreferences.getString(PREF_SWIPE_UP, res.getString(R.string.default_swipe_up))
        } else if (PREF_SWIPE_DOWN == key) {
            mSwipeDownAction = sharedPreferences.getString(PREF_SWIPE_DOWN,
                res.getString(R.string.default_swipe_down))
        } else if (PREF_SWIPE_LEFT == key) {
            mSwipeLeftAction = sharedPreferences.getString(PREF_SWIPE_LEFT,
                res.getString(R.string.default_swipe_left))
        } else if (PREF_SWIPE_RIGHT == key) {
            mSwipeRightAction = sharedPreferences.getString(PREF_SWIPE_RIGHT,
                res.getString(R.string.default_swipe_right))
        } else if (PREF_VOL_UP == key) {
            mVolUpAction =
                sharedPreferences.getString(PREF_VOL_UP, res.getString(R.string.default_vol_up))
        } else if (PREF_VOL_DOWN == key) {
            mVolDownAction =
                sharedPreferences.getString(PREF_VOL_DOWN, res.getString(R.string.default_vol_down))
        } else if (PREF_VIBRATE_LEN == key) {
            mVibrateLen = getPrefInt(sharedPreferences,
                PREF_VIBRATE_LEN,
                resources.getString(R.string.vibrate_duration_ms))
        } else if (PREF_NOIME == key) {
            setNoIME(sharedPreferences.getBoolean(PREF_NOIME, false))
        }
        updateKeyboardOptions()
        if (needReload) {
            mKeyboardSwitcher.makeKeyboards(true)
        }
    }

    private fun doSwipeAction(action: String?): Boolean {
        //Log.i(TAG, "doSwipeAction + " + action);
        if (action == null || action == "" || action == "none") {
            return false
        } else if (action == "close") {
            handleClose()
        } else if (action == "settings") {
            launchSettings()
        } else if (action == "lang_prev") {
            toggleLanguage(false, false)
        } else if (action == "lang_next") {
            toggleLanguage(false, true)
        } else if (action == "full_mode") {
            if (isPortrait) {
                mKeyboardModeOverridePortrait =
                    (mKeyboardModeOverridePortrait + 1) % mNumKeyboardModes
            } else {
                mKeyboardModeOverrideLandscape =
                    (mKeyboardModeOverrideLandscape + 1) % mNumKeyboardModes
            }
            toggleLanguage(true, true)
        } else if (action == "extension") {
            sKeyboardSettings.useExtension = !sKeyboardSettings.useExtension
            reloadKeyboards()
        } else if (action == "height_up") {
            if (isPortrait) {
                mHeightPortrait += 5
                if (mHeightPortrait > 70) mHeightPortrait = 70
            } else {
                mHeightLandscape += 5
                if (mHeightLandscape > 70) mHeightLandscape = 70
            }
            toggleLanguage(true, true)
        } else if (action == "height_down") {
            if (isPortrait) {
                mHeightPortrait -= 5
                if (mHeightPortrait < 15) mHeightPortrait = 15
            } else {
                mHeightLandscape -= 5
                if (mHeightLandscape < 15) mHeightLandscape = 15
            }
            toggleLanguage(true, true)
        } else {
            Log.i(TAG, "Unsupported swipe action config: $action")
        }
        return true
    }

    override fun swipeRight(): Boolean {
        return doSwipeAction(mSwipeRightAction)
    }

    override fun swipeLeft(): Boolean {
        return doSwipeAction(mSwipeLeftAction)
    }

    override fun swipeDown(): Boolean {
        return doSwipeAction(mSwipeDownAction)
    }

    override fun swipeUp(): Boolean {
        return doSwipeAction(mSwipeUpAction)
    }

    override fun onPress(primaryCode: Int) {
        val ic = currentInputConnection
        if (mKeyboardSwitcher.isVibrateAndSoundFeedbackRequired) {
            vibrate()
            playKeyClick(primaryCode)
        }
        val distinctMultiTouch = mKeyboardSwitcher
            .hasDistinctMultitouch()
        if (distinctMultiTouch
            && primaryCode == Keyboard.KEYCODE_MODE_CHANGE
        ) {
            changeKeyboardMode()
            mSymbolKeyState.onPress()
            mKeyboardSwitcher.setAutoModeSwitchStateMomentary()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_LEFT
        ) {
            if (mModShiftLeft) {
                setModShift(false, mModShiftRight)
                mShiftKeyState.left.onRelease()
            } else {
                setModShift(true, mModShiftRight)
                mShiftKeyState.left.onPress()
                sendShiftKey(ic, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT
        ) {
            if (mModCtrlLeft) {
                setModCtrl(false, mModCtrlRight)
                mCtrlKeyState.left.onRelease()
            } else {
                setModCtrl(true, mModCtrlRight)
                mCtrlKeyState.left.onPress()
                sendCtrlKey(ic, true, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT
        ) {
            if (mModAltLeft) {
                setModAlt(false, mModAltRight)
                mAltKeyState.left.onRelease()
            } else {
                setModAlt(true, mModAltRight)
                mAltKeyState.left.onPress()
                sendAltKey(ic, true, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_META_LEFT
        ) {
            if (mModMetaLeft) {
                setModMeta(false, mModMetaRight)
                mMetaKeyState.left.onRelease()
            } else {
                setModMeta(true, mModMetaRight)
                mMetaKeyState.left.onPress()
                sendMetaKey(ic, true, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_RIGHT
        ) {
            if (mModShiftRight) {
                setModShift(mModShiftLeft, false)
                mShiftKeyState.right.onRelease()
            } else {
                setModShift(mModShiftLeft, true)
                mShiftKeyState.right.onPress()
                sendShiftKey(ic, false, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_CTRL_RIGHT
        ) {
            if (mModCtrlRight) {
                setModCtrl(mModCtrlLeft, false)
                mCtrlKeyState.right.onRelease()
            } else {
                setModCtrl(mModCtrlLeft, true)
                mCtrlKeyState.right.onPress()
                sendCtrlKey(ic, false, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_ALT_RIGHT
        ) {
            if (mModAltRight) {
                setModAlt(mModAltLeft, false)
                mAltKeyState.right.onRelease()
            } else {
                setModAlt(mModAltLeft, true)
                mAltKeyState.right.onPress()
                sendAltKey(ic, false, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_META_RIGHT
        ) {
            if (mModMetaRight) {
                setModMeta(mModMetaLeft, false)
                mMetaKeyState.right.onRelease()
            } else {
                setModMeta(mModMetaLeft, true)
                mMetaKeyState.right.onPress()
                sendMetaKey(ic, false, true, true)
            }
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_FN_1
        ) {
            setModFn1(!mModFn1)
            mFn1KeyState.onPress()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_FN_2
        ) {
            setModFn2(!mModFn2)
            mFn2KeyState.onPress()
        } else {
            mShiftKeyState.left.onOtherKeyPressed()
            mShiftKeyState.right.onOtherKeyPressed()
            mCtrlKeyState.left.onOtherKeyPressed()
            mCtrlKeyState.right.onOtherKeyPressed()
            mAltKeyState.left.onOtherKeyPressed()
            mAltKeyState.right.onOtherKeyPressed()
            mMetaKeyState.left.onOtherKeyPressed()
            mMetaKeyState.right.onOtherKeyPressed()
            mSymbolKeyState.onOtherKeyPressed()
            mFnKeyState.onOtherKeyPressed()
            mFn1KeyState.onOtherKeyPressed()
            mFn2KeyState.onOtherKeyPressed()
        }
    }

    override fun onRelease(primaryCode: Int) {
        // Reset any drag flags in the keyboard
        (mKeyboardSwitcher.inputView?.getKeyboard() as? LatinKeyboard)?.keyReleased()
        // vibrate();
        val distinctMultiTouch = mKeyboardSwitcher
            .hasDistinctMultitouch()
        val ic = currentInputConnection
        if (distinctMultiTouch
            && primaryCode == Keyboard.KEYCODE_MODE_CHANGE
        ) {
            // Snap back to the previous keyboard mode if the user chords the
            // mode change key and
            // other key, then released the mode change key.
            if (mKeyboardSwitcher.isInChordingAutoModeSwitchState) changeKeyboardMode()
            mSymbolKeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT
        ) {
            if (mCtrlKeyState.left.isChording) {
                setModCtrl(false, mModCtrlRight)
            }
            sendCtrlKey(ic, true, false, true)
            mCtrlKeyState.left.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_LEFT
        ) {
            if (mShiftKeyState.left.isChording) {
                setModShift(false, mModShiftRight)
            }
            sendShiftKey(ic, true, false)
            mShiftKeyState.left.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT
        ) {
            if (mAltKeyState.left.isChording) {
                setModAlt(false, mModAltRight)
            }
            sendAltKey(ic, true, false, true)
            mAltKeyState.left.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_META_LEFT
        ) {
            if (mMetaKeyState.left.isChording) {
                setModMeta(false, mModMetaRight)
            }
            sendMetaKey(ic, true, false, true)
            mMetaKeyState.left.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_SHIFT_RIGHT
        ) {
            if (mShiftKeyState.right.isChording) {
                setModShift(mModShiftLeft, false)
            }
            sendShiftKey(ic, false, false)
            mShiftKeyState.right.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_CTRL_RIGHT
        ) {
            if (mCtrlKeyState.right.isChording) {
                setModCtrl(mModCtrlLeft, false)
            }
            sendCtrlKey(ic, false, false, true)
            mCtrlKeyState.right.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_ALT_RIGHT
        ) {
            if (mAltKeyState.right.isChording) {
                setModAlt(mModAltLeft, false)
            }
            sendAltKey(ic, false, false, true)
            mAltKeyState.right.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_META_RIGHT
        ) {
            if (mMetaKeyState.right.isChording) {
                setModMeta(mModMetaLeft, false)
            }
            sendMetaKey(ic, false, false, true)
            mMetaKeyState.right.onRelease()
            //        } else if (distinctMultiTouch
//                && primaryCode == LatinKeyboardView.KEYCODE_FN) {
//            if (mFnKeyState.isChording()) {
//                setModFn(false);
//            }
//            mFnKeyState.onRelease();
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_FN_1
        ) {
            if (mFn1KeyState.isChording) {
                setModFn1(false)
            }
            mFn1KeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_FN_2
        ) {
            if (mFn2KeyState.isChording) {
                setModFn2(false)
            }
            mFn2KeyState.onRelease()
        }
        // WARNING: Adding a chording modifier key? Make sure you also
        // edit PointerTracker.isModifierInternal(), otherwise it will
        // force a release event instead of chording.
    }

    // receive ringer mode changes to detect silent mode
    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateRingerMode()
        }
    }

    // update flags for silent mode
    private fun updateRingerMode() {
        if (mAudioManager == null) {
            mAudioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        }
        if (mAudioManager != null) {
            mSilentMode = mAudioManager!!.ringerMode != AudioManager.RINGER_MODE_NORMAL
        }
    }// Channel is silent, won't get audio
    // Cap at 1.0
    // Set absolute volume, treating the percentage as a logarithmic control
    //Log.i(TAG, "getKeyClickVolume absolute, target=" + targetVol + " amp=" + vol);
// TODO(klausw): on some devices the media volume controls the click volume?
    // If that's the case, try to set a relative target volume.
    //Log.i(TAG, "getKeyClickVolume relative, media vol=" + mediaVol + "/" + mediaMax);
// See click_method_values in strings.xml
    // shouldn't happen

    // The volume calculations are poorly documented, this is the closest I could
    // find for explaining volume conversions:
    // http://developer.android.com/reference/android/media/MediaPlayer.html#setAuxEffectSendLevel(float)
    //
    //   Note that the passed level value is a raw scalar. UI controls should be scaled logarithmically:
    //   the gain applied by audio framework ranges from -72dB to 0dB, so an appropriate conversion 
    //   from linear UI input x to level is: x == 0 -> level = 0 0 < x <= R -> level = 10^(72*(x-R)/20/R)
    private val keyClickVolume: Float
        private get() {
            if (mAudioManager == null) return 0.0f // shouldn't happen

            // The volume calculations are poorly documented, this is the closest I could
            // find for explaining volume conversions:
            // http://developer.android.com/reference/android/media/MediaPlayer.html#setAuxEffectSendLevel(float)
            //
            //   Note that the passed level value is a raw scalar. UI controls should be scaled logarithmically:
            //   the gain applied by audio framework ranges from -72dB to 0dB, so an appropriate conversion 
            //   from linear UI input x to level is: x == 0 -> level = 0 0 < x <= R -> level = 10^(72*(x-R)/20/R)
            val method =
                sKeyboardSettings.keyClickMethod // See click_method_values in strings.xml
            if (method == 0) return FX_VOLUME
            var targetVol = sKeyboardSettings.keyClickVolume
            if (method > 1) {
                // TODO(klausw): on some devices the media volume controls the click volume?
                // If that's the case, try to set a relative target volume.
                val mediaMax =
                    mAudioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val mediaVol =
                    mAudioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
                //Log.i(TAG, "getKeyClickVolume relative, media vol=" + mediaVol + "/" + mediaMax);
                val channelVol = mediaVol.toFloat() / mediaMax
                if (method == 2) {
                    targetVol *= channelVol
                } else if (method == 3) {
                    if (channelVol == 0f) return 0.0f // Channel is silent, won't get audio
                    targetVol = Math.min(targetVol / channelVol, 1.0f) // Cap at 1.0
                }
            }
            // Set absolute volume, treating the percentage as a logarithmic control
            //Log.i(TAG, "getKeyClickVolume absolute, target=" + targetVol + " amp=" + vol);
            return Math.pow(10.0, (FX_VOLUME_RANGE_DB * (targetVol - 1) / 20).toDouble()).toFloat()
        }

    private fun playKeyClick(primaryCode: Int) {
        // if mAudioManager is null, we don't have the ringer state yet
        // mAudioManager will be set by updateRingerMode
        if (mAudioManager == null) {
            if (mKeyboardSwitcher.inputView != null) {
                updateRingerMode()
            }
        }
        if (mSoundOn && !mSilentMode) {
            // FIXME: Volume and enable should come from UI settings
            // FIXME: These should be triggered after auto-repeat logic
            var sound = AudioManager.FX_KEYPRESS_STANDARD
            when (primaryCode) {
                Keyboard.KEYCODE_DELETE -> sound = AudioManager.FX_KEYPRESS_DELETE
                ASCII_ENTER -> sound = AudioManager.FX_KEYPRESS_RETURN
                ASCII_SPACE -> sound = AudioManager.FX_KEYPRESS_SPACEBAR
            }
            mAudioManager!!.playSoundEffect(sound, keyClickVolume)
        }
    }

    private fun vibrate() {
        if (!mVibrateOn) {
            return
        }
        vibrate(mVibrateLen)
    }

    fun vibrate(len: Int) {
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.apply {
            vibrate(len.toLong())
            return
        }
        mKeyboardSwitcher.inputView?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    protected fun launchSettings(
        settingsClass: Class<out PreferenceActivity?>? = LatinIMESettings::class.java,
    ) {
        handleClose()
        val intent = Intent()
        intent.setClass(this@LatinIME, settingsClass!!)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun loadSettings() {
        // Get the settings preferences
        val sp = PreferenceManager
            .getDefaultSharedPreferences(this)
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false)
        mVibrateLen =
            getPrefInt(sp, PREF_VIBRATE_LEN, resources.getString(R.string.vibrate_duration_ms))
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false)
        popupOn = sp.getBoolean(PREF_POPUP_ON, mResources
            .getBoolean(R.bool.default_popup_preview))
        mAutoCapPref = sp.getBoolean(PREF_AUTO_CAP, resources.getBoolean(
            R.bool.default_auto_cap))
        val voiceMode = sp.getString(PREF_VOICE_MODE,
            getString(R.string.voice_mode_main))
        mLanguageSwitcher!!.loadLocales(sp)
        mDeadKeysActive = mLanguageSwitcher!!.allowDeadKeys()
    }

    private fun showOptionsMenu() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(true)
        builder.setIcon(R.drawable.ic_dialog_keyboard)
        builder.setNegativeButton(android.R.string.cancel, null)
        val itemSettings: CharSequence = getString(R.string.english_ime_settings)
        val itemInputMethod: CharSequence = getString(R.string.selectInputMethod)
        builder.setItems(arrayOf(itemInputMethod, itemSettings)
        ) { di, position ->
            di.dismiss()
            when (position) {
                POS_SETTINGS -> launchSettings()
                POS_METHOD -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        }
        builder.setTitle(mResources
            .getString(R.string.english_ime_input_options))
        mOptionsDialog = builder.create()
        val window = mOptionsDialog!!.getWindow()
        val lp = window!!.attributes
        lp.token = mKeyboardSwitcher.inputView!!.windowToken
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        mOptionsDialog!!.show()
    }

    fun changeKeyboardMode() {
        val switcher = mKeyboardSwitcher
        switcher!!.toggleSymbols()
        updateShiftKeyState(currentInputEditorInfo)
    }

    override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<String>) {
        super.dump(fd, fout, args)
        val p: Printer = PrintWriterPrinter(fout)
        p.println("LatinIME state :")
        p.println("  Keyboard mode = " + mKeyboardSwitcher.keyboardMode)
        p.println("  mComposing=$mComposing")
        p.println("  mSoundOn=$mSoundOn")
        p.println("  mVibrateOn=$mVibrateOn")
        p.println("  mPopupOn=" + popupOn)
    }

    companion object {
        private const val TAG = "PCKeyboardIME"
        private const val NOTIFICATION_CHANNEL_ID = "PCKeyboard"
        private const val NOTIFICATION_ONGOING_ID = 1001
        private const val PREF_VIBRATE_ON = "vibrate_on"
        const val PREF_VIBRATE_LEN = "vibrate_len"
        private const val PREF_NOIME = "noime"
        private const val PREF_SOUND_ON = "sound_on"
        private const val PREF_POPUP_ON = "popup_on"
        private const val PREF_AUTO_CAP = "auto_cap"
        private const val PREF_VOICE_MODE = "voice_mode"
        const val PREF_SELECTED_LANGUAGES = "selected_languages"
        const val PREF_INPUT_LANGUAGE = "input_language"
        const val PREF_FULLSCREEN_OVERRIDE = "fullscreen_override"
        const val PREF_FORCE_KEYBOARD_ON = "force_keyboard_on"
        const val PREF_KEYBOARD_NOTIFICATION = "keyboard_notification"
        const val PREF_HEIGHT_PORTRAIT = "settings_height_portrait"
        const val PREF_HEIGHT_LANDSCAPE = "settings_height_landscape"
        const val PREF_HINT_MODE = "pref_hint_mode"
        const val PREF_LONGPRESS_TIMEOUT = "pref_long_press_duration"
        const val PREF_RENDER_MODE = "pref_render_mode"
        const val PREF_SWIPE_UP = "pref_swipe_up"
        const val PREF_SWIPE_DOWN = "pref_swipe_down"
        const val PREF_SWIPE_LEFT = "pref_swipe_left"
        const val PREF_SWIPE_RIGHT = "pref_swipe_right"
        const val PREF_VOL_UP = "pref_vol_up"
        const val PREF_VOL_DOWN = "pref_vol_down"

        // How many continuous deletes at which to start deleting at a higher speed.
        private const val DELETE_ACCELERATE_AT = 20

        // Key events coming any faster than this are long-presses.
        private const val QUICK_PRESS = 200
        const val ASCII_ENTER = '\n'.code
        const val ASCII_SPACE = ' '.code

        private var savedOrientation: Int? = null

        // Contextual menu positions
        private const val POS_METHOD = 0
        private const val POS_SETTINGS = 1
        val sKeyboardSettings = GlobalKeyboardSettings()
        var sInstance: LatinIME? = null
        var isDualEnabled = false
            private set
        var mLastDisplayId = -1337
        var mLastConfiguration: Configuration? = null
        var mConfigurationPostId = 1
        var inputMethodAttachCnt = 0
        var lastActionIsShow = false
        var glocker = Any()
        private val asciiToKeyCode = IntArray(127)
        private const val KF_MASK = 0xffff
        private const val KF_SHIFTABLE = 0x10000
        private const val KF_UPPER = 0x20000
        private const val KF_LETTER = 0x40000
        var lastRequestDualMilli: Long = 0

        // Characters per second measurement
        private val NUMBER_RE = Pattern.compile("(\\d+).*")
        fun getIntFromString(`val`: String?, defVal: Int): Int {
            val num = NUMBER_RE.matcher(`val`)
            return if (!num.matches()) defVal else num.group(1).toInt()
        }

        fun getPrefInt(prefs: SharedPreferences, prefName: String?, defVal: Int): Int {
            val prefVal = prefs.getString(prefName, Integer.toString(defVal))
            //Log.i("PCKeyboard", "getPrefInt " + prefName + " = " + prefVal + ", default " + defVal);
            return getIntFromString(prefVal, defVal)
        }

        fun getPrefInt(prefs: SharedPreferences, prefName: String?, defStr: String?): Int {
            val defVal = getIntFromString(defStr, 0)
            return getPrefInt(prefs, prefName, defVal)
        }

        fun getHeight(prefs: SharedPreferences, prefName: String?, defVal: String?): Int {
            var `val` = getPrefInt(prefs, prefName, defVal)
            if (`val` < 15) `val` = 15
            if (`val` > 75) `val` = 75
            return `val`
        }
    }

    init {
        // Include RETURN in this set even though it's not printable.
        // Most other non-printable keys get handled elsewhere.
        asciiToKeyCode['\n'.code] = KeyEvent.KEYCODE_ENTER or KF_SHIFTABLE

        // Non-alphanumeric ASCII codes which have their own keys
        // (on some keyboards)
        asciiToKeyCode[' '.code] = KeyEvent.KEYCODE_SPACE or KF_SHIFTABLE
        //asciiToKeyCode['!'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['"'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['#'.code] = KeyEvent.KEYCODE_POUND
        //asciiToKeyCode['$'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['%'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['&'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['\''.code] = KeyEvent.KEYCODE_APOSTROPHE
        //asciiToKeyCode['('] = KeyEvent.KEYCODE_;
        //asciiToKeyCode[')'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['*'.code] = KeyEvent.KEYCODE_STAR
        asciiToKeyCode['+'.code] = KeyEvent.KEYCODE_PLUS
        asciiToKeyCode[','.code] = KeyEvent.KEYCODE_COMMA
        asciiToKeyCode['-'.code] = KeyEvent.KEYCODE_MINUS
        asciiToKeyCode['.'.code] = KeyEvent.KEYCODE_PERIOD
        asciiToKeyCode['/'.code] = KeyEvent.KEYCODE_SLASH
        //asciiToKeyCode[':'] = KeyEvent.KEYCODE_;
        asciiToKeyCode[';'.code] = KeyEvent.KEYCODE_SEMICOLON
        //asciiToKeyCode['<'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['='.code] = KeyEvent.KEYCODE_EQUALS
        //asciiToKeyCode['>'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['?'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['@'.code] = KeyEvent.KEYCODE_AT
        asciiToKeyCode['['.code] = KeyEvent.KEYCODE_LEFT_BRACKET
        asciiToKeyCode['\\'.code] = KeyEvent.KEYCODE_BACKSLASH
        asciiToKeyCode[']'.code] = KeyEvent.KEYCODE_RIGHT_BRACKET
        //asciiToKeyCode['^'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['_'] = KeyEvent.KEYCODE_;
        asciiToKeyCode['`'.code] = KeyEvent.KEYCODE_GRAVE
        //asciiToKeyCode['{'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['|'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['}'] = KeyEvent.KEYCODE_;
        //asciiToKeyCode['~'] = KeyEvent.KEYCODE_;
        for (i in 0..25) {
            asciiToKeyCode['a'.code + i] = KeyEvent.KEYCODE_A + i or KF_LETTER
            asciiToKeyCode['A'.code + i] = KeyEvent.KEYCODE_A + i or KF_UPPER or KF_LETTER
        }
        for (i in 0..9) {
            asciiToKeyCode['0'.code + i] = KeyEvent.KEYCODE_0 + i
        }
    }
}