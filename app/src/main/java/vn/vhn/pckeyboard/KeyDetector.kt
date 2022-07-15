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

import android.util.*
import java.util.*

abstract class KeyDetector {
    protected var mKeyboard: Keyboard? = null
    private lateinit var mKeys: Array<Keyboard.Key?>
    protected var mCorrectionX = 0
    protected var mCorrectionY = 0
    var isProximityCorrectionEnabled = false
    protected var mProximityThresholdSquare = 0
    fun setKeyboard(
        keyboard: Keyboard?,
        correctionX: Float,
        correctionY: Float
    ): Array<Keyboard.Key?> {
        Log.i("KeyDetector", "KeyDetector correctionX=$correctionX correctionY=$correctionY")
        if (keyboard == null) throw NullPointerException()
        mCorrectionX = correctionX.toInt()
        mCorrectionY = correctionY.toInt()
        mKeyboard = keyboard
        val keys = mKeyboard!!.keys
        val array = keys!!.toTypedArray()
        mKeys = array
        return array
    }

    protected fun getTouchX(x: Int): Int {
        return x + mCorrectionX
    }

    protected fun getTouchY(y: Int): Int {
        return y + mCorrectionY
    }

    // mKeyboard is guaranteed not to be null at setKeybaord() method if mKeys is not null
    protected val keys: Array<Keyboard.Key?>
        protected get() {
            checkNotNull(mKeys) { "keyboard isn't set" }
            // mKeyboard is guaranteed not to be null at setKeybaord() method if mKeys is not null
            return mKeys
        }

    fun setProximityThreshold(threshold: Int) {
        mProximityThresholdSquare = threshold * threshold
    }

    /**
     * Allocates array that can hold all key indices returned by [.getKeyIndexAndNearbyCodes]
     * method. The maximum size of the array should be computed by [.getMaxNearbyKeys].
     *
     * @return Allocates and returns an array that can hold all key indices returned by
     * [.getKeyIndexAndNearbyCodes] method. All elements in the returned array are
     * initialized by [LatinKeyboardBaseView.NOT_A_KEY]
     * value.
     */
    fun newCodeArray(): IntArray {
        val codes = IntArray(maxNearbyKeys)
        Arrays.fill(codes, LatinKeyboardBaseView.Companion.NOT_A_KEY)
        return codes
    }

    /**
     * Computes maximum size of the array that can contain all nearby key indices returned by
     * [.getKeyIndexAndNearbyCodes].
     *
     * @return Returns maximum size of the array that can contain all nearby key indices returned
     * by [.getKeyIndexAndNearbyCodes].
     */
    protected abstract val maxNearbyKeys: Int

    /**
     * Finds all possible nearby key indices around a touch event point and returns the nearest key
     * index. The algorithm to determine the nearby keys depends on the threshold set by
     * [.setProximityThreshold] and the mode set by
     * [.setProximityCorrectionEnabled].
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @param allKeys All nearby key indices are returned in this array
     * @return The nearest key index
     */
    abstract fun getKeyIndexAndNearbyCodes(x: Int, y: Int, allKeys: IntArray?): Int
}