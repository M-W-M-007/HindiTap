package com.waz.hinditap

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.LANG_MISSING_DATA
import android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
import java.util.Locale

/**
 * Shared text-to-speech. Used to hear how an English word is pronounced
 * ("alleged", "tribunal") and to hear the Hindi translation read back.
 *
 * Speech is strictly on demand. Nothing is queued to play once the engine
 * finishes starting up: a queued utterance arriving later was heard as audio
 * beginning by itself, with no obvious way to stop it.
 */
object TtsHelper {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** Slightly slower than default: this is for making out individual words. */
    private const val RATE = 0.88f

    fun init(context: Context) {
        if (tts != null) return

        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.setSpeechRate(RATE)
        }
    }

    /**
     * @return null on success, or a human-readable reason it could not speak.
     */
    fun speak(context: Context, text: String, locale: Locale): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "Bolne ke liye kuch nahi hai."

        // Nothing is ever queued to play later. Speech happens only on the tap
        // that asked for it — a queued utterance firing after the fact meant
        // audio starting on its own, which is exactly what must not happen.
        if (tts == null) {
            init(context)
            return "Voice engine shuru ho raha hai — ek baar phir dabayein."
        }

        if (!ready) {
            return "Voice engine abhi taiyaar nahi — ek baar phir dabayein."
        }

        val engine = tts ?: return "Voice engine nahi mila."

        return when (engine.setLanguage(locale)) {
            LANG_MISSING_DATA, LANG_NOT_SUPPORTED ->
                if (locale.language == "hi")
                    "Hindi voice phone par nahi hai. Settings → Language & input → Text-to-speech se Hindi voice download karein."
                else
                    "Is bhasha ki voice phone par nahi hai."

            else -> {
                engine.setSpeechRate(RATE)
                engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hinditap")
                null
            }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    val ENGLISH: Locale = Locale.US
    val HINDI: Locale = Locale("hi", "IN")
}
