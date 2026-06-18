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
 * Voice driver #2: a real Piper voice via sherpa-onnx (offline, on-device). A
 * deep male Russian voice — far closer to TARS than the robotic system TTS.
 *
 * The ~30 MB voice model is downloaded once and unpacked (the .tar.bz2 is
 * extracted by the embedded Python, since Java has no bz2). Synthesis returns
 * float PCM which we play through an AudioTrack.
 */
object PiperVoice {

    // A deep male Russian Piper voice from sherpa-onnx's model release.
    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium.tar.bz2"

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var current: AudioTrack? = null

    fun isReady(): Boolean = tts != null

    private fun dir(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }
    private fun marker(ctx: Context): File = File(dir(ctx), "paths.txt")

    fun isInstalled(ctx: Context): Boolean = marker(ctx).exists()

    /** Load an already-installed voice (fast path on launch). */
    fun load(ctx: Context, log: (String) -> Unit): Boolean {
        if (tts != null) return true
        val m = marker(ctx)
        if (!m.exists()) return false
        return try {
            val (onnx, tokens, data) = m.readText().split("|").let {
                Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" })
            }
            if (!File(onnx).exists()) { log("voice: model files missing, re-install needed"); return false }
            buildTts(onnx, tokens, data)
            log("voice: Piper voice ready")
            true
        } catch (e: Exception) {
            log("voice: failed to load Piper — ${e.message}")
            false
        }
    }

    /** Download + unpack + load the Piper voice (one time). Heavy; call off the UI thread. */
    fun install(ctx: Context, bridge: PyObject, log: (String) -> Unit) {
        try {
            System.loadLibrary("sherpa-onnx-jni")
        } catch (e: Throwable) {
            // The AAR usually self-loads; ignore if already loaded.
        }
        try {
            val archive = File(dir(ctx), "voice.tar.bz2")
            if (!archive.exists() || archive.length() == 0L) {
                log("voice: downloading TARS voice (~30 MB)…")
                download(URL, archive, log)
            }
            log("voice: unpacking…")
            val paths = bridge.callAttr("extract_voice", archive.absolutePath, dir(ctx).absolutePath).toString()
            val parts = paths.split("|")
            val onnx = parts.getOrElse(0) { "" }
            val tokens = parts.getOrElse(1) { "" }
            val data = parts.getOrElse(2) { "" }
            if (onnx.isBlank() || !File(onnx).exists()) {
                log("voice: unpack failed (no .onnx found)")
                return
            }
            buildTts(onnx, tokens, data)
            marker(ctx).writeText("$onnx|$tokens|$data")
            archive.delete()
            log("voice: Piper voice installed and ready — TARS speaks with it now.")
        } catch (e: Exception) {
            log("voice: install failed — ${e.message}")
        }
    }

    private fun buildTts(onnx: String, tokens: String, dataDir: String) {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = onnx, tokens = tokens, dataDir = dataDir),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
        )
        tts = OfflineTts(config = config)
    }

    /** Synthesize and play. Blocks until playback finishes; call off the UI thread. */
    fun speak(text: String, log: (String) -> Unit) {
        val engine = tts ?: return
        try {
            stop()
            val audio = engine.generate(text = text, sid = 0, speed = 0.95f)
            val samples = audio.samples
            if (samples.isEmpty()) return
            val sr = audio.sampleRate
            val minBuf = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(sr)
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
                minBuf,
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
