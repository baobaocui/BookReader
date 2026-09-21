package com.bookreader.app

import android.content.Context
import kotlin.math.roundToInt

/** 朗读语速：0.5×～2.0×，对应豆包 speech_rate [-50, 100]。 */
object SpeechPrefs {
    private const val PREF = "speech"
    private const val KEY_SPEED = "speed"

    const val MIN = 0.5f
    const val MAX = 2.0f
    const val DEFAULT = 1.0f
    const val STEP = 0.1f

    val maxProgress: Int get() = ((MAX - MIN) / STEP).roundToInt()

    fun getSpeed(context: Context): Float {
        val stored = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, DEFAULT)
        return snap(stored)
    }

    fun setSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SPEED, snap(speed))
            .apply()
    }

    fun fromProgress(progress: Int): Float = snap(MIN + progress * STEP)

    fun toProgress(speed: Float): Int =
        ((snap(speed) - MIN) / STEP).roundToInt().coerceIn(0, maxProgress)

    fun toApiSpeechRate(speed: Float): Int =
        ((snap(speed) - 1f) * 100f).roundToInt().coerceIn(-50, 100)

    fun format(speed: Float): String = String.format("%.1f×", snap(speed))

    private fun snap(speed: Float): Float {
        val stepped = ((speed - MIN) / STEP).roundToInt() * STEP + MIN
        return stepped.coerceIn(MIN, MAX)
    }
}
