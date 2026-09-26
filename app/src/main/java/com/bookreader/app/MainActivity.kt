package com.bookreader.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bookreader.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var pageReader: PageReader
    private val cloudVoice = CloudVoiceCommander()
    private val pageTurnDetector = PageTurnDetector {
        runOnUiThread { onAutoPageTurn() }
    }

    private var imageCapture: ImageCapture? = null
    private var isProcessing = false
    private var watchTurns = false
    private var pageTurnAvailable = false
    private var readGeneration = 0
    private var lastReadText = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            startCamera()
        } else {
            setStatus(getString(R.string.permission_required))
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()
        pageReader = PageReader(this)
        pageReader.initTts { ready ->
            if (!ready) {
                runOnUiThread {
                    Toast.makeText(this, "TTS 初始化失败，仍可识别文字", Toast.LENGTH_SHORT).show()
                }
            }
        }
        refreshTtsButton()

        // 启动时打印语音诊断，方便逐步调试
        binding.resultText.text = VoiceDiagnostics.report(this)

        binding.btnTtsEngine.setOnClickListener { showTtsPicker() }
        setupSpeechRateBar()

        binding.btnHoldSpeak.setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            onVoiceButtonClicked()
        }

        binding.btnReadNow.setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            captureAndRead()
        }

        binding.btnStop.setOnClickListener {
            readGeneration++
            watchTurns = false
            lastReadText = ""
            pageTurnDetector.disarm()
            pageReader.stopSpeaking()
            cloudVoice.cancel()
            isProcessing = false
            setStatus(getString(R.string.status_ready))
            binding.btnHoldSpeak.text = getString(R.string.btn_hold_speak)
        }

        if (!ApiConfig.isConfigured) {
            setStatus(getString(R.string.api_not_configured))
            Toast.makeText(this, R.string.api_not_configured, Toast.LENGTH_LONG).show()
        }

        if (hasPermissions()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    private fun refreshTtsButton() {
        val engine = pageReader.currentEngine()
        binding.btnTtsEngine.text = getString(R.string.btn_tts_engine, engine.shortLabel)
    }

    private fun showTtsPicker() {
        val engines = TtsEngine.entries
        val labels = engines.map { engine ->
            val mark = if (TtsEngine.isAvailable(engine)) "" else "（未配置）"
            engine.label + mark
        }.toTypedArray()
        val checked = engines.indexOf(pageReader.currentEngine()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.tts_pick_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val picked = engines[which]
                when {
                    picked == TtsEngine.AZURE && !ApiConfig.isAzureTtsConfigured -> {
                        Toast.makeText(this, R.string.tts_azure_not_configured, Toast.LENGTH_LONG)
                            .show()
                    }
                    picked == TtsEngine.VOLC && !ApiConfig.isAsrConfigured -> {
                        Toast.makeText(this, R.string.tts_volc_not_configured, Toast.LENGTH_LONG)
                            .show()
                    }
                    else -> {
                        pageReader.setEngine(picked)
                        refreshTtsButton()
                        Toast.makeText(
                            this,
                            getString(R.string.tts_switched, picked.label),
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupSpeechRateBar() {
        val speed = SpeechPrefs.getSpeed(this)
        binding.speechRateBar.max = SpeechPrefs.maxProgress
        binding.speechRateBar.progress = SpeechPrefs.toProgress(speed)
        binding.speechRateValue.text = SpeechPrefs.format(speed)
        binding.speechRateBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.speechRateValue.text = SpeechPrefs.format(SpeechPrefs.fromProgress(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val next = SpeechPrefs.fromProgress(
                    seekBar?.progress ?: SpeechPrefs.toProgress(SpeechPrefs.DEFAULT)
                )
                SpeechPrefs.setSpeed(this@MainActivity, next)
                binding.speechRateValue.text = SpeechPrefs.format(next)
            }
        })
    }

    private fun onVoiceButtonClicked() {
        // 未配置云端 ASR：只展示调试报告，不假装能听
        if (!ApiConfig.isAsrConfigured) {
            binding.resultText.text = VoiceDiagnostics.report(this) +
                "\n下一步：\n" +
                "1. 打开 https://console.volcengine.com/speech/app\n" +
                "2. 创建应用，复制 App ID 与 Access Token\n" +
                "3. 写入 local.properties 的 VOLC_ASR_APP_ID / VOLC_ASR_ACCESS_TOKEN\n" +
                "4. 重新编译安装\n" +
                "5. 点「语音指令」开始录音，再说一次结束"
            setStatus(getString(R.string.asr_not_configured))
            return
        }

        if (cloudVoice.isRecording) {
            // 第二次点击：结束录音并识别
            setStatus("正在识别语音…")
            binding.btnHoldSpeak.text = getString(R.string.btn_hold_speak)
            lifecycleScope.launch {
                try {
                    val text = cloudVoice.stopAndRecognize()
                    handleVoiceResult(text)
                } catch (e: Exception) {
                    Log.e(TAG, "cloud ASR failed", e)
                    isProcessing = false
                    setStatus("语音识别失败：${e.message}")
                    appendDebug("ASR失败：${e.message}")
                    if (watchTurns && pageTurnAvailable) pageTurnDetector.arm()
                }
            }
            return
        }

        if (isProcessing) {
            Toast.makeText(this, "正在处理中，先点「停止」", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            isProcessing = true
            pageTurnDetector.disarm()
            cloudVoice.startRecording()
            setStatus(getString(R.string.status_listening))
            binding.btnHoldSpeak.text = "结束录音"
            appendDebug("开始录音，请说：帮我读这一页")
        } catch (e: Exception) {
            isProcessing = false
            setStatus("无法录音：${e.message}")
        }
    }

    private fun handleVoiceResult(utterance: String) {
        binding.resultText.text = "语音：$utterance"
        isProcessing = false
        if (ReadIntentParser.isReadRequest(utterance)) {
            captureAndRead()
        } else if (watchTurns && pageTurnAvailable) {
            pageTurnDetector.arm()
            setStatus(getString(R.string.status_wait_turn))
        } else {
            setStatus(getString(R.string.intent_not_read))
        }
    }

    private fun onAutoPageTurn() {
        if (isDestroyed || !watchTurns || isProcessing) return
        appendDebug("检测到翻页")
        setStatus(getString(R.string.status_page_turned))
        captureAndRead()
    }

    private fun captureAndRead() {
        if (!ApiConfig.isConfigured) {
            setStatus(getString(R.string.api_not_configured))
            isProcessing = false
            return
        }
        val capture = imageCapture
        if (capture == null) {
            setStatus("相机尚未就绪")
            isProcessing = false
            return
        }
        if (isProcessing) {
            Toast.makeText(this, "正在处理中，可点「停止」后重试", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessing = true
        val generation = readGeneration
        pageTurnDetector.disarm()
        pageTurnDetector.lockCurrentPage()
        setStatus(getString(R.string.status_capture))
        appendDebug("开始读当前页")
        Log.e(TAG, "开始读当前页，takePicture")

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.e(
                        TAG,
                        "拍照成功: ${image.width}x${image.height} rotation=${image.imageInfo.rotationDegrees}"
                    )
                    val bitmap = try {
                        imageProxyToBitmap(image)
                    } finally {
                        image.close()
                    }
                    if (generation != readGeneration) {
                        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                        return
                    }
                    if (bitmap == null) {
                        runOnUiThread {
                            if (generation != readGeneration) return@runOnUiThread
                            appendDebug("抓取画面失败")
                            resumeWatchIfNeeded()
                            if (!watchTurns) setStatus("抓取画面失败")
                        }
                        return
                    }
                    runOnUiThread {
                        if (generation != readGeneration) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@runOnUiThread
                        }
                        setStatus(getString(R.string.status_ocr))
                        appendDebug("拍照成功 ${bitmap.width}x${bitmap.height}，开始调豆包")
                    }
                    lifecycleScope.launch {
                        if (generation != readGeneration) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@launch
                        }
                        try {
                            val text = withContext(Dispatchers.IO) {
                                pageReader.recognizeText(bitmap) { msg ->
                                    runOnUiThread {
                                        if (generation != readGeneration) return@runOnUiThread
                                        setStatus(msg)
                                        appendDebug(msg)
                                    }
                                }
                            }
                            if (!bitmap.isRecycled) bitmap.recycle()
                            if (generation != readGeneration) return@launch
                            if (text.isBlank() || text == "无法识别") {
                                binding.resultText.text = getString(R.string.ocr_empty)
                                appendDebug(getString(R.string.ocr_empty))
                                resumeWatchIfNeeded()
                                if (!watchTurns) setStatus(getString(R.string.ocr_empty))
                                return@launch
                            }
                            if (ReadTextDeduper.isSame(lastReadText, text)) {
                                Log.e(TAG, "识别内容与上一页相同，跳过朗读")
                                watchTurns = pageTurnAvailable
                                binding.resultText.text = text
                                appendDebug(getString(R.string.status_same_page))
                                resumeWatchIfNeeded(status = getString(R.string.status_same_page))
                                return@launch
                            }
                            lastReadText = text
                            watchTurns = pageTurnAvailable
                            binding.resultText.text = text
                            setStatus(getString(R.string.status_speaking))
                            pageReader.speak(
                                text,
                                onProgress = { msg ->
                                    runOnUiThread {
                                        if (generation != readGeneration) return@runOnUiThread
                                        setStatus(msg)
                                        appendDebug(msg)
                                    }
                                }
                            ) {
                                runOnUiThread {
                                    if (isDestroyed || generation != readGeneration) return@runOnUiThread
                                    resumeWatchIfNeeded()
                                }
                            }
                        } catch (e: CancellationException) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            throw e
                        } catch (e: Exception) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            if (generation != readGeneration) return@launch
                            Log.e(TAG, "识别流程失败", e)
                            val timeout = e is java.net.SocketTimeoutException ||
                                e.message.orEmpty().contains("timeout", ignoreCase = true)
                            val msg = if (timeout) {
                                getString(R.string.status_vision_timeout)
                            } else {
                                "识别失败：${e.message}"
                            }
                            appendDebug(msg)
                            resumeWatchIfNeeded(status = msg)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    runOnUiThread {
                        if (generation != readGeneration) return@runOnUiThread
                        appendDebug("拍照失败：${exception.message}")
                        resumeWatchIfNeeded()
                        if (!watchTurns) setStatus("拍照失败：${exception.message}")
                    }
                }
            }
        )
    }

    private fun resumeWatchIfNeeded(status: String? = null) {
        isProcessing = false
        if (watchTurns && pageTurnAvailable) {
            pageTurnDetector.lockCurrentPage()
            pageTurnDetector.arm()
            setStatus(status ?: getString(R.string.status_wait_turn))
            Log.e(TAG, status ?: "等待翻页")
        } else {
            setStatus(status ?: getString(R.string.status_ready))
        }
    }

    private fun appendDebug(msg: String) {
        val old = binding.resultText.text?.toString().orEmpty()
        val line = "• $msg"
        binding.resultText.text = if (old.isBlank()) line else "$line\n$old".take(1200)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val bitmap = try {
            image.toBitmap()
        } catch (_: Exception) {
            return null
        }
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.previewView.surfaceProvider)
            val capture = buildImageCapture()
            val analysis = buildImageAnalysis()
            cameraProvider.unbindAll()
            try {
                cameraProvider.bindToLifecycle(this, selector, preview, capture, analysis)
                imageCapture = capture
                pageTurnAvailable = true
                Log.e(TAG, "相机已绑定，翻页检测开启")
            } catch (e: Exception) {
                Log.e(TAG, "含分析流的绑定失败，翻页检测不可用", e)
                try {
                    cameraProvider.unbindAll()
                    val fallbackPreview = Preview.Builder().build()
                    fallbackPreview.setSurfaceProvider(binding.previewView.surfaceProvider)
                    val fallbackCapture = buildImageCapture()
                    cameraProvider.bindToLifecycle(this, selector, fallbackPreview, fallbackCapture)
                    imageCapture = fallbackCapture
                    pageTurnAvailable = false
                } catch (fallback: Exception) {
                    Log.e(TAG, "相机绑定失败", fallback)
                    pageTurnAvailable = false
                    setStatus("相机启动失败：${fallback.message}")
                    return@addListener
                }
            }
            setStatus(getString(R.string.status_ready))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildImageCapture(): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
    }

    private fun buildImageAnalysis(): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                pageTurnDetector.onFrame(image)
            } catch (e: Exception) {
                Log.e(TAG, "翻页检测失败", e)
            } finally {
                image.close()
            }
        }
        return analysis
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        readGeneration++
        watchTurns = false
        lastReadText = ""
        pageTurnDetector.disarm()
        cloudVoice.cancel()
        pageReader.release()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "BookReader"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
