package com.bookreader.app

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * 从相机分析流里抽样，大约每 200ms 一帧。确认翻页后回调，本身不请求视觉模型。
 */
internal class PageTurnDetector(
    private val onPageTurned: () -> Unit
) {
    private val tracker = PageTurnTracker()
    private var lastSampleAt = 0L

    fun arm() = tracker.arm()

    fun disarm() = tracker.disarm()

    fun lockCurrentPage() = tracker.lockCurrentPage()

    fun onFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now
        val gray = sampleGray(image) ?: return
        val dist = tracker.onGray(gray) ?: return
        Log.e(TAG, "检测到翻页 dist=$dist")
        onPageTurned()
    }

    private fun sampleGray(image: ImageProxy): IntArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        if (buffer.remaining() <= 0) return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val srcW = image.width
        val srcH = image.height
        if (srcW < 8 || srcH < 8) return null
        val rotation = image.imageInfo.rotationDegrees
        val uprightW = if (rotation == 90 || rotation == 270) srcH else srcW
        val uprightH = if (rotation == 90 || rotation == 270) srcW else srcH
        val outW = PageTurnTracker.SAMPLE_W
        val outH = PageTurnTracker.SAMPLE_H
        val out = IntArray(outW * outH)

        for (oy in 0 until outH) {
            val yStart = oy * uprightH / outH
            var yEnd = (oy + 1) * uprightH / outH
            if (yEnd <= yStart) yEnd = (yStart + 1).coerceAtMost(uprightH)
            for (ox in 0 until outW) {
                val xStart = ox * uprightW / outW
                var xEnd = (ox + 1) * uprightW / outW
                if (xEnd <= xStart) xEnd = (xStart + 1).coerceAtMost(uprightW)
                var sum = 0
                var count = 0
                var y = yStart
                while (y < yEnd) {
                    var x = xStart
                    while (x < xEnd) {
                        val (sx, sy) = PageTurnTracker.uprightToSource(x, y, srcW, srcH, rotation)
                        val index = sy * rowStride + sx * pixelStride
                        if (index in bytes.indices) {
                            sum += bytes[index].toInt() and 0xFF
                            count++
                        }
                        x += BOX_STEP
                    }
                    y += BOX_STEP
                }
                out[oy * outW + ox] = if (count == 0) 0 else sum / count
            }
        }
        return out
    }

    companion object {
        private const val TAG = "BookReader"
        private const val SAMPLE_INTERVAL_MS = 200L
        private const val BOX_STEP = 2
    }
}
