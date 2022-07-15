package vn.vhn.pckeyboard

import android.content.SharedPreferences
import android.content.res.Resources
import java.util.*

/**
 * Global current settings for the keyboard.
 *
 *
 *
 * Yes, globals are evil. But the persisted shared preferences are global data
 * by definition, and trying to hide this by propagating the current manually
 * just adds a lot of complication. This is especially annoying due to Views
 * getting constructed in a way that doesn't support adding additional
 * constructor arguments, requiring post-construction method calls, which is
 * error-prone and fragile.
 *
 *
 *
 * The comments below indicate which class is responsible for updating the
 * value, and for recreating keyboards or views as necessary. Other classes
 * MUST treat the fields as read-only values, and MUST NOT attempt to save
 * these values or results derived from them across re-initializations.
 *
 *
 * @author klaus.weidner@gmail.com
 */
class GlobalKeyboardSettings {
    /* Simple prefs updated by this class */ //
    // Read by Keyboard
    var popupKeyboardFlags = 0x1
    var topRowScale = 1.0f

    //
    // Read by LatinKeyboardView
    var showTouchPos = false

    //
    // Read by LatinIME
    var suggestedPunctuation: String? = "!?,."
    var keyboardModePortrait = 0
    var keyboardModeLandscape = 2
    var keyClickVolume = 0.0f
    var keyClickMethod = 0
    var capsLock = true
    var shiftLockModifiers = false

    //
    // Read by LatinKeyboardBaseView
    var labelScalePref = 1.0f

    //
    // Read by CandidateView
    var candidateScalePref = 1.0f

    //
    // Read by PointerTracker
    var sendSlideKeys = 0

    /* Updated by LatinIME */ //
    // Read by KeyboardSwitcher
    var keyboardMode = 0
    var useExtension = false

    //
    // Read by LatinKeyboardView and KeyboardSwitcher
    var keyboardHeightPercent = 40.0f // percent of screen height

    //
    // Read by LatinKeyboardBaseView
    var hintMode = 0
    var renderMode = 1

    //
    // Read by PointerTracker
    var longpressTimeout = 400

    //
    // Read by LatinIMESettings
    // These are cached values for informational display, don't use for other purposes
    var editorPackageName: String? = null
    var editorFieldName: String? = null
    var editorFieldId = 0
    var editorInputType = 0

    /* Updated by KeyboardSwitcher */ //
    // Used by LatinKeyboardBaseView and LatinIME
    /* Updated by LanguageSwitcher */ //
    // Used by Keyboard and KeyboardSwitcher
    var inputLocale = Locale.getDefault()

    // Auto pref implementation follows
    private val mBoolPrefs: MutableMap<String, BooleanPref> = HashMap()
    private val mStringPrefs: MutableMap<String, StringPref> = HashMap()
    private var mCurrentFlags = 0

    private interface BooleanPref {
        fun set(`val`: Boolean)
        fun getDefault(): Boolean
        fun getFlags(): Int
    }

    private interface StringPref {
        fun set(`val`: String?)
        fun getDefault(): String
        fun getFlags(): Int
    }

    fun initPrefs(prefs: SharedPreferences, resources: Resources) {
        addStringPref("pref_keyboard_mode_portrait", object : StringPref {
            override fun set(`val`: String?) {
                keyboardModePortrait = Integer.valueOf(`val`)
            }

            override fun getDefault(): String {
                return resources.getString(R.string.default_keyboard_mode_portrait)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_RESET_KEYBOARDS or FLAG_PREF_RESET_MODE_OVERRIDE
            }
        })
        addStringPref("pref_keyboard_mode_landscape", object : StringPref {
            override fun set(`val`: String?) {
                keyboardModeLandscape = Integer.valueOf(`val`)
            }

            override fun getDefault(): String {
                return resources.getString(R.string.default_keyboard_mode_landscape)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_RESET_KEYBOARDS or FLAG_PREF_RESET_MODE_OVERRIDE
            }
        })
        addStringPref("pref_slide_keys_int", object : StringPref {
            override fun set(`val`: String?) {
                sendSlideKeys = Integer.valueOf(`val`)
            }

            override fun getDefault(): String {
                return "0"
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NONE
            }
        })
        addBooleanPref("pref_touch_pos", object : BooleanPref {
            override fun set(`val`: Boolean) {
                showTouchPos = `val`
            }

            override fun getDefault(): Boolean {
                return false
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NONE
            }
        })
        addStringPref("pref_popup_content", object : StringPref {
            override fun set(`val`: String?) {
                popupKeyboardFlags = Integer.valueOf(`val`)
            }

            override fun getDefault(): String {
                return resources.getString(R.string.default_popup_content)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_RESET_KEYBOARDS
            }
        })
        addStringPref("pref_suggested_punctuation", object : StringPref {
            override fun set(`val`: String?) {
                suggestedPunctuation = `val`
            }

            override fun getDefault(): String {
                return resources.getString(R.string.suggested_punctuations_default)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NEW_PUNC_LIST
            }
        })
        addStringPref("pref_label_scale_v2", object : StringPref {
            override fun set(`val`: String?) {
                labelScalePref = java.lang.Float.valueOf(`val`)
            }

            override fun getDefault(): String {
                return "1.0"
            }

            override fun getFlags(): Int {
                return FLAG_PREF_RECREATE_INPUT_VIEW
            }
        })
        addStringPref("pref_candidate_scale", object : StringPref {
            override fun set(`val`: String?) {
                candidateScalePref = java.lang.Float.valueOf(`val`)
            }

            override fun getDefault(): String {
                return "1.0"
            }

            override fun getFlags(): Int {
                return FLAG_PREF_RESET_KEYBOARDS
            }
        })
        addStringPref("pref_top_row_scale", object : StringPref {
            override fun set(`val`: String?) {
                topRowScale = java.lang.Float.valueOf(`val`)
            }

            override fun getDefault(): String {
                return "1.0"
            }

            override fun getFlags(): Int {
                return FLAG_PREF_RESET_KEYBOARDS
            }
        })
        addStringPref("pref_click_volume", object : StringPref {
            override fun set(`val`: String?) {
                keyClickVolume = java.lang.Float.valueOf(`val`)
            }

            override fun getDefault(): String {
                return resources.getString(R.string.default_click_volume)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NONE
            }
        })
        addStringPref("pref_click_method", object : StringPref {
            override fun set(`val`: String?) {
                keyClickMethod = Integer.valueOf(`val`)
            }

            override fun getDefault(): String {
                return resources.getString(R.string.default_click_method)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NONE
            }
        })
        addBooleanPref("pref_caps_lock", object : BooleanPref {
            override fun set(`val`: Boolean) {
                capsLock = `val`
            }

            override fun getDefault(): Boolean {
                return resources.getBoolean(R.bool.default_caps_lock)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NONE
            }
        })
        addBooleanPref("pref_shift_lock_modifiers", object : BooleanPref {
            override fun set(`val`: Boolean) {
                shiftLockModifiers = `val`
            }

            override fun getDefault(): Boolean {
                return resources.getBoolean(R.bool.default_shift_lock_modifiers)
            }

            override fun getFlags(): Int {
                return FLAG_PREF_NONE
            }
        })

        // Set initial values
        for (key in mBoolPrefs.keys) {
            val pref = mBoolPrefs[key]
            pref!!.set(prefs.getBoolean(key, pref.getDefault()))
        }
        for (key in mStringPrefs.keys) {
            val pref = mStringPrefs[key]
            pref!!.set(prefs.getString(key, pref.getDefault()))
        }
    }

    fun sharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        var found = false
        mCurrentFlags = FLAG_PREF_NONE
        val bPref = mBoolPrefs[key]
        if (bPref != null) {
            found = true
            bPref.set(prefs.getBoolean(key, bPref.getDefault()))
            mCurrentFlags = mCurrentFlags or bPref.getFlags()
        }
        val sPref = mStringPrefs[key]
        if (sPref != null) {
            found = true
            sPref.set(prefs.getString(key, sPref.getDefault()))
            mCurrentFlags = mCurrentFlags or sPref.getFlags()
        }
        //if (!found) Log.i(TAG, "sharedPreferenceChanged: unhandled key=" + key);
    }

    fun hasFlag(flag: Int): Boolean {
        if (mCurrentFlags and flag != 0) {
            mCurrentFlags = mCurrentFlags and flag.inv()
            return true
        }
        return false
    }

    fun unhandledFlags(): Int {
        return mCurrentFlags
    }

    private fun addBooleanPref(key: String, setter: BooleanPref) {
        mBoolPrefs[key] = setter
    }

    private fun addStringPref(key: String, setter: StringPref) {
        mStringPrefs[key] = setter
    }

    companion object {
        protected const val TAG = "HK/Globals"
        const val FLAG_PREF_NONE = 0
        const val FLAG_PREF_NEED_RELOAD = 0x1
        const val FLAG_PREF_NEW_PUNC_LIST = 0x2
        const val FLAG_PREF_RECREATE_INPUT_VIEW = 0x4
        const val FLAG_PREF_RESET_KEYBOARDS = 0x8
        const val FLAG_PREF_RESET_MODE_OVERRIDE = 0x10
    }
}