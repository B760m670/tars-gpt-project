package com.tars.app

/**
 * Raw JNI surface to the on-device llama.cpp engine (see app/src/main/cpp/tars_llm.cpp).
 * One process-local instance; all calls MUST happen on a single dedicated thread
 * (the native side keeps global state). [LlamaBrain] owns that thread — never call
 * these directly from elsewhere.
 *
 * The function names here are bound by name to the JNI symbols in tars_llm.cpp,
 * so they must not be renamed without updating the C++ side.
 */
internal class LlamaNative {
    external fun init(nativeLibDir: String)
    external fun load(modelPath: String): Int
    external fun prepare(): Int
    external fun systemInfo(): String
    external fun processSystemPrompt(systemPrompt: String): Int
    external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    external fun generateNextToken(): String?
    external fun unload()
    external fun shutdown()

    companion object {
        @Volatile private var loaded = false

        /** Load libtarsllm.so (and its companions) once. */
        @Synchronized
        fun ensureLibrary() {
            if (loaded) return
            System.loadLibrary("tarsllm")
            loaded = true
        }
    }
}
