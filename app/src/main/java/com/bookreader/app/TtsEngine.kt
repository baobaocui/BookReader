package com.bookreader.app

/**
 * 朗读引擎。可在界面随时切换，选择会写入 SharedPreferences。
 */
enum class TtsEngine(val id: String, val label: String, val shortLabel: String) {
    AZURE("azure", "Azure（英文优先）", "Azure"),
    VOLC("volc", "豆包小何", "豆包"),
    SYSTEM("system", "系统语音", "系统");

    companion object {
        fun fromId(id: String?): TtsEngine? = entries.firstOrNull { it.id == id }

        /** 按配置可用性给出默认引擎：Azure → 豆包 → 系统 */
        fun preferredDefault(): TtsEngine = when {
            ApiConfig.isAzureTtsConfigured -> AZURE
            ApiConfig.isAsrConfigured -> VOLC
            else -> SYSTEM
        }

        fun isAvailable(engine: TtsEngine): Boolean = when (engine) {
            AZURE -> ApiConfig.isAzureTtsConfigured
            VOLC -> ApiConfig.isAsrConfigured
            SYSTEM -> true
        }
    }
}
