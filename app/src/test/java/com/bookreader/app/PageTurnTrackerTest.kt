package com.bookreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnTrackerTest {
    @Test
    fun rotation_roundTrips() {
        val srcW = 640
        val srcH = 480
        val samples = listOf(0 to 0, 10 to 20, 639 to 479)
        for ((sx, sy) in samples) {
            val upright0 = sx to sy
            val back0 = PageTurnTracker.uprightToSource(upright0.first, upright0.second, srcW, srcH, 0)
            assertEquals(sx, back0.first)
            assertEquals(sy, back0.second)

            val ux90 = srcH - 1 - sy
            val uy90 = sx
            val back90 = PageTurnTracker.uprightToSource(ux90, uy90, srcW, srcH, 90)
            assertEquals(sx, back90.first)
            assertEquals(sy, back90.second)

            val ux180 = srcW - 1 - sx
            val uy180 = srcH - 1 - sy
            val back180 = PageTurnTracker.uprightToSource(ux180, uy180, srcW, srcH, 180)
            assertEquals(sx, back180.first)
            assertEquals(sy, back180.second)

            val ux270 = sy
            val uy270 = srcW - 1 - sx
            val back270 = PageTurnTracker.uprightToSource(ux270, uy270, srcW, srcH, 270)
            assertEquals(sx, back270.first)
            assertEquals(sy, back270.second)
        }
    }

    @Test
    fun samePage_doesNotFire() {
        val tracker = locked(pageA)
        tracker.arm()
        repeat(8) {
            assertNull(tracker.onGray(pageA))
        }
    }

    @Test
    fun brightnessShift_doesNotFire() {
        val tracker = locked(pageA)
        tracker.arm()
        repeat(4) { assertNull(tracker.onGray(pageA)) }
        val brighter = pageA.map { (it + 15).coerceAtMost(220) }.toIntArray()
        assertTrue(PageTurnTracker.hashDistance(pageA, brighter) < 14)
        assertNull(tracker.onGray(brighter))
        repeat(6) {
            assertNull(tracker.onGray(brighter))
        }
    }

    @Test
    fun motionThenNewPage_firesOnce() {
        val tracker = locked(pageA)
        tracker.arm()
        repeat(4) { assertNull(tracker.onGray(pageA)) }
        assertNull(tracker.onGray(white))
        assertNull(tracker.onGray(pageB))
        repeat(3) { assertNull(tracker.onGray(pageB)) }
        assertNotNull(tracker.onGray(pageB))
        repeat(6) { assertNull(tracker.onGray(pageB)) }
    }

    @Test
    fun handPassesAndReturns_doesNotFire() {
        val tracker = locked(pageA)
        tracker.arm()
        repeat(4) { assertNull(tracker.onGray(pageA)) }
        assertNull(tracker.onGray(white))
        repeat(6) { assertNull(tracker.onGray(pageA)) }
    }

    @Test
    fun turnedWhileDisarmed_firesAfterArm() {
        val tracker = locked(pageA)
        tracker.disarm()
        repeat(3) { assertNull(tracker.onGray(pageB)) }
        tracker.arm()
        repeat(3) { assertNull(tracker.onGray(pageB)) }
        assertNotNull(tracker.onGray(pageB))
    }

    @Test
    fun rejectedTurn_canFireAgainUntilPageIsLocked() {
        val tracker = locked(pageA)
        tracker.arm()
        assertNull(tracker.onGray(white))
        assertNull(tracker.onGray(pageB))
        repeat(3) { assertNull(tracker.onGray(pageB)) }
        assertNotNull(tracker.onGray(pageB))

        tracker.arm()
        repeat(3) { assertNull(tracker.onGray(pageB)) }
        assertNotNull(tracker.onGray(pageB))

        tracker.lockCurrentPage()
        tracker.arm()
        repeat(8) { assertNull(tracker.onGray(pageB)) }
    }

    companion object {
        private val pageA = stripes(width = 3, phase = 0)
        private val pageB = stripes(width = 5, phase = 1)
        private val white = IntArray(PageTurnTracker.SAMPLE_W * PageTurnTracker.SAMPLE_H) { 255 }

        init {
            val dist = PageTurnTracker.hashDistance(pageA, pageB)
            check(dist >= 18) { "测试页哈希距离过近: $dist" }
        }

        private fun locked(page: IntArray): PageTurnTracker {
            val tracker = PageTurnTracker()
            tracker.onGray(page)
            tracker.lockCurrentPage()
            return tracker
        }

        private fun stripes(width: Int, phase: Int): IntArray {
            val w = PageTurnTracker.SAMPLE_W
            val h = PageTurnTracker.SAMPLE_H
            val out = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val on = ((x / width) + (y / width) + phase) % 2 == 0
                    out[y * w + x] = if (on) 40 else 180
                }
            }
            return out
        }
    }
}
