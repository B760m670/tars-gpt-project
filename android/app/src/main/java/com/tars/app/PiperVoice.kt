package com.tars.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.chaquo.python.PyObject
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voice driver #2: real Piper voices via sherpa-onnx (offline, on-device). Deep
 * male voices — far closer to TARS than the robotic system TTS — one per
 * language (Russian + English), routed by the text.
 *
 * Each ~30 MB voice is downloaded once and its .tar.bz2 unpacked by the embedded
 * Python (Java has no bz2). Synthesis returns float PCM played via AudioTrack.
 */
object PiperVoice {

    // Deep male Piper voices from sherpa-onnx's model release, by language.
    private val URLS = mapOf(
        "ru" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium.tar.bz2",
        "en" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-medium.tar.bz2",
    )

    private val engines = HashMap<String, OfflineTts>()
    @Volatile private var current: AudioTrack? = null

    fun isReady(lang: String): Boolean = engines.containsKey(lang)
    fun isAnyReady(): Boolean = engines.isNotEmpty()

    private fun root(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }
    private fun langDir(ctx: Context, lang: String): File = File(root(ctx), lang).apply { mkdirs() }
    private fun marker(ctx: Context, lang: String): File = File(langDir(ctx, lang), "paths.txt")

    /** Load any already-installed voices (fast path on launch). */
    fun load(ctx: Context, log: (String) -> Unit) {
        for (lang in URLS.keys) {
            if (engines.containsKey(lang)) continue
            val m = marker(ctx, lang)
            if (!m.exists()) continue
            try {
                val (onnx, tokens, data) = m.readText().split("|").let {
                    Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" })
                }
                if (File(onnx).exists()) {
                    engines[lang] = buildTts(onnx, tokens, data)
                    log("voice: $lang Piper voice ready")
                }
            } catch (e: Exception) {
                log("voice: failed to load $lang Piper — ${e.message}")
            }
        }
    }

    /** Download + unpack + load both voices (one time). Heavy; call off the UI thread. */
    fun install(ctx: Context, bridge: PyObject, log: (String) -> Unit) {
        try {
            System.loadLibrary("sherpa-onnx-jni")
        } catch (e: Throwable) {
            // The AAR usually self-loads; ignore if already loaded.
        }
        for ((lang, url) in URLS) {
            if (engines.containsKey(lang)) continue
            try {
                val archive = File(langDir(ctx, lang), "voice.tar.bz2")
                if (!archive.exists() || archive.length() == 0L) {
                    log("voice: downloading $lang voice (~30 MB)…")
                    download(url, archive, log)
                }
                log("voice: unpacking $lang…")
                val paths = bridge.callAttr("extract_voice", archive.absolutePath, langDir(ctx, lang).absolutePath).toString()
                val parts = paths.split("|")
                val onnx = parts.getOrElse(0) { "" }
                if (onnx.isBlank() || !File(onnx).exists()) {
                    log("voice: $lang unpack failed (no .onnx)")
                    continue
                }
                engines[lang] = buildTts(onnx, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                marker(ctx, lang).writeText(paths)
                archive.delete()
                log("voice: $lang voice installed and ready")
            } catch (e: Exception) {
                log("voice: $lang install failed — ${e.message}")
            }
        }
        log("voice: TARS now speaks with the Piper voice(s).")
    }

    private fun buildTts(onnx: String, tokens: String, dataDir: String): OfflineTts {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = onnx, tokens = tokens, dataDir = dataDir),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
        )
        return OfflineTts(config = config)
    }

    /** Synthesize + play in the given language. Returns false if no voice for it
     *  (so the caller can fall back to system TTS). Call off the UI thread. */
    fun speak(text: String, lang: String, log: (String) -> Unit): Boolean {
        val engine = engines[lang] ?: return false
        try {
            stop()
            val audio = engine.generate(text = text, sid = 0, speed = 0.95f)
            val samples = CommsFilter.apply(audio.samples, audio.sampleRate)
            if (samples.isEmpty()) return true
            val sr = audio.sampleRate
            // Frame size for float mono is 4 bytes; the buffer must be a valid,
            // frame-aligned size or AudioTrack throws "Invalid audio buffer size".
            var bufBytes = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (bufBytes <= 0) bufBytes = sr * 4   // ~1s of float mono
            bufBytes = (bufBytes / 4) * 4
            if (bufBytes < 4) bufBytes = 4
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
                bufBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            current = track
            track.play()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.stop()
            track.release()
            if (current === track) current = null
        } catch (e: Exception) {
            log("voice: synthesis error — ${e.message}")
        }
        return true
    }

    fun stop() {
        try {
            current?.pause()
            current?.flush()
            current?.release()
        } catch (e: Exception) {
            // ignore
        }
        current = null
    }

    private fun download(url: String, dest: File, log: (String) -> Unit) {
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
                        if (pct >= lastPct + 10) { lastPct = pct; log("voice: downloading… $pct%") }
                    }
                }
            }
        }
        tmp.renameTo(dest)
    }
}
