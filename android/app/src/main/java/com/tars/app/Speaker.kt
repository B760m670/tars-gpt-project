package com.tars.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Voice socket, driver #1: the device's built-in text-to-speech. Offline, no
 * native build, speaks Russian or English depending on the text. Tuned low and
 * measured to feel TARS-like. A later driver (Piper / sherpa-onnx) can replace
 * this behind the same speak() call for a more custom voice.
 */
class Speaker(ctx: Context, private val log: (String) -> Unit) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(ctx, this)
    @Volatile private var ready = false
    @Volatile var enabled = true

    private val cyrillic = Regex("[А-Яа-яЁё]")

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts.setPitch(0.7f)        // deep, masculine
            tts.setSpeechRate(0.95f)  // calm, deliberate
            log("voice: ready (system TTS — a TARS-like Piper voice comes later)")
        } else {
            log("voice: system TTS unavailable (status $status)")
        }
    }

    /** Speak in the same language the text is written in, preferring a male voice. */
    fun speak(text: String) {
        if (!ready || !enabled || text.isBlank()) return
        val locale = if (cyrillic.containsMatchIn(text)) Locale("ru") else Locale.ENGLISH
        val res = tts.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            log("voice: '$locale' not installed, using device default")
            tts.language = Locale.getDefault()
        }
        selectMaleVoice(locale)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tars")
    }

    /** Best effort: pick a male voice for this language if the engine offers one.
     *  Many devices only ship a female voice, in which case the deep pitch is the
     *  closest we get until the Piper voice lands. */
    private fun selectMaleVoice(locale: Locale) {
        try {
            val candidates = tts.voices ?: return
            val male = candidates.firstOrNull { v ->
                v.locale.language == locale.language &&
                    v.name.lowercase().contains("male") &&
                    !v.name.lowercase().contains("female")
            }
            if (male != null && tts.voice?.name != male.name) {
                tts.voice = male
                log("voice: using ${male.name}")
            }
        } catch (e: Exception) {
            // ignore — keep the default voice
        }
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }
}
