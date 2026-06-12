package com.seoin.emojienglish.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Android TTS for [VoiceGateway.speak] — instant, offline caption/word
 * playback (전작의 만화 캡션 낭독 메커니즘). Distinct from the GPT voice turns:
 * steps use `speak()` for immediate read-aloud (comic captions, word meanings)
 * and `requestVoice()` for interactive coaching.
 *
 * Speech rate is kept slow (0.8) — 전작은 만화 낭독에 0.68을 썼고, 아이가 듣고
 * 따라 읽기에 느린 속도가 핵심이라는 피드백이 있었다.
 */
@Singleton
class LocalTts @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayDeque<Pair<String, String>>()

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "TTS init failed: $status")
                return@TextToSpeech
            }
            synchronized(pending) {
                while (pending.isNotEmpty()) {
                    val (text, lang) = pending.removeFirst()
                    doSpeak(text, lang)
                }
            }
        }
    }

    fun speak(text: String, lang: String) {
        if (text.isBlank()) return
        ensure()
        if (!ready) {
            synchronized(pending) { pending.addLast(text to lang) }
            return
        }
        doSpeak(text, lang)
    }

    fun stop() {
        tts?.stop()
        synchronized(pending) { pending.clear() }
    }

    private fun doSpeak(text: String, lang: String) {
        val engine = tts ?: return
        runCatching { engine.language = Locale.forLanguageTag(lang) }
        engine.setSpeechRate(SPEECH_RATE)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seoin_tts_${System.currentTimeMillis()}")
    }

    private companion object {
        const val TAG = "SeoinVoice"
        const val SPEECH_RATE = 0.8f
    }
}
