package com.bookreader.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTextDeduperTest {
    @Test
    fun same_after_whitespace_and_punctuation() {
        val a = "春眠不觉晓，处处闻啼鸟。\n夜来风雨声"
        val b = "春眠不觉晓处处闻啼鸟 夜来风雨声。"
        assertTrue(ReadTextDeduper.isSame(a, b))
    }

    @Test
    fun same_with_small_ocr_noise() {
        val a = "从前有座山，山里有座庙，庙里有个老和尚在讲故事。"
        val b = "从前有座山，山里有座庙，庙里有个老和尚在讲故事故。"
        assertTrue(ReadTextDeduper.isSame(a, b))
    }

    @Test
    fun different_pages_are_not_same() {
        val a = "从前有座山，山里有座庙，庙里有个老和尚在讲故事。"
        val b = "第二天早上，小和尚去山下挑水，回来时天已经亮了。"
        assertFalse(ReadTextDeduper.isSame(a, b))
    }

    @Test
    fun empty_is_not_same() {
        assertFalse(ReadTextDeduper.isSame("", "春眠不觉晓"))
        assertFalse(ReadTextDeduper.isSame("春眠不觉晓", "   "))
    }
}
