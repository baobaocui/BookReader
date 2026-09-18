package com.bookreader.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 录制 16kHz / 16bit / 单声道 PCM，并封装为 WAV，供云端 ASR 使用。
 */
class PcmWavRecorder {
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val recording = AtomicBoolean(false)
    private val pcmBuffer = ByteArrayOutputStream()

    val isRecording: Boolean get() = recording.get()

    fun start() {
        if (recording.get()) return
        pcmBuffer.reset()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minBuf * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("麦克风初始化失败")
        }
        audioRecord = record
        recording.set(true)
        record.startRecording()
        worker = thread(name = "PcmWavRecorder") {
            val buf = ByteArray(minBuf)
            while (recording.get()) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(pcmBuffer) { pcmBuffer.write(buf, 0, n) }
                }
            }
        }
        Log.e(TAG, "recording started")
    }

    fun stopToWav(): ByteArray {
        recording.set(false)
        try {
            worker?.join(1000)
        } catch (_: Exception) {
        }
        worker = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null

        val pcm = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        Log.e(TAG, "recording stopped pcmBytes=${pcm.size}")
        if (pcm.size < SAMPLE_RATE) { // <0.5s
            throw IllegalStateException("录音太短，请多说一会儿再点结束")
        }
        return pcmToWav(pcm)
    }

    fun cancel() {
        recording.set(false)
        try {
            worker?.join(500)
        } catch (_: Exception) {
        }
        worker = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        pcmBuffer.reset()
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val totalDataLen = pcm.size + 36
        val byteRate = SAMPLE_RATE * 2
        out.write("RIFF".toByteArray())
        out.write(intLE(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLE(16))
        out.write(shortLE(1)) // PCM
        out.write(shortLE(1)) // mono
        out.write(intLE(SAMPLE_RATE))
        out.write(intLE(byteRate))
        out.write(shortLE(2)) // block align
        out.write(shortLE(16)) // bits
        out.write("data".toByteArray())
        out.write(intLE(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLE(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    companion object {
        private const val TAG = "BookReader"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
