package com.bookreader.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云端语音指令：点一次开始录音，再点一次结束并识别。
 * 不依赖华为/系统 SpeechRecognizer。
 */
class CloudVoiceCommander {
    private val recorder = PcmWavRecorder()
    private val asr = VolcAsrClient()

    val isRecording: Boolean get() = recorder.isRecording

    fun startRecording() {
        if (!ApiConfig.isAsrConfigured) {
            throw IllegalStateException("未配置云端 ASR")
        }
        recorder.start()
        Log.e(TAG, "cloud voice recording…")
    }

    suspend fun stopAndRecognize(): String = withContext(Dispatchers.IO) {
        val wav = recorder.stopToWav()
        asr.recognizeWav(wav)
    }

    fun cancel() {
        recorder.cancel()
    }

    companion object {
        private const val TAG = "BookReader"
    }
}
