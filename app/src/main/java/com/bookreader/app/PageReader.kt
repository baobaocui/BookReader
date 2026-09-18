package com.bookreader.app

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class PageReader(private val context: Context) {
    private val visionClient = DoubaoVisionClient()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onSpeakDone: (() -> Unit)? = null

    fun initTts(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null && !utteranceId.startsWith("chunk_")) {
                            val done = onSpeakDone
                            onSpeakDone = null
                            done?.invoke()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val done = onSpeakDone
                        onSpeakDone = null
                        done?.invoke()
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

    fun speak(text: String, onDone: () -> Unit) {
        onSpeakDone = onDone
        val engine = tts
        if (!ttsReady || engine == null) {
            onDone()
            return
        }
        val chunks = text.chunked(300)
        if (chunks.isEmpty()) {
            onDone()
            return
        }
        val lastId = UUID.randomUUID().toString()
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (index == chunks.lastIndex) lastId else "chunk_$index"
            engine.speak(chunk, mode, null, id)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        onSpeakDone?.invoke()
        onSpeakDone = null
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
