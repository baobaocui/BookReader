package com.bookreader.app

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调用火山方舟豆包视觉模型：理解书页版面、阅读顺序与正文/非正文区分。
 */
class DoubaoVisionClient(
    private val apiKey: String = ApiConfig.apiKey,
    private val modelId: String = ApiConfig.modelId
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun extractReadableText(
        bitmap: Bitmap,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        fun progress(msg: String) {
            Log.e(TAG, msg)
            onProgress?.invoke(msg)
        }

        if (apiKey.isBlank() || modelId.isBlank()) {
            throw IllegalStateException("未配置豆包 API Key / Model ID")
        }

        progress("编码图片中… ${bitmap.width}x${bitmap.height}")
        val dataUrl = bitmapToJpegDataUrl(bitmap)
        val jpegKb = ((dataUrl.length - PREFIX.length) * 3) / 4 / 1024
        progress("开始请求豆包 model=$modelId jpeg≈${jpegKb}KB（已关闭深度思考）")

        val body = buildRequestBody(dataUrl)
        progress("请求体已组装 ${body.length / 1024}KB，正在上传…")

        val request = Request.Builder()
            .url(ApiConfig.BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMedia))
            .build()

        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = System.currentTimeMillis() - startedAt
                val raw = response.body?.string().orEmpty()
                progress("HTTP ${response.code} 耗时 ${elapsedMs}ms 响应 ${raw.length} 字")

                if (!response.isSuccessful) {
                    throw IllegalStateException("豆包接口错误 ${response.code}: ${raw.take(300)}")
                }

                logUsageIfPresent(raw)
                val text = parseContent(raw)
                if (text.isBlank()) {
                    throw IllegalStateException("模型未返回可读正文: ${raw.take(200)}")
                }
                progress("成功 textLen=${text.length}")
                text
            }
        } catch (e: Exception) {
            progress("失败: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun logUsageIfPresent(raw: String) {
        try {
            val usage = JSONObject(raw).optJSONObject("usage") ?: return
            Log.e(
                TAG,
                "token用量: prompt=${usage.optInt("prompt_tokens")} " +
                    "completion=${usage.optInt("completion_tokens")} " +
                    "total=${usage.optInt("total_tokens")}"
            )
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun buildRequestBody(imageDataUrl: String): String {
        // 避免 JSONObject 嵌套拷贝巨大 base64，手动拼接图片段
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", SYSTEM_PROMPT)
            .toString()
        val imagePart =
            """{"type":"image_url","image_url":{"url":"$imageDataUrl"}}"""
        return """
            {
              "model":"$modelId",
              "temperature":0.2,
              "thinking":{"type":"disabled"},
              "messages":[{
                "role":"user",
                "content":[$textPart,$imagePart]
              }]
            }
        """.trimIndent().replace("\n", "")
    }

    private fun parseContent(raw: String): String {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val message = choices.getJSONObject(0).optJSONObject("message") ?: return ""
        val content = message.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> {
                buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") {
                            append(part.optString("text"))
                        }
                    }
                }
            }
            else -> content?.toString().orEmpty()
        }
        return text
            .replace(Regex("^```[\\w]*\\n?"), "")
            .replace(Regex("\\n?```$"), "")
            .trim()
    }

    private fun bitmapToJpegDataUrl(source: Bitmap): String {
        val scaled = scaleForUpload(source)
        val stream = ByteArrayOutputStream()
        var quality = 65
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        while (stream.size() > 600_000 && quality > 40) {
            stream.reset()
            quality -= 8
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
        if (scaled !== source) {
            scaled.recycle()
        }
        Log.e(TAG, "图片编码完成: jpegBytes=${stream.size()} quality=$quality")
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return PREFIX + base64
    }

    private fun scaleForUpload(source: Bitmap): Bitmap {
        val maxSide = 1024
        val w = source.width
        val h = source.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        Log.e(TAG, "缩放图片: ${w}x${h} -> 最长边=$maxSide")
        return Bitmap.createScaledBitmap(
            source,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val TAG = "BookReader"
        private const val PREFIX = "data:image/jpeg;base64,"

        private val SYSTEM_PROMPT = """
你是纸质书朗读助手。用户拍了一页书，请仔细看图后完成任务。

要求：
1. 按人类正常阅读顺序提取「正文」（中文从左到右、从上到下；若是双栏则先左栏后右栏）。
2. 只要适合朗读的正文内容；跳过页眉、页脚、页码、装饰线。
3. 区分并忽略：生词表、单词注释、旁注、脚注、练习题选项编号旁的提示、与正文无关的小字说明。若某块明显是「单词 / vocabulary / 注释」而不是故事或课文主体，不要读出来。
4. 保留段落换行，不要添加解释、标题或「如下所示」等套话。
5. 若几乎看不清文字，只输出：无法识别
6. 直接输出要朗读的纯文本，不要用 Markdown 代码块包裹。
        """.trimIndent()
    }
}
