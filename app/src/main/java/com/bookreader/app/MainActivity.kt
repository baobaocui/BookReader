package com.bookreader.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var pageReader: PageReader
    private val cloudVoice = CloudVoiceCommander()

    private var imageCapture: ImageCapture? = null
    private var isProcessing = false

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
        pageReader = PageReader(this)
        pageReader.initTts { ready ->
            if (!ready) {
                runOnUiThread {
                    Toast.makeText(this, "TTS 初始化失败，仍可识别文字", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 启动时打印语音诊断，方便逐步调试
        binding.resultText.text = VoiceDiagnostics.report(this)

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
        if (ReadIntentParser.isReadRequest(utterance)) {
            isProcessing = false
            captureAndRead()
        } else {
            isProcessing = false
            setStatus(getString(R.string.intent_not_read))
        }
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
        setStatus(getString(R.string.status_capture))
        appendDebug("点击读当前页")
        Log.e(TAG, "点击读当前页，开始 takePicture")

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
                    if (bitmap == null) {
                        runOnUiThread {
                            isProcessing = false
                            setStatus("抓取画面失败")
                            appendDebug("抓取画面失败")
                        }
                        return
                    }
                    runOnUiThread {
                        setStatus(getString(R.string.status_ocr))
                        appendDebug("拍照成功 ${bitmap.width}x${bitmap.height}，开始调豆包")
                    }
                    lifecycleScope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) {
                                pageReader.recognizeText(bitmap) { msg ->
                                    runOnUiThread {
                                        setStatus(msg)
                                        appendDebug(msg)
                                    }
                                }
                            }
                            if (!bitmap.isRecycled) bitmap.recycle()
                            if (text.isBlank() || text == "无法识别") {
                                binding.resultText.text = getString(R.string.ocr_empty)
                                setStatus(getString(R.string.ocr_empty))
                                isProcessing = false
                                return@launch
                            }
                            binding.resultText.text = text
                            setStatus(getString(R.string.status_speaking))
                            pageReader.speak(text) {
                                runOnUiThread {
                                    isProcessing = false
                                    setStatus(getString(R.string.status_ready))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "识别流程失败", e)
                            isProcessing = false
                            setStatus("识别失败：${e.message}")
                            appendDebug("识别失败：${e.message}")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    runOnUiThread {
                        isProcessing = false
                        setStatus("拍照失败：${exception.message}")
                        appendDebug("拍照失败：${exception.message}")
                    }
                }
            }
        )
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
            val previewView = binding.previewView
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            imageCapture = ImageCapture.Builder()
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

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            setStatus(getString(R.string.status_ready))
        }, ContextCompat.getMainExecutor(this))
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
        cloudVoice.cancel()
        pageReader.release()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "BookReader"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
