package com.bookreader.app

object ReadIntentParser {
    private val readKeywords = listOf(
        "读", "朗读", "念", "讲一下", "读一下", "读一读",
        "帮我读", "请读", "开始读", "读这一页", "读这一页", "读出来"
    )

    fun isReadRequest(utterance: String): Boolean {
        val text = utterance.trim()
        if (text.isEmpty()) return false
        return readKeywords.any { text.contains(it) }
    }
}
