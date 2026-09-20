package com.bookreader.app

import android.util.Base64
import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import org.json.JSONObject

/**
 * 豆包语音合成 2.0 单向流式（HTTP Chunked / NDJSON）。
 * 鉴权复用语音应用的 App ID + Access Token，资源 ID 为 seed-tts-2.0。
 */
class VolcTtsClient(
    private val appId: String = ApiConfig.asrAppId,
    private val accessToken: String = ApiConfig.asrAccessToken
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val activeCall = AtomicReference<Call?>(null)

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    fun streamPcm(text: String, isActive: () -> Boolean, onPcm: (ByteArray) -> Unit) {
        if (appId.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("未配置 VOLC_ASR_APP_ID / VOLC_ASR_ACCESS_TOKEN")
        }
        val additions = JSONObject()
            .put("context_texts", org.json.JSONArray().put("用平静、自然的语气朗读，像在给人读书。"))
            .toString()
        val body = JSONObject()
            .put("user", JSONObject().put("uid", "bookreader"))
            .put(
                "req_params",
                JSONObject()
                    .put("text", text)
                    .put("speaker", ApiConfig.TTS_SPEAKER)
                    .put(
                        "audio_params",
                        JSONObject()
                            .put("format", "pcm")
                            .put("sample_rate", SAMPLE_RATE)
                    )
                    .put("additions", additions)
            )
        val request = Request.Builder()
            .url(ApiConfig.TTS_URL)
            .addHeader("X-Api-App-Id", appId)
            .addHeader("X-Api-Access-Key", accessToken)
            .addHeader("X-Api-Resource-Id", ApiConfig.TTS_RESOURCE_ID)
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val call = client.newCall(request)
        activeCall.set(call)
        try {
            if (!isActive()) {
                call.cancel()
                return
            }
            call.execute().use { response ->
                val logId = response.header("X-Tt-Logid").orEmpty()
                val source = response.body?.source()
                    ?: throw IllegalStateException("语音合成无响应")
                if (!response.isSuccessful) {
                    val err = source.readUtf8().take(300)
                    throw IllegalStateException("语音合成 HTTP ${response.code}: $err")
                }
                var gotAudio = false
                while (isActive()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val json = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        throw IllegalStateException("语音合成返回无法解析 logid=$logId")
                    }
                    val code = json.optInt("code", -1)
                    when {
                        code == 0 -> {
                            val data = json.optString("data")
                            if (data.isNotEmpty() && data != "null") {
                                gotAudio = true
                                onPcm(Base64.decode(data, Base64.DEFAULT))
                            }
                        }
                        code == CODE_DONE -> {
                            if (!gotAudio) {
                                throw IllegalStateException("语音合成没有返回音频 logid=$logId")
                            }
                            return
                        }
                        else -> {
                            val message = json.optString("message").ifBlank { "语音合成失败($code)" }
                            Log.e(TAG, "TTS error code=$code logid=$logId msg=$message")
                            throw IllegalStateException(message)
                        }
                    }
                }
                if (isActive() && !gotAudio) {
                    throw IllegalStateException("语音合成中断 logid=$logId")
                }
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    companion object {
        private const val TAG = "BookReader"
        const val SAMPLE_RATE = 24000
        private const val CODE_DONE = 20000000
    }
}
