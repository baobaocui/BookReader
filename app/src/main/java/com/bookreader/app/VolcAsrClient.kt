package com.bookreader.app

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * 豆包流式语音识别 2.0 小时版（nostream）。
 * URL: wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream
 * Resource: volc.seedasr.sauc.duration
 *
 * 协议要点：full client request 无序号；服务端 ack 的 sequence=1；
 * 随后音频包 sequence 必须从 2 递增，最后一包用负数序号 + last 标记。
 */
class VolcAsrClient(
    private val appId: String = ApiConfig.asrAppId,
    private val accessToken: String = ApiConfig.asrAccessToken
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspend fun recognizeWav(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (appId.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("未配置 VOLC_ASR_APP_ID / VOLC_ASR_ACCESS_TOKEN")
        }
        val pcm = wavToPcm(wavBytes)
        if (pcm.size < 3200) {
            throw IllegalStateException("录音太短，请多说一会儿再结束")
        }
        Log.e(TAG, "ASR pcmBytes=${pcm.size}")
        streamRecognize(pcm)
    }

    private suspend fun streamRecognize(pcm: ByteArray): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(ApiConfig.ASR_STREAM_URL)
            .addHeader("X-Api-App-Key", appId)
            .addHeader("X-Api-Access-Key", accessToken)
            .addHeader("X-Api-Resource-Id", RESOURCE_ID)
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
            .build()

        val audioStarted = AtomicBoolean(false)
        var bestText = ""

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.e(TAG, "ASR ws open")
                webSocket.send(fullClientRequest().toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                try {
                    val parsed = parseServerMessage(bytes.toByteArray())
                    if (parsed.error != null) {
                        resumeError(cont, parsed.error)
                        webSocket.close(1000, "error")
                        return
                    }
                    if (parsed.text.isNotBlank()) {
                        bestText = parsed.text
                        Log.e(TAG, "ASR text=${parsed.text.take(80)} last=${parsed.isLast}")
                    }

                    // 收到 seq=1 的 ack 后再发音频（只发一次）
                    if (parsed.sequence == 1 && audioStarted.compareAndSet(false, true)) {
                        sendAudio(webSocket, pcm)
                    }

                    if (parsed.isLast) {
                        if (bestText.isBlank()) {
                            resumeError(cont, "没有识别到语音内容，请再说一次")
                        } else {
                            resumeOk(cont, bestText)
                        }
                        webSocket.close(1000, "done")
                    }
                } catch (e: Exception) {
                    resumeError(cont, e.message ?: "ASR 解析失败")
                    webSocket.close(1000, "parse")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val extra = try {
                    response?.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                Log.e(TAG, "ASR failure ${t.message} $extra", t)
                resumeError(cont, listOfNotNull(t.message, extra.takeIf { it.isNotBlank() }).joinToString(" "))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (bestText.isNotBlank()) resumeOk(cont, bestText)
                else resumeError(cont, "ASR 关闭: $reason")
            }
        })

        cont.invokeOnCancellation { socket.cancel() }
    }

    private fun resumeOk(cont: CancellableContinuation<String>, text: String) {
        if (cont.isActive) cont.resume(text)
    }

    private fun resumeError(cont: CancellableContinuation<String>, msg: String) {
        if (cont.isActive) cont.resumeWithException(IllegalStateException(msg))
    }

    private fun sendAudio(webSocket: WebSocket, pcm: ByteArray) {
        val chunk = 3200
        var seq = 2
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunk, pcm.size)
            val last = end >= pcm.size
            val part = pcm.copyOfRange(offset, end)
            val packet = audioPacket(part, if (last) -seq else seq, last)
            webSocket.send(packet.toByteString())
            offset = end
            seq += 1
        }
        Log.e(TAG, "ASR audio sent, lastSeq=${seq - 1}")
    }

    private fun fullClientRequest(): ByteArray {
        val json = JSONObject()
            .put("user", JSONObject().put("uid", "bookreader"))
            .put(
                "audio",
                JSONObject()
                    .put("format", "pcm")
                    .put("codec", "raw")
                    .put("rate", 16000)
                    .put("bits", 16)
                    .put("channel", 1)
                    .put("language", "zh-CN")
            )
            .put(
                "request",
                JSONObject()
                    .put("model_name", "bigmodel")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("result_type", "full")
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        return packet(0x1, 0x0, 0x1, json)
    }

    private fun audioPacket(pcm: ByteArray, sequence: Int, last: Boolean): ByteArray {
        val flags = if (last) 0x3 else 0x1
        return packet(0x2, flags, 0x0, pcm, sequence)
    }

    private fun packet(
        messageType: Int,
        flags: Int,
        serialization: Int,
        payload: ByteArray,
        sequence: Int? = null
    ): ByteArray {
        val header = byteArrayOf(
            0x11,
            ((messageType shl 4) or (flags and 0x0F)).toByte(),
            ((serialization shl 4) and 0xF0).toByte(),
            0x00
        )
        val out = java.io.ByteArrayOutputStream()
        out.write(header)
        if (sequence != null && (flags and 0x1) == 0x1) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(sequence).array())
        }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
        out.write(payload)
        return out.toByteArray()
    }

    private data class Parsed(
        val text: String,
        val isLast: Boolean,
        val error: String?,
        val sequence: Int?
    )

    private fun parseServerMessage(data: ByteArray): Parsed {
        if (data.size < 8) return Parsed("", false, "ASR 响应过短", null)
        val messageType = (data[1].toInt() ushr 4) and 0x0F
        val flags = data[1].toInt() and 0x0F
        var offset = 4
        var sequence: Int? = null
        if (flags and 0x1 == 0x1) {
            sequence = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
        }
        if (messageType == 0xF) {
            if (offset + 8 > data.size) return Parsed("", true, "ASR 错误包", sequence)
            val code = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
            val size = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
            val msg = if (size > 0 && offset + size <= data.size) {
                String(data, offset, size, Charsets.UTF_8)
            } else ""
            return Parsed("", true, "ASR 错误 $code $msg", sequence)
        }
        if (offset + 4 > data.size) return Parsed("", false, null, sequence)
        val size = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        offset += 4
        if (size <= 0 || offset + size > data.size) {
            return Parsed("", flags and 0x2 == 0x2, null, sequence)
        }
        val json = String(data, offset, size, Charsets.UTF_8)
        val text = runCatching {
            val root = JSONObject(json)
            root.optJSONObject("result")?.optString("text").orEmpty()
                .ifBlank { root.optString("text") }
        }.getOrDefault("")
        return Parsed(text, flags and 0x2 == 0x2, null, sequence)
    }

    private fun wavToPcm(wav: ByteArray): ByteArray {
        if (wav.size > 44 && wav[0] == 'R'.code.toByte() && wav[1] == 'I'.code.toByte()) {
            return wav.copyOfRange(44, wav.size)
        }
        return wav
    }

    companion object {
        private const val TAG = "BookReader"
        private const val RESOURCE_ID = "volc.seedasr.sauc.duration"
    }
}
