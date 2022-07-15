/*
 * Copyright (C) 2010 Google Inc.
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

import android.content.res.Resources
import android.util.*
import android.view.MotionEvent
import vn.vhn.pckeyboard.LatinKeyboardBaseView.UIHandler

class PointerTracker(
    id: Int, handler: UIHandler?, keyDetector: KeyDetector?, proxy: UIProxy?,
    res: Resources, slideKeyHack: Boolean
) {
    interface UIProxy {
        fun invalidateKey(key: Keyboard.Key?)
        fun showPreview(keyIndex: Int, tracker: PointerTracker?)
        fun hasDistinctMultitouch(): Boolean
    }

    val mPointerId: Int

    // Timing constants
    private val mDelayBeforeKeyRepeatStart: Int
    private val mMultiTapKeyTimeout: Int
    private val mProxy: UIProxy
    private val mHandler: UIHandler
    private val mKeyDetector: KeyDetector
    private var mListener: LatinKeyboardBaseView.OnKeyboardActionListener? = null
    private val mKeyboardSwitcher: KeyboardSwitcher
    private val mHasDistinctMultitouch: Boolean
    private var mKeys: Array<Keyboard.Key?>? = null
    private var mKeyHysteresisDistanceSquared = -1
    private val mKeyState: KeyState

    // true if keyboard layout has been changed.
    private var mKeyboardLayoutHasBeenChanged = false

    // true if event is already translated to a key action (long press or mini-keyboard)
    private var mKeyAlreadyProcessed = false

    // true if this pointer is repeatable key
    private var mIsRepeatableKey = false

    // true if this pointer is in sliding key input
    var isInSlidingKeyInput = false
        private set

    // For multi-tap
    private var mLastSentIndex = 0
    private var mTapCount = 0
    private var mLastTapTime: Long = 0
    private var mInMultiTap = false
    private val mPreviewLabel = StringBuilder(1)

    // pressed key
    private var mPreviousKey = NOT_A_KEY

    // This class keeps track of a key index and a position where this pointer is.
    private class KeyState(private val mKeyDetector: KeyDetector) {
        // The position and time at which first down event occurred.
        var startX = 0
            private set
        var startY = 0
            private set
        var downTime: Long = 0
            private set

        // The current key index where this pointer is.
        var keyIndex = NOT_A_KEY
            private set

        // The position where mKeyIndex was recognized for the first time.
        var keyX = 0
            private set
        var keyY = 0
            private set

        // Last pointer position.
        var lastX = 0
            private set
        var lastY = 0
            private set

        fun onDownKey(x: Int, y: Int, eventTime: Long): Int {
            startX = x
            startY = y
            downTime = eventTime
            return onMoveToNewKey(onMoveKeyInternal(x, y), x, y)
        }

        private fun onMoveKeyInternal(x: Int, y: Int): Int {
            lastX = x
            lastY = y
            return mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null)
        }

        fun onMoveKey(x: Int, y: Int): Int {
            return onMoveKeyInternal(x, y)
        }

        fun onMoveToNewKey(keyIndex: Int, x: Int, y: Int): Int {
            this.keyIndex = keyIndex
            keyX = x
            keyY = y
            return keyIndex
        }

        fun onUpKey(x: Int, y: Int): Int {
            return onMoveKeyInternal(x, y)
        }
    }

    fun setOnKeyboardActionListener(listener: LatinKeyboardBaseView.OnKeyboardActionListener?) {
        mListener = listener
    }

    fun setKeyboard(keys: Array<Keyboard.Key?>?, keyHysteresisDistance: Float) {
        require(!(keys == null || keyHysteresisDistance < 0))
        mKeys = keys
        mKeyHysteresisDistanceSquared = (keyHysteresisDistance * keyHysteresisDistance).toInt()
        // Mark that keyboard layout has been changed.
        mKeyboardLayoutHasBeenChanged = true
    }

    fun setSlidingKeyInputState(state: Boolean) {
        isInSlidingKeyInput = state
    }

    private fun isValidKeyIndex(keyIndex: Int): Boolean {
        return keyIndex >= 0 && keyIndex < mKeys!!.size
    }

    fun getKey(keyIndex: Int): Keyboard.Key? {
        return if (isValidKeyIndex(keyIndex)) mKeys!![keyIndex] else null
    }

    private fun isModifierInternal(keyIndex: Int): Boolean {
        val key = getKey(keyIndex)
        if (key == null || key.codes == null) return false
        val primaryCode = key.codes!![0]
        return primaryCode == LatinKeyboardView.Companion.KEYCODE_SHIFT_LEFT || primaryCode == LatinKeyboardView.Companion.KEYCODE_SHIFT_RIGHT || primaryCode == Keyboard.Companion.KEYCODE_MODE_CHANGE || primaryCode == LatinKeyboardView.Companion.KEYCODE_CTRL_LEFT || primaryCode == LatinKeyboardView.Companion.KEYCODE_ALT_LEFT || primaryCode == LatinKeyboardView.Companion.KEYCODE_META_LEFT || primaryCode == LatinKeyboardView.Companion.KEYCODE_CTRL_RIGHT || primaryCode == LatinKeyboardView.Companion.KEYCODE_ALT_RIGHT || primaryCode == LatinKeyboardView.Companion.KEYCODE_META_RIGHT || primaryCode == LatinKeyboardView.Companion.KEYCODE_FN || primaryCode == LatinKeyboardView.Companion.KEYCODE_FN_1 || primaryCode == LatinKeyboardView.Companion.KEYCODE_FN_2
    }

    val isModifier: Boolean
        get() = isModifierInternal(mKeyState.keyIndex)

    fun isOnModifierKey(x: Int, y: Int): Boolean {
        return isModifierInternal(mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null))
    }

    fun isSpaceKey(keyIndex: Int): Boolean {
        val key = getKey(keyIndex)
        return key != null && key.codes != null && key.codes!![0] == LatinIME.Companion.ASCII_SPACE
    }

    fun updateKey(keyIndex: Int) {
        if (mKeyAlreadyProcessed) return
        val oldKeyIndex = mPreviousKey
        mPreviousKey = keyIndex
        if (keyIndex != oldKeyIndex) {
            if (isValidKeyIndex(oldKeyIndex)) {
                // if new key index is not a key, old key was just released inside of the key.
                val inside = keyIndex == NOT_A_KEY
                mKeys!![oldKeyIndex]!!.onReleased(inside)
                mProxy.invalidateKey(mKeys!![oldKeyIndex])
            }
            if (isValidKeyIndex(keyIndex)) {
                mKeys!![keyIndex]!!.onPressed()
                mProxy.invalidateKey(mKeys!![keyIndex])
            }
        }
    }

    fun setAlreadyProcessed() {
        mKeyAlreadyProcessed = true
    }

    fun onTouchEvent(action: Int, x: Int, y: Int, eventTime: Long) {
        when (action) {
            MotionEvent.ACTION_MOVE -> onMoveEvent(x, y, eventTime)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDownEvent(x, y, eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUpEvent(x, y, eventTime)
            MotionEvent.ACTION_CANCEL -> onCancelEvent(x, y, eventTime)
        }
    }

    fun onDownEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG) debugLog("onDownEvent:", x, y)
        var keyIndex = mKeyState.onDownKey(x, y, eventTime)
        mKeyboardLayoutHasBeenChanged = false
        mKeyAlreadyProcessed = false
        mIsRepeatableKey = false
        isInSlidingKeyInput = false
        checkMultiTap(eventTime, keyIndex)
        if (mListener != null) {
            if (isValidKeyIndex(keyIndex)) {
                val key = mKeys!![keyIndex]
                if (key!!.codes != null) mListener!!.onPress(key.primaryCode)
                // This onPress call may have changed keyboard layout. Those cases are detected at
                // {@link #setKeyboard}. In those cases, we should update keyIndex according to the
                // new keyboard layout.
                if (mKeyboardLayoutHasBeenChanged) {
                    mKeyboardLayoutHasBeenChanged = false
                    keyIndex = mKeyState.onDownKey(x, y, eventTime)
                }
            }
        }
        if (isValidKeyIndex(keyIndex)) {
            if (mKeys!![keyIndex]!!.repeatable) {
                repeatKey(keyIndex)
                mHandler.startKeyRepeatTimer(mDelayBeforeKeyRepeatStart.toLong(), keyIndex, this)
                mIsRepeatableKey = true
            }
            startLongPressTimer(keyIndex)
        }
        showKeyPreviewAndUpdateKey(keyIndex)
    }

    fun sendSlideKeys() {
        if (!sSlideKeyHack) return
        val slideMode: Int = LatinIME.Companion.sKeyboardSettings.sendSlideKeys
        if (slideMode and 4 > 0) {
            // send all
            for (key in sSlideKeys) {
                detectAndSendKey(key, key.x, key.y, -1)
            }
        } else {
            // Send first and/or last key only.
            val n = sSlideKeys.size
            if (n > 0 && slideMode and 1 > 0) {
                val key = sSlideKeys[0]
                detectAndSendKey(key, key.x, key.y, -1)
            }
            if (n > 1 && slideMode and 2 > 0) {
                val key = sSlideKeys[n - 1]
                detectAndSendKey(key, key.x, key.y, -1)
            }
        }
        clearSlideKeys()
    }

    fun onMoveEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG_MOVE) debugLog("onMoveEvent:", x, y)
        if (mKeyAlreadyProcessed) return
        val keyState = mKeyState
        var keyIndex = keyState.onMoveKey(x, y)
        val oldKey = getKey(keyState.keyIndex)
        if (isValidKeyIndex(keyIndex)) {
            val isMinorMoveBounce = isMinorMoveBounce(x, y, keyIndex)
            if (DEBUG_MOVE) Log.i(TAG,
                "isMinorMoveBounce=" + isMinorMoveBounce + " oldKey=" + (oldKey ?: "null"))
            if (oldKey == null) {
                // The pointer has been slid in to the new key, but the finger was not on any keys.
                // In this case, we must call onPress() to notify that the new key is being pressed.
                if (mListener != null) {
                    val key = getKey(keyIndex)
                    if (key!!.codes != null) mListener!!.onPress(key.primaryCode)
                    // This onPress call may have changed keyboard layout. Those cases are detected
                    // at {@link #setKeyboard}. In those cases, we should update keyIndex according
                    // to the new keyboard layout.
                    if (mKeyboardLayoutHasBeenChanged) {
                        mKeyboardLayoutHasBeenChanged = false
                        keyIndex = keyState.onMoveKey(x, y)
                    }
                }
                keyState.onMoveToNewKey(keyIndex, x, y)
                startLongPressTimer(keyIndex)
            } else if (!isMinorMoveBounce) {
                // The pointer has been slid in to the new key from the previous key, we must call
                // onRelease() first to notify that the previous key has been released, then call
                // onPress() to notify that the new key is being pressed.
                isInSlidingKeyInput = true
                if (mListener != null && oldKey.codes != null) mListener!!.onRelease(oldKey.primaryCode)
                resetMultiTap()
                if (mListener != null) {
                    val key = getKey(keyIndex)
                    if (key!!.codes != null) mListener!!.onPress(key.primaryCode)
                    // This onPress call may have changed keyboard layout. Those cases are detected
                    // at {@link #setKeyboard}. In those cases, we should update keyIndex according
                    // to the new keyboard layout.
                    if (mKeyboardLayoutHasBeenChanged) {
                        mKeyboardLayoutHasBeenChanged = false
                        keyIndex = keyState.onMoveKey(x, y)
                    }
                    addSlideKey(oldKey)
                }
                keyState.onMoveToNewKey(keyIndex, x, y)
                startLongPressTimer(keyIndex)
            }
        } else {
            if (oldKey != null && !isMinorMoveBounce(x, y, keyIndex)) {
                // The pointer has been slid out from the previous key, we must call onRelease() to
                // notify that the previous key has been released.
                isInSlidingKeyInput = true
                if (mListener != null && oldKey.codes != null) mListener!!.onRelease(oldKey.primaryCode)
                resetMultiTap()
                keyState.onMoveToNewKey(keyIndex, x, y)
                mHandler.cancelLongPressTimer()
            }
        }
        showKeyPreviewAndUpdateKey(keyState.keyIndex)
    }

    fun onUpEvent(x: Int, y: Int, eventTime: Long) {
        var x = x
        var y = y
        if (DEBUG) debugLog("onUpEvent  :", x, y)
        mHandler.cancelKeyTimers()
        mHandler.cancelPopupPreview()
        showKeyPreviewAndUpdateKey(NOT_A_KEY)
        isInSlidingKeyInput = false
        sendSlideKeys()
        if (mKeyAlreadyProcessed) return
        var keyIndex = mKeyState.onUpKey(x, y)
        if (isMinorMoveBounce(x, y, keyIndex)) {
            // Use previous fixed key index and coordinates.
            keyIndex = mKeyState.keyIndex
            x = mKeyState.keyX
            y = mKeyState.keyY
        }
        if (!mIsRepeatableKey) {
            detectAndSendKey(keyIndex, x, y, eventTime)
        }
        if (isValidKeyIndex(keyIndex)) mProxy.invalidateKey(mKeys!![keyIndex])
    }

    fun onCancelEvent(x: Int, y: Int, eventTime: Long) {
        if (DEBUG) debugLog("onCancelEvt:", x, y)
        mHandler.cancelKeyTimers()
        mHandler.cancelPopupPreview()
        showKeyPreviewAndUpdateKey(NOT_A_KEY)
        isInSlidingKeyInput = false
        val keyIndex = mKeyState.keyIndex
        if (isValidKeyIndex(keyIndex)) mProxy.invalidateKey(mKeys!![keyIndex])
    }

    fun repeatKey(keyIndex: Int) {
        val key = getKey(keyIndex)
        if (key != null) {
            // While key is repeating, because there is no need to handle multi-tap key, we can
            // pass -1 as eventTime argument.
            detectAndSendKey(keyIndex, key.x, key.y, -1)
        }
    }

    val lastX: Int
        get() = mKeyState.lastX
    val lastY: Int
        get() = mKeyState.lastY
    val downTime: Long
        get() = mKeyState.downTime

    // These package scope methods are only for debugging purpose.
    /* package */
    val startX: Int
        get() = mKeyState.startX

    /* package */
    val startY: Int
        get() = mKeyState.startY

    private fun isMinorMoveBounce(x: Int, y: Int, newKey: Int): Boolean {
        check(!(mKeys == null || mKeyHysteresisDistanceSquared < 0)) { "keyboard and/or hysteresis not set" }
        val curKey = mKeyState.keyIndex
        return if (newKey == curKey) {
            true
        } else if (isValidKeyIndex(curKey)) {
            //return false; // TODO(klausw): tweak this?
            getSquareDistanceToKeyEdge(x,
                y,
                mKeys!![curKey]) < mKeyHysteresisDistanceSquared
        } else {
            false
        }
    }

    private fun showKeyPreviewAndUpdateKey(keyIndex: Int) {
        updateKey(keyIndex)
        // The modifier key, such as shift key, should not be shown as preview when multi-touch is
        // supported. On the other hand, if multi-touch is not supported, the modifier key should
        // be shown as preview.
        if (mHasDistinctMultitouch && isModifier) {
            mProxy.showPreview(NOT_A_KEY, this)
        } else {
            mProxy.showPreview(keyIndex, this)
        }
    }

    private fun startLongPressTimer(keyIndex: Int) {
        if (mKeyboardSwitcher.isInMomentaryAutoModeSwitchState) {
            // We use longer timeout for sliding finger input started from the symbols mode key.
            mHandler.startLongPressTimer((LatinIME.Companion.sKeyboardSettings.longpressTimeout * 3).toLong(),
                keyIndex,
                this)
        } else {
            mHandler.startLongPressTimer(LatinIME.Companion.sKeyboardSettings.longpressTimeout.toLong(),
                keyIndex,
                this)
        }
    }

    private fun detectAndSendKey(index: Int, x: Int, y: Int, eventTime: Long) {
        detectAndSendKey(getKey(index), x, y, eventTime)
        mLastSentIndex = index
    }

    private fun detectAndSendKey(key: Keyboard.Key?, x: Int, y: Int, eventTime: Long) {
        val listener = mListener
        if (key == null) {
            listener?.onCancel()
        } else {
            if (key.text != null) {
                if (listener != null) {
                    listener.onText(key.text)
                    listener.onRelease(0) // dummy key code
                }
            } else {
                if (key.codes == null) return
                var code = key.primaryCode
                val codes = mKeyDetector.newCodeArray()
                mKeyDetector.getKeyIndexAndNearbyCodes(x, y, codes)
                // Multi-tap
                if (mInMultiTap) {
                    if (mTapCount != -1) {
                        mListener!!.onKey(Keyboard.Companion.KEYCODE_DELETE, KEY_DELETE, x, y)
                    } else {
                        mTapCount = 0
                    }
                    code = key.codes!![mTapCount]
                }
                /*
                 * Swap the first and second values in the codes array if the primary code is not
                 * the first value but the second value in the array. This happens when key
                 * debouncing is in effect.
                 */if (codes!!.size >= 2 && codes[0] != code && codes[1] == code) {
                    codes[1] = codes[0]
                    codes[0] = code
                }
                if (listener != null) {
                    listener.onKey(code, codes, x, y)
                    listener.onRelease(code)
                }
            }
            mLastTapTime = eventTime
        }
    }

    /**
     * Handle multi-tap keys by producing the key label for the current multi-tap state.
     */
    fun getPreviewText(key: Keyboard.Key): CharSequence? {
        return if (mInMultiTap) {
            // Multi-tap
            mPreviewLabel.setLength(0)
            mPreviewLabel.append(key.codes!![if (mTapCount < 0) 0 else mTapCount].toChar())
            mPreviewLabel
        } else {
            if (key.isDeadKey) {
                DeadAccentSequence.Companion.normalize(" " + key.label)
            } else {
                key.label
            }
        }
    }

    private fun resetMultiTap() {
        mLastSentIndex = NOT_A_KEY
        mTapCount = 0
        mLastTapTime = -1
        mInMultiTap = false
    }

    private fun checkMultiTap(eventTime: Long, keyIndex: Int) {
        val key = getKey(keyIndex)
        if (key == null || key.codes == null) return
        val isMultiTap =
            eventTime < mLastTapTime + mMultiTapKeyTimeout && keyIndex == mLastSentIndex
        if (key.codes!!.size > 1) {
            mInMultiTap = true
            if (isMultiTap) {
                mTapCount = (mTapCount + 1) % key.codes!!.size
                return
            } else {
                mTapCount = -1
                return
            }
        }
        if (!isMultiTap) {
            resetMultiTap()
        }
    }

    private fun debugLog(title: String, x: Int, y: Int) {
        val keyIndex = mKeyDetector.getKeyIndexAndNearbyCodes(x, y, null)
        val key = getKey(keyIndex)
        val code: String
        code = if (key == null || key.codes == null) {
            "----"
        } else {
            val primaryCode = key.codes!![0]
            String.format(if (primaryCode < 0) "%4d" else "0x%02x", primaryCode)
        }
        Log.d(TAG, String.format("%s%s[%d] %3d,%3d %3d(%s) %s", title,
            if (mKeyAlreadyProcessed) "-" else " ", mPointerId, x, y, keyIndex, code,
            if (isModifier) "modifier" else ""))
    }

    companion object {
        private const val TAG = "PointerTracker"
        private const val DEBUG = false
        private const val DEBUG_MOVE = false

        // Miscellaneous constants
        private val NOT_A_KEY: Int = LatinKeyboardBaseView.Companion.NOT_A_KEY
        private val KEY_DELETE = intArrayOf(Keyboard.Companion.KEYCODE_DELETE)
        private var sSlideKeyHack: Boolean = false
        private val sSlideKeys: MutableList<Keyboard.Key> = ArrayList(10)
        private fun addSlideKey(key: Keyboard.Key?) {
            if (!sSlideKeyHack || LatinIME.Companion.sKeyboardSettings.sendSlideKeys == 0) return
            if (key == null) return
            if (key.modifier) {
                clearSlideKeys()
            } else {
                sSlideKeys.add(key)
            }
        }

        /*package*/
        fun clearSlideKeys() {
            sSlideKeys.clear()
        }

        private fun getSquareDistanceToKeyEdge(x: Int, y: Int, key: Keyboard.Key?): Int {
            val left = key!!.x
            val right = key.x + key.width
            val top = key.y
            val bottom = key.y + key.height
            val edgeX = if (x < left) left else if (x > right) right else x
            val edgeY = if (y < top) top else if (y > bottom) bottom else y
            val dx = x - edgeX
            val dy = y - edgeY
            return dx * dx + dy * dy
        }
    }

    init {
        if (proxy == null || handler == null || keyDetector == null) throw NullPointerException()
        mPointerId = id
        mProxy = proxy
        mHandler = handler
        mKeyDetector = keyDetector
        mKeyboardSwitcher = KeyboardSwitcher.Companion.instance
        mKeyState = KeyState(keyDetector)
        mHasDistinctMultitouch = proxy.hasDistinctMultitouch()
        mDelayBeforeKeyRepeatStart = res.getInteger(R.integer.config_delay_before_key_repeat_start)
        mMultiTapKeyTimeout = res.getInteger(R.integer.config_multi_tap_key_timeout)
        sSlideKeyHack = slideKeyHack
        resetMultiTap()
    }
}