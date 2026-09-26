package com.bookreader.app

import kotlin.math.max
import kotlin.math.min

/**
 * 判断两次视觉识别是否是同一页正文。
 * 空白和标点忽略；允许少量 OCR/模型差异。
 */
internal object ReadTextDeduper {
    private val noise = Regex("[\\s\\p{Punct}、。！？，；：．「」『』（）\\[\\]【】《》〈〉…—·•]+")

    fun isSame(previous: String, current: String): Boolean {
        val a = normalize(previous)
        val b = normalize(current)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val short = min(a.length, b.length)
        val long = max(a.length, b.length)
        if (short < 8) return false
        if (short * 5 < long * 4) return false
        return similarity(a, b) >= 0.86
    }

    internal fun normalize(text: String): String = noise.replace(text, "")

    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val n = max(a.length, b.length)
        if (n == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / n
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
