package com.tars.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Reliable model download over flaky mobile networks. Uses OkHttp (correct
 * cross-host redirect handling — the homemade HttpURLConnection downloader choked
 * on those) and resumes from a partial .part file via HTTP Range. Crucially it
 * reports the real HTTP status code on failure instead of the old opaque
 * "interrupted (0 MB)", and does NOT retry non-retryable 4xx errors forever.
 */
object ModelDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Download [url] to [dest]. Returns null on success, else an error message. */
    fun download(url: String, dest: File, log: (String) -> Unit): String? {
        if (dest.exists() && dest.length() > 0) {
            log("brain: model already present (${dest.length() / (1024 * 1024)} MB)")
            return null
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")

        var attempt = 0
        while (true) {
            attempt++
            val from = if (tmp.exists()) tmp.length() else 0L
            val reqBuilder = Request.Builder().url(url)
            if (from > 0) reqBuilder.header("Range", "bytes=$from-")

            try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    if (code == 416) {                       // already have the whole thing
                        finalize(tmp, dest, log); return null
                    }
                    if (code !in 200..299) {
                        // 4xx (bad URL / not uploaded yet) is permanent — don't hammer it.
                        if (code in 400..499) {
                            return "brain: model not available — HTTP $code at $url " +
                                "(is the GGUF uploaded to the 'models' release?)"
                        }
                        throw RuntimeException("HTTP $code")
                    }
                    val append = code == 206
                    if (!append && from > 0) tmp.delete()
                    val body = resp.body ?: throw RuntimeException("empty body")
                    val total = (if (append) from else 0L) + body.contentLength()

                    RandomAccessFile(tmp, "rw").use { out ->
                        if (append) out.seek(from) else out.setLength(0)
                        val buf = ByteArray(1 shl 16)
                        var done = if (append) from else 0L
                        var lastPct = -1
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct >= lastPct + 5) {
                                        lastPct = pct; log("brain: downloading model… $pct%")
                                    }
                                }
                            }
                        }
                    }
                }
                finalize(tmp, dest, log)
                return null
            } catch (e: Exception) {
                val have = if (tmp.exists()) tmp.length() / (1024 * 1024) else 0
                if (attempt >= 6) {
                    return "brain: download failed after $attempt tries (${have} MB) — ${e.message}"
                }
                log("brain: connection dropped (${have} MB) — retry $attempt in 3s (${e.message})")
                Thread.sleep(3000)
            }
        }
    }

    private fun finalize(tmp: File, dest: File, log: (String) -> Unit) {
        if (!tmp.renameTo(dest)) throw RuntimeException("couldn't finalize model file")
        log("brain: model download complete (${dest.length() / (1024 * 1024)} MB)")
    }
}
