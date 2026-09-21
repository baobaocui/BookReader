package com.bookreader.app

/**
 * 用连续灰度小图判断「翻到新页并停稳」。
 * 运动中不触发；停稳后用中心区域 dHash 和上一页比较。
 */
internal class PageTurnTracker {
    private val lock = Any()
    private var baseline: Long? = null
    private var lastGray: IntArray? = null
    private var lastMotion = 0
    private var armed = false
    private var sawMotion = false
    private var firstCheck = false
    private var stableCount = 0
    private var rebaseWhenStable = false
    private var calmForRebase = 0

    /** 把当前预览当作「正在读的这一页」。尚无帧时，等画面停稳再记。 */
    fun lockCurrentPage() {
        synchronized(lock) {
            val gray = lastGray
            if (gray == null) {
                rebaseWhenStable = true
                calmForRebase = 0
                return
            }
            baseline = hashOf(gray)
            rebaseWhenStable = false
            calmForRebase = 0
        }
    }

    fun arm() {
        synchronized(lock) {
            armed = true
            sawMotion = false
            stableCount = 0
            firstCheck = true
            val gray = lastGray
            val base = baseline
            // 朗读期间的轻微漂移吸进基线；差得大说明朗读时已经换页，留给第一次停稳检查。
            if (gray != null && base != null && lastMotion <= MOTION_LOW) {
                val dist = hamming(hashOf(gray), base)
                if (dist < TURN_DIST) {
                    baseline = hashOf(gray)
                }
            }
        }
    }

    fun disarm() {
        synchronized(lock) {
            armed = false
            sawMotion = false
            stableCount = 0
            firstCheck = false
        }
    }

    /**
     * @return 确认翻页时与上一页的哈希距离；否则 null。
     * 触发后解除武装，且不改基线，方便这次读页被拒绝后还能再判一次。
     */
    fun onGray(gray: IntArray): Int? {
        synchronized(lock) {
            if (gray.size != SAMPLE_W * SAMPLE_H) return null
            val prev = lastGray
            lastGray = gray
            if (prev == null) {
                lastMotion = 0
                return null
            }
            val motion = meanAbsDiff(prev, gray)
            lastMotion = motion

            if (rebaseWhenStable) {
                if (motion <= MOTION_LOW) {
                    calmForRebase++
                    if (calmForRebase >= REBASE_FRAMES) {
                        baseline = hashOf(gray)
                        rebaseWhenStable = false
                        calmForRebase = 0
                        stableCount = 0
                        return null
                    }
                } else {
                    calmForRebase = 0
                }
            }

            if (!armed || baseline == null) return null

            if (motion >= MOTION_HIGH) {
                sawMotion = true
                stableCount = 0
                return null
            }
            if (motion > MOTION_LOW) {
                stableCount = 0
                return null
            }
            stableCount++
            if (stableCount < STABLE_FRAMES) return null

            val dist = hamming(hashOf(gray), baseline!!)
            val turned = dist >= CLEAR_DIST ||
                (firstCheck && dist >= TURN_DIST) ||
                (sawMotion && dist >= TURN_DIST)
            firstCheck = false
            if (dist < TURN_DIST) sawMotion = false
            stableCount = 0
            if (!turned) return null
            armed = false
            sawMotion = false
            return dist
        }
    }

    companion object {
        const val SAMPLE_W = 64
        const val SAMPLE_H = 48

        private const val STABLE_FRAMES = 4
        private const val REBASE_FRAMES = 2

        /** 平均绝对差 ≤ 此值视为画面不动。0–255。 */
        private const val MOTION_LOW = 12

        /** 平均绝对差 ≥ 此值视为手或纸页在动。 */
        private const val MOTION_HIGH = 20

        /** 动过之后，中心 dHash 汉明距离达到此值算换页。 */
        private const val TURN_DIST = 14

        /** 没抓到运动过程（例如朗读时已经翻完），距离达到此值也算换页。 */
        private const val CLEAR_DIST = 18

        internal fun hashDistance(a: IntArray, b: IntArray): Int = hamming(hashOf(a), hashOf(b))

        /** 把竖直画面坐标映射回 ImageProxy 的原始缓冲坐标。rotation 为顺时针转到竖直的角度。 */
        internal fun uprightToSource(
            ux: Int,
            uy: Int,
            srcW: Int,
            srcH: Int,
            rotation: Int
        ): Pair<Int, Int> {
            return when (rotation) {
                90 -> uy to (srcH - 1 - ux)
                180 -> (srcW - 1 - ux) to (srcH - 1 - uy)
                270 -> (srcW - 1 - uy) to ux
                else -> ux to uy
            }
        }

        private fun hashOf(gray: IntArray): Long {
            val x0 = SAMPLE_W / 4
            val y0 = SAMPLE_H / 4
            val cw = SAMPLE_W / 2
            val ch = SAMPLE_H / 2
            var bits = 0L
            var bit = 0
            for (row in 0 until 8) {
                val y = (y0 + row * ch / 8).coerceAtMost(SAMPLE_H - 1)
                var prev = -1
                for (col in 0 until 9) {
                    val x = (x0 + col * cw / 9).coerceAtMost(SAMPLE_W - 1)
                    val v = gray[y * SAMPLE_W + x]
                    if (prev >= 0) {
                        if (v > prev) bits = bits or (1L shl bit)
                        bit++
                    }
                    prev = v
                }
            }
            return bits
        }

        private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

        private fun meanAbsDiff(a: IntArray, b: IntArray): Int {
            var sum = 0L
            val n = minOf(a.size, b.size)
            if (n == 0) return 0
            for (i in 0 until n) {
                sum += kotlin.math.abs(a[i] - b[i])
            }
            return (sum / n).toInt()
        }
    }
}
