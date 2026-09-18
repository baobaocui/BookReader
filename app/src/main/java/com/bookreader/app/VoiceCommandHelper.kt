package com.bookreader.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 语音指令：优先 SpeechRecognizer（不依赖系统语音弹窗）。
 * 若本机完全没有识别服务，回调 [onUnavailable]，由界面改走「直接读页」。
 */
class VoiceCommandHelper(
    context: Context,
    private val onPartial: (String) -> Unit = {},
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onUnavailable: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    fun hasRecognitionService(): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) return true
        val services = appContext.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0
        )
        return services.isNotEmpty()
    }

    fun hasRecognitionActivity(): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return intent.resolveActivity(appContext.packageManager) != null
    }

    /** 点一下开始听，说完自动出结果（不必按住）。 */
    fun startListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening() }
            return
        }
        if (!hasRecognitionService()) {
            Log.e(TAG, "no recognition service")
            onUnavailable()
            return
        }

        destroyRecognizer()
        finished = false

        val speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "createSpeechRecognizer failed", e)
            onUnavailable()
            return
        }
        recognizer = speechRecognizer

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.e(TAG, "ready")
            }

            override fun onBeginningOfSpeech() {
                Log.e(TAG, "beginning")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                Log.e(TAG, "endOfSpeech")
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeoutRunnable)
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                Log.e(TAG, "results=$text")
                destroyRecognizer()
                if (text.isBlank()) onError("没有听清，请再说一次或点「读当前页」")
                else onResult(text)
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "error=$error")
                destroyRecognizer()
                // 无引擎 / 客户端失败 → 走直接读页，避免卡死
                if (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                ) {
                    onUnavailable()
                } else {
                    onError(errorMessage(error))
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
        }

        try {
            speechRecognizer.startListening(intent)
            mainHandler.postDelayed(timeoutRunnable, 15_000)
            Log.e(TAG, "startListening ok")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            destroyRecognizer()
            onUnavailable()
        }
    }

    fun cancel() {
        finished = true
        mainHandler.removeCallbacks(timeoutRunnable)
        destroyRecognizer()
    }

    fun release() = cancel()

    private val timeoutRunnable = Runnable {
        if (finished) return@Runnable
        finished = true
        destroyRecognizer()
        onError("听写超时，请改点「读当前页」")
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.setRecognitionListener(null)
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请点「读当前页」"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "语音识别需要联网，或直接点「读当前页」"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说或点「读当前页」"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没检测到说话，请再说或点「读当前页」"
        SpeechRecognizer.ERROR_SERVER -> "语音服务异常，请点「读当前页」"
        else -> "语音失败($error)，请点「读当前页」"
    }

    companion object {
        private const val TAG = "BookReader"
    }
}
