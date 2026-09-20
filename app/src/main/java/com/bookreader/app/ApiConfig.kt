package com.bookreader.app

/**
 * 配置来自项目根目录 local.properties。
 *
 * 读页（豆包视觉）:
 *   DOUBAO_API_KEY / DOUBAO_MODEL_ID
 *
 * 语音指令与朗读（火山语音应用，与方舟 Key 不是同一套）:
 *   VOLC_ASR_APP_ID / VOLC_ASR_ACCESS_TOKEN
 * 朗读走豆包语音合成 2.0 字符版，音色是公版「小何」。
 */
object ApiConfig {
    const val BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    const val ASR_STREAM_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
    const val TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    const val TTS_RESOURCE_ID = "seed-tts-2.0"
    const val TTS_SPEAKER = "zh_female_xiaohe_uranus_bigtts"
    const val TTS_SPEAKER_NAME = "小何"

    val apiKey: String get() = BuildConfig.DOUBAO_API_KEY.trim()
    val modelId: String get() = BuildConfig.DOUBAO_MODEL_ID.trim()
    val asrAppId: String get() = BuildConfig.VOLC_ASR_APP_ID.trim()
    val asrAccessToken: String get() = BuildConfig.VOLC_ASR_ACCESS_TOKEN.trim()

    val isConfigured: Boolean get() = apiKey.isNotEmpty() && modelId.isNotEmpty()
    val isAsrConfigured: Boolean get() = asrAppId.isNotEmpty() && asrAccessToken.isNotEmpty()
}
