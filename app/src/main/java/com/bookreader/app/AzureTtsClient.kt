package com.bookreader.app

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Azure 神经网络 TTS（REST + SSML）。
 * 英文优先：拉丁字母占优时用 en 音色，否则用 zh 音色。
 * 输出 raw 24kHz 16-bit mono PCM，与豆包云端播放链路一致。
 */
class AzureTtsClient(
    private val key: String = ApiConfig.azureSpeechKey,
    private val region: String = ApiConfig.azureSpeechRegion,
    private val endpointOverride: String = ApiConfig.azureSpeechEndpoint,
    private val voiceEn: String = ApiConfig.azureVoiceEn,
    private val voiceZh: String = ApiConfig.azureVoiceZh
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val ssmlMedia = "application/ssml+xml; charset=utf-8".toMediaType()
    private val activeCall = AtomicReference<Call?>(null)

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    fun synthesizePcm(text: String, isActive: () -> Boolean): ByteArray {
        if (key.isBlank() || (region.isBlank() && endpointOverride.isBlank())) {
            throw IllegalStateException("未配置 AZURE_SPEECH_KEY / AZURE_SPEECH_REGION")
        }
        if (!isActive() || text.isBlank()) return ByteArray(0)

        val english = preferEnglish(text)
        val voice = if (english) voiceEn else voiceZh
        val lang = if (english) "en-US" else "zh-CN"
        val ssml = buildSsml(text, voice, lang)
        val url = endpointOverride.ifBlank { defaultEndpoint(region) }

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", key)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .addHeader("User-Agent", "BookReaderAndroid")
            .post(ssml.toRequestBody(ssmlMedia))
            .build()

        val call = client.newCall(request)
        activeCall.set(call)
        try {
            if (!isActive()) {
                call.cancel()
                return ByteArray(0)
            }
            call.execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    val err = body.toString(Charsets.UTF_8).take(400)
                    throw IllegalStateException("Azure TTS HTTP ${response.code}: $err")
                }
                if (body.isEmpty()) {
                    throw IllegalStateException("Azure TTS 返回空音频")
                }
                return body
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    companion object {
        const val SAMPLE_RATE = 24000
        private const val OUTPUT_FORMAT = "raw-24khz-16bit-mono-pcm"

        fun preferEnglish(text: String): Boolean {
            var latin = 0
            var cjk = 0
            for (ch in text) {
                when {
                    ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> cjk++
                }
            }
            return latin >= cjk
        }

        fun defaultEndpoint(region: String): String {
            val r = region.trim().lowercase()
            require(r.isNotEmpty()) { "AZURE_SPEECH_REGION 为空" }
            val host = if (r.startsWith("china")) {
                "$r.tts.speech.azure.cn"
            } else {
                "$r.tts.speech.microsoft.com"
            }
            return "https://$host/cognitiveservices/v1"
        }

        private fun buildSsml(text: String, voice: String, lang: String): String {
            val escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
            return """
                <speak version='1.0' xml:lang='$lang'>
                  <voice name='$voice'>$escaped</voice>
                </speak>
            """.trimIndent()
        }
    }
}
