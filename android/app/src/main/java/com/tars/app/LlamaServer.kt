package com.tars.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs a local llama.cpp server on the device so TARS can think fully offline.
 *
 * The server binary is bundled inside the APK as libllamaserver.so (Android only
 * executes binaries from the native-lib dir, never from app storage). We start
 * it on 127.0.0.1:8080; the Python `local` brain talks to it over llama.cpp's
 * OpenAI-compatible HTTP API. The GGUF model is downloaded separately into the
 * app's files dir.
 */
object LlamaServer {

    @Volatile
    private var process: Process? = null

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun binary(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, "libllamaserver.so")

    fun isSupported(ctx: Context): Boolean = binary(ctx).exists()

    fun isRunning(): Boolean = process?.isAlive == true

    /** Download a GGUF model with coarse progress logging. Returns the file. */
    fun downloadModel(url: String, dest: File, log: (String) -> Unit): File {
        if (dest.exists() && dest.length() > 0) {
            log("brain: model already present")
            return dest
        }
        val tmp = File(dest.absolutePath + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        val total = c.contentLengthLong
        var done = 0L
        var lastPct = -1
        c.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct >= lastPct + 5) {
                            lastPct = pct
                            log("brain: downloading model… $pct%")
                        }
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) throw IllegalStateException("couldn't finalize model file")
        log("brain: model download complete (${dest.length() / (1024 * 1024)} MB)")
        return dest
    }

    /** Start the server against a downloaded model (no-op if already running). */
    fun start(ctx: Context, model: File, log: (String) -> Unit) {
        if (isRunning()) return
        val bin = binary(ctx)
        if (!bin.exists()) {
            log("brain: on-device engine not bundled for this CPU — using cloud/offline")
            return
        }
        if (!model.exists()) {
            log("brain: no model downloaded yet")
            return
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        val pb = ProcessBuilder(
            bin.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", "8080",
            "-c", "2048",
            "-t", threads.toString()
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(File(ctx.filesDir, "llama-server.log"))
        process = pb.start()
        log("brain: llama-server starting on 127.0.0.1:8080 (model loads in a few seconds)")
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
