package com.bookreader.app

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class PageReader(private val context: Context) {
    private val visionClient = DoubaoVisionClient()
    private val volcTts = VolcTtsClient()
    private val azureTts = AzureTtsClient()
    private val prefs = TtsPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onSpeakDone: (() -> Unit)? = null
    private var systemDone: (() -> Unit)? = null
    private var speakJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private var pendingByte: Int = -1
    private var framesWritten = 0
    private var playSampleRate = CLOUD_SAMPLE_RATE

    fun currentEngine(): TtsEngine = prefs.getEngine()

    fun setEngine(engine: TtsEngine) {
        prefs.setEngine(engine)
    }

    fun initTts(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                applySystemLocale(prefs.getEngine())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null && !utteranceId.startsWith("chunk_")) {
                            finishSystem()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finishSystem()
                    }
                })
            }
            onReady(ttsReady)
        }
    }

    suspend fun recognizeText(
        bitmap: Bitmap,
        onProgress: ((String) -> Unit)? = null
    ): String = visionClient.extractReadableText(bitmap, onProgress)

    fun speak(
        text: String,
        onProgress: ((String) -> Unit)? = null,
        onDone: () -> Unit
    ) {
        cancelActive(notify = false)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone()
            return
        }
        onSpeakDone = onDone
        val requested = prefs.getEngine()
        val engine = resolveEngine(requested)
        if (engine != requested) {
            onProgress?.invoke("${requested.shortLabel}未配置，改用${engine.shortLabel}")
        }
        speakJob = scope.launch {
            try {
                when (engine) {
                    TtsEngine.AZURE -> {
                        onProgress?.invoke("正在合成朗读（Azure）…")
                        withContext(Dispatchers.IO) {
                            playAzure(trimmed) {
                                onProgress?.invoke("正在朗读…")
                            }
                        }
                    }
                    TtsEngine.VOLC -> {
                        val rateLabel = SpeechPrefs.format(SpeechPrefs.getSpeed(context))
                        onProgress?.invoke(
                            "正在合成朗读（${ApiConfig.TTS_SPEAKER_NAME} $rateLabel）…"
                        )
                        withContext(Dispatchers.IO) {
                            playVolc(trimmed) {
                                onProgress?.invoke("正在朗读…")
                            }
                        }
                    }
                    TtsEngine.SYSTEM -> {
                        onProgress?.invoke("正在朗读（系统）…")
                        speakWithSystem(trimmed)
                    }
                }
                finishSpeak()
            } catch (_: CancellationException) {
                // 停止按钮已经回调过
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.e(TAG, "${engine.shortLabel}朗读失败，改用系统语音", e)
                releaseTrack()
                onProgress?.invoke("${engine.shortLabel}朗读失败，改用系统语音")
                try {
                    speakWithSystem(trimmed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fallback: Exception) {
                    Log.e(TAG, "系统朗读也失败", fallback)
                }
                finishSpeak()
            }
        }
    }

    fun stopSpeaking() {
        cancelActive(notify = true)
    }

    fun release() {
        cancelActive(notify = false)
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun resolveEngine(requested: TtsEngine): TtsEngine {
        if (TtsEngine.isAvailable(requested)) return requested
        return TtsEngine.preferredDefault().let {
            if (TtsEngine.isAvailable(it)) it else TtsEngine.SYSTEM
        }
    }

    private fun playAzure(text: String, onFirstAudio: () -> Unit) {
        playSampleRate = AzureTtsClient.SAMPLE_RATE
        resetTrack()
        var started = false
        for (chunk in chunkForTts(text, maxLen = 1200)) {
            if (speakJob?.isActive != true) return
            val pcm = azureTts.synthesizePcm(chunk) { speakJob?.isActive == true }
            if (pcm.isEmpty()) continue
            if (!started) {
                started = true
                onFirstAudio()
            }
            writePcm(pcm)
        }
        awaitPlayback()
    }

    private fun playVolc(text: String, onFirstAudio: () -> Unit) {
        playSampleRate = VolcTtsClient.SAMPLE_RATE
        resetTrack()
        val speechRate = SpeechPrefs.toApiSpeechRate(SpeechPrefs.getSpeed(context))
        var started = false
        for (chunk in chunkForTts(text, maxLen = 300)) {
            if (speakJob?.isActive != true) return
            streamVolcChunk(chunk, speechRate) { pcm ->
                if (!started) {
                    started = true
                    onFirstAudio()
                }
                writePcm(pcm)
            }
        }
        awaitPlayback()
    }

    private fun streamVolcChunk(text: String, speechRate: Int, onPcm: (ByteArray) -> Unit) {
        if (text.isBlank() || speakJob?.isActive != true) return
        try {
            volcTts.streamPcm(
                text,
                speechRate = speechRate,
                isActive = { speakJob?.isActive == true },
                onPcm = onPcm
            )
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            val tooLong = message.contains("ExceededTextLimit", ignoreCase = true) ||
                message.contains("max limit", ignoreCase = true)
            if (!tooLong || text.length < 40) throw e
            val mid = text.length / 2
            streamVolcChunk(text.substring(0, mid).trim(), speechRate, onPcm)
            streamVolcChunk(text.substring(mid).trim(), speechRate, onPcm)
        }
    }

    private fun writePcm(chunk: ByteArray) {
        if (chunk.isEmpty() || speakJob?.isActive != true) return
        val track = ensureTrack()
        var data = chunk
        if (pendingByte >= 0) {
            data = ByteArray(chunk.size + 1)
            data[0] = pendingByte.toByte()
            System.arraycopy(chunk, 0, data, 1, chunk.size)
            pendingByte = -1
        }
        var length = data.size
        if (length % 2 != 0) {
            pendingByte = data[length - 1].toInt() and 0xFF
            length -= 1
        }
        if (length <= 0) return
        var offset = 0
        while (offset < length) {
            val wrote = track.write(data, offset, length - offset)
            if (wrote < 0) throw IllegalStateException("播放失败($wrote)")
            if (wrote == 0) break
            offset += wrote
            framesWritten += wrote / 2
        }
    }

    private fun ensureTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) return existing
        val rate = playSampleRate
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val min = if (minBuffer > 0) minBuffer else rate
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(min.coerceAtLeast(rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
        return track
    }

    private fun awaitPlayback() {
        val track = audioTrack ?: return
        val target = framesWritten
        val rate = playSampleRate.coerceAtLeast(1)
        val timeoutAt = SystemClock.elapsedRealtime() + target * 1000L / rate + 1500
        while (
            speakJob?.isActive == true &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition < target &&
            SystemClock.elapsedRealtime() < timeoutAt
        ) {
            Thread.sleep(40)
        }
    }

    private fun resetTrack() {
        releaseTrack()
        pendingByte = -1
        framesWritten = 0
    }

    private fun releaseTrack() {
        val track = audioTrack
        audioTrack = null
        if (track == null) return
        try {
            track.pause()
            track.flush()
            track.release()
        } catch (_: Exception) {
        }
    }

    private suspend fun speakWithSystem(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) return
        applySystemLocale(TtsEngine.SYSTEM, text)
        suspendCancellableCoroutine { cont ->
            systemDone = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation {
                systemDone = null
                tts?.stop()
            }
            val chunks = text.chunked(300)
            if (chunks.isEmpty()) {
                finishSystem()
                return@suspendCancellableCoroutine
            }
            engine.setSpeechRate(SpeechPrefs.getSpeed(context))
            val lastId = UUID.randomUUID().toString()
            chunks.forEachIndexed { index, chunk ->
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = if (index == chunks.lastIndex) lastId else "chunk_$index"
                engine.speak(chunk, mode, null, id)
            }
        }
    }

    private fun applySystemLocale(engine: TtsEngine, text: String = "") {
        val locale = when {
            engine != TtsEngine.SYSTEM -> Locale.SIMPLIFIED_CHINESE
            text.isNotBlank() && AzureTtsClient.preferEnglish(text) -> Locale.US
            else -> Locale.SIMPLIFIED_CHINESE
        }
        tts?.language = locale
    }

    private fun finishSystem() {
        val done = systemDone
        systemDone = null
        done?.invoke()
    }

    private fun finishSpeak() {
        val done = onSpeakDone
        onSpeakDone = null
        done?.invoke()
    }

    private fun cancelActive(notify: Boolean) {
        volcTts.cancel()
        azureTts.cancel()
        speakJob?.cancel()
        speakJob = null
        systemDone = null
        releaseTrack()
        tts?.stop()
        if (notify) finishSpeak() else onSpeakDone = null
    }

    private fun chunkForTts(text: String, maxLen: Int = 300): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            val boundary = ch == '。' || ch == '！' || ch == '？' || ch == '\n' ||
                ch == '；' || ch == '.' || ch == '!' || ch == '?' || ch == ';'
            if ((boundary && buf.length >= 80) || buf.length >= maxLen) {
                val piece = buf.toString().trim()
                if (piece.isNotEmpty()) parts.add(piece)
                buf.clear()
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)
        return parts
    }

    companion object {
        private const val TAG = "BookReader"
        private const val CLOUD_SAMPLE_RATE = 24000
    }
}
