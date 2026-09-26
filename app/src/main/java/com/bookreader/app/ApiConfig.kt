package com.bookreader.app

/**
 * 配置来自项目根目录 local.properties。
 *
 * 读页（豆包视觉）:
 *   DOUBAO_API_KEY / DOUBAO_MODEL_ID
 *
 * 语音指令 ASR + 豆包朗读（火山语音应用，与方舟 Key 不是同一套）:
 *   VOLC_ASR_APP_ID / VOLC_ASR_ACCESS_TOKEN
 *
 * Azure 神经网络朗读（英文优先，更便宜）:
 *   AZURE_SPEECH_KEY / AZURE_SPEECH_REGION
 *   可选：AZURE_SPEECH_ENDPOINT、AZURE_TTS_VOICE_EN、AZURE_TTS_VOICE_ZH
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

    val azureSpeechKey: String get() = BuildConfig.AZURE_SPEECH_KEY.trim()
    val azureSpeechRegion: String get() = BuildConfig.AZURE_SPEECH_REGION.trim()
    val azureSpeechEndpoint: String get() = BuildConfig.AZURE_SPEECH_ENDPOINT.trim()
    val azureVoiceEn: String
        get() = BuildConfig.AZURE_TTS_VOICE_EN.trim()
            .ifBlank { "en-US-JennyNeural" }
    val azureVoiceZh: String
        get() = BuildConfig.AZURE_TTS_VOICE_ZH.trim()
            .ifBlank { "zh-CN-XiaoxiaoNeural" }

    val isConfigured: Boolean get() = apiKey.isNotEmpty() && modelId.isNotEmpty()
    val isAsrConfigured: Boolean get() = asrAppId.isNotEmpty() && asrAccessToken.isNotEmpty()
    val isAzureTtsConfigured: Boolean
        get() = azureSpeechKey.isNotEmpty() &&
            (azureSpeechRegion.isNotEmpty() || azureSpeechEndpoint.isNotEmpty())
}
