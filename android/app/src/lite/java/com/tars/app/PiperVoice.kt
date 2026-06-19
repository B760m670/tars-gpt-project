package com.tars.app

import android.content.Context
import com.chaquo.python.PyObject

/**
 * LITE stub of the Piper voice engine. The Lite build (Android 5+) ships no
 * onnxruntime/Piper, so it always speaks through the system TTS. speak() returns
 * false to tell the shared UI to fall back to the system voice.
 */
object PiperVoice {
    @Suppress("UNUSED_PARAMETER")
    fun load(ctx: Context, log: (String) -> Unit) {}
    @Suppress("UNUSED_PARAMETER")
    fun install(ctx: Context, bridge: PyObject, log: (String) -> Unit) {
        log("voice: the deep Piper voice is in the Full version (Android 8+).")
    }
    @Suppress("UNUSED_PARAMETER")
    fun isReady(lang: String): Boolean = false
    @Suppress("UNUSED_PARAMETER")
    fun speak(text: String, lang: String, log: (String) -> Unit): Boolean = false
    fun stop() {}
}
