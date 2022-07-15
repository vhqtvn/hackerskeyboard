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

import java.util.*

internal class ProximityKeyDetector : KeyDetector() {
    // working area
    private val mDistances = IntArray(MAX_NEARBY_KEYS)
    override val maxNearbyKeys
        get() = MAX_NEARBY_KEYS

    override fun getKeyIndexAndNearbyCodes(x: Int, y: Int, allKeys: IntArray?): Int {
        val keys = keys
        val touchX = getTouchX(x)
        val touchY = getTouchY(y)
        var primaryIndex: Int = LatinKeyboardBaseView.Companion.NOT_A_KEY
        var closestKey: Int = LatinKeyboardBaseView.Companion.NOT_A_KEY
        var closestKeyDist = mProximityThresholdSquare + 1
        val distances = mDistances
        Arrays.fill(distances, Int.MAX_VALUE)
        val nearestKeyIndices = mKeyboard!!.getNearestKeys(touchX, touchY)
        val keyCount = nearestKeyIndices!!.size
        for (i in 0 until keyCount) {
            val key = keys!![nearestKeyIndices[i]]
            var dist = 0
            val isInside = key!!.isInside(touchX, touchY)
            if (isInside) {
                primaryIndex = nearestKeyIndices[i]
            }
            if (((isProximityCorrectionEnabled
                        && key.squaredDistanceFrom(touchX, touchY)
                    .also { dist = it } < mProximityThresholdSquare)
                        || isInside)
                && key.codes!![0] > 32
            ) {
                // Find insertion point
                val nCodes = key.codes!!.size
                if (dist < closestKeyDist) {
                    closestKeyDist = dist
                    closestKey = nearestKeyIndices[i]
                }
                if (allKeys == null) continue
                for (j in distances.indices) {
                    if (distances[j] > dist) {
                        // Make space for nCodes codes
                        System.arraycopy(distances, j, distances, j + nCodes,
                            distances.size - j - nCodes)
                        System.arraycopy(allKeys, j, allKeys, j + nCodes,
                            allKeys.size - j - nCodes)
                        System.arraycopy(key.codes, 0, allKeys, j, nCodes)
                        Arrays.fill(distances, j, j + nCodes, dist)
                        break
                    }
                }
            }
        }
        if (primaryIndex == LatinKeyboardBaseView.Companion.NOT_A_KEY) {
            primaryIndex = closestKey
        }
        return primaryIndex
    }

    companion object {
        private const val MAX_NEARBY_KEYS = 12
    }
}