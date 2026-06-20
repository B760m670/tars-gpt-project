package com.tars.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * TARS' ears. On-device speech recognition via Android's SpeechRecognizer
 * (free, no key). Must be created and called on the main thread; callbacks
 * arrive on the main thread too. [onResult] gets the final recognized text.
 */
class Ears(private val context: Context, private val log: (String) -> Unit) {

    private var sr: SpeechRecognizer? = null
    var onResult: ((String) -> Unit)? = null

    /** Start one listening session. Call on the main thread. */
    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            log("ears: speech recognition not available on this device")
            return
        }
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { log("ears: listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { log("ears: error $error") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onResult?.invoke(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr = r
        r.startListening(intent)
    }

    fun destroy() {
        sr?.destroy()
        sr = null
    }
}
