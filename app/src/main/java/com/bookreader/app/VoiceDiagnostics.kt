package com.bookreader.app

import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** 本机语音能力诊断（华为 FakeRecognitionService 会导致系统听写不可用）。 */
object VoiceDiagnostics {
    fun report(context: Context): String {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val activities = pm.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        val avail = SpeechRecognizer.isRecognitionAvailable(context)

        val serviceNames = if (services.isEmpty()) {
            "无"
        } else {
            services.joinToString("\n") {
                "  - ${it.serviceInfo.packageName}/${it.serviceInfo.name}"
            }
        }
        val fake = services.any { it.serviceInfo.name.contains("Fake", ignoreCase = true) }

        return buildString {
            appendLine("【语音调试报告】")
            appendLine("isRecognitionAvailable=$avail")
            appendLine("RecognitionService 数量=${services.size}")
            appendLine(serviceNames)
            appendLine("RECOGNIZE_SPEECH Activity 数量=${activities.size}")
            appendLine("云端 ASR 已配置=${ApiConfig.isAsrConfigured}")
            if (fake) {
                appendLine()
                appendLine("结论：系统只有 FakeRecognitionService（空壳），系统听写不可用。")
                appendLine("请改用云端 ASR：在 local.properties 配置 VOLC_ASR_APP_ID 与 VOLC_ASR_ACCESS_TOKEN。")
            } else if (!ApiConfig.isAsrConfigured && services.isEmpty()) {
                appendLine()
                appendLine("结论：本机无可用识别服务，需配置云端 ASR。")
            }
        }.also { Log.e("BookReader", it) }
    }
}
