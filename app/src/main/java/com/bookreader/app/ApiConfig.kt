package com.bookreader.app

/**
 * 配置来自项目根目录 local.properties。
 *
 * 读页（豆包视觉）:
 *   DOUBAO_API_KEY / DOUBAO_MODEL_ID
 *
 * 语音指令（火山语音 BigASR，与方舟 Key 不是同一套）:
 *   VOLC_ASR_APP_ID / VOLC_ASR_ACCESS_TOKEN
 */
object ApiConfig {
    const val BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    const val ASR_STREAM_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"

    val apiKey: String get() = BuildConfig.DOUBAO_API_KEY.trim()
    val modelId: String get() = BuildConfig.DOUBAO_MODEL_ID.trim()
    val asrAppId: String get() = BuildConfig.VOLC_ASR_APP_ID.trim()
    val asrAccessToken: String get() = BuildConfig.VOLC_ASR_ACCESS_TOKEN.trim()

    val isConfigured: Boolean get() = apiKey.isNotEmpty() && modelId.isNotEmpty()
    val isAsrConfigured: Boolean get() = asrAppId.isNotEmpty() && asrAccessToken.isNotEmpty()
}
