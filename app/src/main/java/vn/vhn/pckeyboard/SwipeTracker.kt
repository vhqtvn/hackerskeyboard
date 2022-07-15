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

import android.view.MotionEvent

internal class SwipeTracker {
    val mBuffer = EventRingBuffer(NUM_PAST)
    var yVelocity = 0f
        private set
    var xVelocity = 0f
        private set

    fun addMovement(ev: MotionEvent) {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            mBuffer.clear()
            return
        }
        val time = ev.eventTime
        val count = ev.historySize
        for (i in 0 until count) {
            addPoint(ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalEventTime(i))
        }
        addPoint(ev.x, ev.y, time)
    }

    private fun addPoint(x: Float, y: Float, time: Long) {
        val buffer = mBuffer
        while (buffer.size() > 0) {
            val lastT = buffer.getTime(0)
            if (lastT >= time - LONGEST_PAST_TIME) break
            buffer.dropOldest()
        }
        buffer.add(x, y, time)
    }

    @JvmOverloads
    fun computeCurrentVelocity(units: Int, maxVelocity: Float = Float.MAX_VALUE) {
        val buffer = mBuffer
        val oldestX = buffer.getX(0)
        val oldestY = buffer.getY(0)
        val oldestTime = buffer.getTime(0)
        var accumX = 0f
        var accumY = 0f
        val count = buffer.size()
        for (pos in 1 until count) {
            val dur = (buffer.getTime(pos) - oldestTime).toInt()
            if (dur == 0) continue
            var dist = buffer.getX(pos) - oldestX
            var vel = dist / dur * units // pixels/frame.
            accumX = if (accumX == 0f) vel else (accumX + vel) * .5f
            dist = buffer.getY(pos) - oldestY
            vel = dist / dur * units // pixels/frame.
            accumY = if (accumY == 0f) vel else (accumY + vel) * .5f
        }
        xVelocity =
            if (accumX < 0.0f) Math.max(accumX, -maxVelocity) else Math.min(accumX, maxVelocity)
        yVelocity =
            if (accumY < 0.0f) Math.max(accumY, -maxVelocity) else Math.min(accumY, maxVelocity)
    }

    internal class EventRingBuffer(private val bufSize: Int) {
        private val xBuf: FloatArray
        private val yBuf: FloatArray
        private val timeBuf: LongArray
        private var top // points new event
                = 0
        private var end // points oldest event
                = 0
        private var count // the number of valid data
                = 0

        fun clear() {
            count = 0
            end = count
            top = end
        }

        fun size(): Int {
            return count
        }

        // Position 0 points oldest event
        private fun index(pos: Int): Int {
            return (end + pos) % bufSize
        }

        private fun advance(index: Int): Int {
            return (index + 1) % bufSize
        }

        fun add(x: Float, y: Float, time: Long) {
            xBuf[top] = x
            yBuf[top] = y
            timeBuf[top] = time
            top = advance(top)
            if (count < bufSize) {
                count++
            } else {
                end = advance(end)
            }
        }

        fun getX(pos: Int): Float {
            return xBuf[index(pos)]
        }

        fun getY(pos: Int): Float {
            return yBuf[index(pos)]
        }

        fun getTime(pos: Int): Long {
            return timeBuf[index(pos)]
        }

        fun dropOldest() {
            count--
            end = advance(end)
        }

        init {
            xBuf = FloatArray(bufSize)
            yBuf = FloatArray(bufSize)
            timeBuf = LongArray(bufSize)
            clear()
        }
    }

    companion object {
        private const val NUM_PAST = 4
        private const val LONGEST_PAST_TIME = 200
    }
}