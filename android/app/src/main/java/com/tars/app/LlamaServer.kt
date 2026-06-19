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

    fun logFile(ctx: Context): File = File(ctx.filesDir, "llama-server.log")

    /** Last few lines of the server's own log — the place its errors land. */
    fun tailLog(ctx: Context, lines: Int = 8): String {
        val f = logFile(ctx)
        if (!f.exists()) return "(no server log yet)"
        return try {
            f.readLines().takeLast(lines).joinToString("\n").ifBlank { "(server log empty)" }
        } catch (e: Exception) {
            "(can't read server log: ${e.message})"
        }
    }

    /** Download a GGUF model RELIABLY over flaky mobile networks: it resumes from
     *  a partial .part file (HTTP Range) and retries on failure, so a 1 GB model
     *  survives dropped connections instead of starting over. Returns the file. */
    fun downloadModel(url: String, dest: File, log: (String) -> Unit): File {
        if (dest.exists() && dest.length() > 0) {
            log("brain: model already present")
            return dest
        }
        val tmp = File(dest.absolutePath + ".part")
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadResumable(url, tmp, log)
                if (!tmp.renameTo(dest)) throw IllegalStateException("couldn't finalize model file")
                log("brain: model download complete (${dest.length() / (1024 * 1024)} MB)")
                return dest
            } catch (e: Exception) {
                val have = if (tmp.exists()) tmp.length() / (1024 * 1024) else 0
                if (attempt >= 8) {
                    log("brain: model download failed after $attempt tries (${have} MB) — ${e.message}")
                    throw e
                }
                log("brain: download interrupted (${have} MB) — retry $attempt in 3s (${e.message})")
                Thread.sleep(3000)
            }
        }
    }

    private fun downloadResumable(url: String, tmp: File, log: (String) -> Unit) {
        val from = if (tmp.exists()) tmp.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        c.connect()
        val code = c.responseCode
        // 206 = resumed; 200 = server ignored Range, so restart from scratch.
        val append = code == 206
        if (!append && from > 0) tmp.delete()
        val total = from + c.contentLengthLong
        var done = if (append) from else 0L
        var lastPct = -1
        c.inputStream.use { input ->
            FileOutputStream(tmp, append).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct >= lastPct + 5) { lastPct = pct; log("brain: downloading model… $pct%") }
                    }
                }
            }
        }
    }

    /** Start the server against a downloaded model (no-op if already running). */
    fun start(ctx: Context, model: File, log: (String) -> Unit) {
        if (isRunning()) return
        val bin = binary(ctx)
        if (!bin.exists()) {
            log("brain: on-device engine not bundled for this CPU — offline mode")
            return
        }
        if (!model.exists()) {
            log("brain: no model downloaded yet")
            return
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val pb = ProcessBuilder(
            bin.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", "8080",
            "-c", "2048",
            "-t", threads.toString()
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(logFile(ctx))
        val proc = pb.start()
        process = proc
        log("brain: llama-server starting on 127.0.0.1:8080 (model loads in a few seconds)")

        // Watch startup and report the outcome — otherwise a crash is invisible
        // and TARS silently stays on the offline brain.
        Thread {
            for (i in 1..90) {
                Thread.sleep(1000)
                if (!proc.isAlive) {
                    val code = try { proc.exitValue() } catch (e: Exception) { -1 }
                    log("brain: engine exited (code $code). Server log:\n${tailLog(ctx)}")
                    return@Thread
                }
                if (health()) {
                    log("brain: local engine READY — TARS now thinks on-device. Say something.")
                    return@Thread
                }
            }
            log("brain: engine didn't become ready in 90s. Server log:\n${tailLog(ctx)}")
        }.start()
    }

    /** True once llama-server answers /health with status ok (200). */
    private fun health(): Boolean {
        return try {
            val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 1500
            val code = c.responseCode
            c.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
