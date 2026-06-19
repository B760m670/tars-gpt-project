package com.tars.app

import android.content.Context
import org.json.JSONObject
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

    /** Start the server against a downloaded model (no-op if already running).
     *  If [mmproj] is given (a vision projector), the model serves images too —
     *  TARS can see. */
    fun start(ctx: Context, model: File, mmproj: File? = null, log: (String) -> Unit) {
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
        // Kept deliberately minimal so the server reliably STARTS. (An earlier
        // bare "-fa" crashed newer llama.cpp, which now requires "-fa on"; flash
        // attention + KV-cache quant are optimizations we can re-add once the core
        // is verified on-device.) A 2048 context keeps the KV cache small enough to
        // fit comfortably on a 6 GB phone — a too-large cache is part of why the
        // engine spent a minute+ "loading" and sometimes never became ready.
        val args = arrayListOf(
            bin.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", "8080",
            "-c", "2048",
            "-t", threads.toString(),
            // Use the model's own chat template (e.g. Qwen3's /no_think switch).
            "--jinja"
        )
        if (mmproj != null && mmproj.exists()) {
            args.add("--mmproj"); args.add(mmproj.absolutePath)
            log("brain: vision projector loaded — TARS can see")
        }
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.redirectOutput(logFile(ctx))
        val proc = pb.start()
        process = proc
        log("brain: llama-server starting on 127.0.0.1:8080 (model loads in a few seconds)")

        // Watch startup and report the outcome — otherwise a crash is invisible
        // and TARS silently stays on the offline brain.
        // 300s budget: a 2.5 GB Qwen3-4B takes ~60-120s to map into RAM on A32.
        Thread {
            for (i in 1..300) {
                Thread.sleep(1000)
                if (!proc.isAlive) {
                    val code = try { proc.exitValue() } catch (e: Exception) { -1 }
                    log("brain: engine exited (code $code). Server log:\n${tailLog(ctx)}")
                    return@Thread
                }
                if (health()) {
                    log("brain: local engine READY — running a quick self-test so you can see it's alive…")
                    // PROOF, end-to-end: actually ask the model for one line and log
                    // what it says. If this shows real TARS words, the brain works.
                    // If it shows an error, that error is the real bug — not a guess.
                    val proof = selfTest()
                    log("brain: self-test — TARS says: \"$proof\"")
                    log("brain: TARS now thinks on-device. Talk to him.")
                    return@Thread
                }
                // Progress heartbeat every 20s so the user knows it's still loading.
                if (i % 20 == 0) log("brain: loading model… ${i}s")
            }
            log("brain: engine didn't become ready in 300s. Server log:\n${tailLog(ctx)}")
        }.start()
    }

    /** End-to-end proof the engine can think: send one tiny chat completion and
     *  return what the model actually said (or the real error). This is the line
     *  that finally distinguishes "loaded but mute" from "genuinely working". */
    private fun selfTest(): String {
        return try {
            val body = "{\"messages\":[{\"role\":\"user\"," +
                "\"content\":\"/no_think Ответь одним коротким предложением в характере TARS: ты в сети?\"}]," +
                "\"temperature\":0.7,\"max_tokens\":48}"
            val c = URL("http://127.0.0.1:8080/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val resp = if (c.responseCode in 200..299)
                c.inputStream.bufferedReader().readText()
            else
                "HTTP ${c.responseCode}: " + (c.errorStream?.bufferedReader()?.readText() ?: "")
            c.disconnect()
            val m = Regex("\"content\"\\s*:\\s*\"(.*?)\"", RegexOption.DOT_MATCHES_ALL).find(resp)
            m?.groupValues?.get(1)
                ?.replace("\\n", " ")?.replace("\\\"", "\"")?.replace("\\u003c", "<")?.replace("\\u003e", ">")
                ?.trim()
                ?.ifBlank { "(empty reply)" }
                ?: "(no content in response: ${resp.take(160)})"
        } catch (e: Exception) {
            "self-test failed: ${e.message}"
        }
    }

    /** The entire llama-server log — the ground truth for diagnosing a stuck or
     *  crashing engine. Surfaced via the menu so it can be copied and shared. */
    fun fullLog(ctx: Context): String {
        val f = logFile(ctx)
        if (!f.exists()) return "(no server log yet — start the brain first)"
        return try {
            f.readText().ifBlank { "(server log empty)" }
        } catch (e: Exception) {
            "(can't read server log: ${e.message})"
        }
    }

    /** Public readiness probe for the chat path. */
    fun ready(): Boolean = health()

    /** Stream a chat completion token-by-token so TARS can talk WHILE he thinks.
     *  [messagesJson] is the OpenAI 'messages' array (built by Python). Each clean
     *  delta (Qwen3's <think> monologue stripped out live) is handed to [onClean]
     *  as it arrives. Returns the full clean reply, or "" on failure. */
    fun streamChat(messagesJson: String, onClean: (String) -> Unit, log: (String) -> Unit): String {
        val raw = StringBuilder()
        val clean = StringBuilder()
        var emitted = 0
        val body = "{\"messages\":$messagesJson," +
            "\"temperature\":0.7,\"top_p\":0.8,\"max_tokens\":240,\"stream\":true}"
        var c: HttpURLConnection? = null
        try {
            c = URL("http://127.0.0.1:8080/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText() ?: ""
                log("brain: stream HTTP ${c.responseCode} — ${err.take(160)}")
                return ""
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val data = line.substring(5).trim()
                if (data == "[DONE]" || data.isEmpty()) return@forEachLine
                val piece = try {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("delta")?.optString("content") ?: ""
                } catch (e: Exception) { "" }
                if (piece.isEmpty()) return@forEachLine
                raw.append(piece)
                val visible = stripThink(raw.toString())
                if (visible.length > emitted) {
                    val chunk = visible.substring(emitted)
                    emitted = visible.length
                    clean.append(chunk)
                    onClean(chunk)
                }
            }
        } catch (e: Exception) {
            log("brain: stream error — ${e.message}")
        } finally {
            try { c?.disconnect() } catch (e: Exception) {}
        }
        return clean.toString().trim()
    }

    /** Remove Qwen3's <think>…</think> monologue as text streams in. While the
     *  block is still open (no closing tag yet), everything after <think> is held
     *  back so it's never shown or spoken. */
    private fun stripThink(s: String): String {
        val open = s.indexOf("<think>")
        if (open < 0) return s
        val close = s.indexOf("</think>")
        if (close < 0) return s.substring(0, open)
        return s.substring(0, open) + s.substring(close + "</think>".length)
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
