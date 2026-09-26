package com.bookreader.app

import android.content.Context

class TtsPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getEngine(): TtsEngine {
        val saved = TtsEngine.fromId(prefs.getString(KEY_ENGINE, null))
        if (saved != null && TtsEngine.isAvailable(saved)) return saved
        return TtsEngine.preferredDefault()
    }

    fun setEngine(engine: TtsEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.id).apply()
    }

    companion object {
        private const val PREFS = "bookreader_tts"
        private const val KEY_ENGINE = "engine"
    }
}
