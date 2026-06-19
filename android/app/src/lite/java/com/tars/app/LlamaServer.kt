package com.tars.app

import android.content.Context
import com.chaquo.python.PyObject
import java.io.File

/**
 * LITE stub of the on-device llama.cpp brain. The Lite build (Android 5+) has no
 * local model — it relies on the cloud + offline brains — so every entry point
 * here is a no-op with the same signatures the shared UI calls.
 */
object LlamaServer {
    fun isSupported(ctx: Context): Boolean = false
    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    @Suppress("UNUSED_PARAMETER")
    fun downloadModel(url: String, dest: File, log: (String) -> Unit): File = dest
    @Suppress("UNUSED_PARAMETER")
    fun start(ctx: Context, model: File, log: (String) -> Unit) {}
    fun stop() {}
}
