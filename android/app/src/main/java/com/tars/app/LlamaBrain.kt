package com.tars.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * The real TARS mind: drives the in-process llama.cpp engine on a single worker
 * thread (the native side is stateful and single-threaded). No HTTP server, no
 * port, no health polling — load a GGUF, set the character once, then stream
 * replies. Qwen3's internal <think> monologue is suppressed (/no_think) and any
 * residual think-block is stripped so TARS speaks instead of musing.
 */
class LlamaBrain(context: Context) {

    private val appContext = context.applicationContext
    private val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
    private val worker = Executors.newSingleThreadExecutor()
    private val native = LlamaNative()

    @Volatile var ready = false
        private set

    /** Load a model and imprint the TARS character. Blocks the caller's thread;
     *  call from a background thread. Returns null on success, else an error. */
    fun load(modelPath: String, systemPrompt: String): String? {
        return runOnWorker {
            try {
                LlamaNative.ensureLibrary()
                native.init(nativeLibDir)
                if (!File(modelPath).let { it.exists() && it.canRead() })
                    return@runOnWorker "model file missing or unreadable"
                if (native.load(modelPath) != 0)
                    return@runOnWorker "model failed to load (unsupported/corrupt GGUF?)"
                if (native.prepare() != 0)
                    return@runOnWorker "engine failed to allocate context"
                if (native.processSystemPrompt(systemPrompt) != 0)
                    return@runOnWorker "failed to set the character prompt"
                ready = true
                null
            } catch (e: Throwable) {
                "engine error: ${e.message}"
            }
        }
    }

    /** Re-imprint the character on the running engine (e.g. after the dials
     *  change). This resets the conversation context — expected when the
     *  personality changes. Returns null on success, else an error. */
    fun setSystemPrompt(systemPrompt: String): String? {
        if (!ready) return "engine not loaded"
        return runOnWorker {
            if (native.processSystemPrompt(systemPrompt) != 0) "failed to apply settings" else null
        }
    }

    /** One self-test line — proof the engine actually thinks. */
    fun selfTest(): String =
        reply("/no_think Ответь одним коротким предложением в характере TARS: ты в сети?", null)

    /**
     * Generate a reply. [onToken] (optional) receives clean, think-stripped text
     * as it streams. Returns the full reply. Runs on the worker thread.
     */
    fun reply(userText: String, onToken: ((String) -> Unit)?): String {
        if (!ready) return ""
        return runOnWorker {
            val raw = StringBuilder()
            var emitted = 0
            if (native.processUserPrompt("/no_think " + userText, N_PREDICT) != 0)
                return@runOnWorker ""
            while (true) {
                val piece = native.generateNextToken() ?: break
                if (piece.isEmpty()) continue
                raw.append(piece)
                val visible = stripThink(raw.toString())
                if (onToken != null && visible.length > emitted) {
                    onToken(visible.substring(emitted))
                    emitted = visible.length
                }
            }
            stripThink(raw.toString()).trim()
        }
    }

    fun shutdown() {
        runOnWorker {
            try { if (ready) native.unload(); native.shutdown() } catch (_: Throwable) {}
            ready = false
        }
        worker.shutdown()
    }

    private fun <T> runOnWorker(block: () -> T): T =
        worker.submit(block).get()

    /** Remove Qwen3's <think>…</think> block, holding back an unclosed one. */
    private fun stripThink(s: String): String {
        val open = s.indexOf("<think>")
        if (open < 0) return s
        val close = s.indexOf("</think>")
        if (close < 0) return s.substring(0, open)
        return s.substring(0, open) + s.substring(close + "</think>".length)
    }

    companion object {
        private const val N_PREDICT = 240
    }
}
